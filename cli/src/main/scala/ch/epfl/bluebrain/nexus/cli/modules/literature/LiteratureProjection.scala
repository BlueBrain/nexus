package ch.epfl.bluebrain.nexus.cli.modules.literature

import java.text.BreakIterator
import java.util.Locale
import java.util.regex.Pattern.quote

import _root_.io.circe.syntax._
import _root_.io.circe.{Json, JsonObject}
import cats.data.EitherT
import cats.effect.{Blocker, ConcurrentEffect, ContextShift, Timer}
import cats.implicits._
import cats.kernel.Eq
import ch.epfl.bluebrain.nexus.cli.CliError.ClientError.Unexpected
import ch.epfl.bluebrain.nexus.cli.CliError.JsonTransformationError
import ch.epfl.bluebrain.nexus.cli.ProjectionPipes._
import ch.epfl.bluebrain.nexus.cli.clients.BlueBrainSearchClient.Embedding
import ch.epfl.bluebrain.nexus.cli.clients._
import ch.epfl.bluebrain.nexus.cli.config.literature.BlueBrainSearchConfig.ModelType
import ch.epfl.bluebrain.nexus.cli.config.literature.LiteratureConfig
import ch.epfl.bluebrain.nexus.cli.config.{AppConfig, PrintConfig}
import ch.epfl.bluebrain.nexus.cli.sse.{Event, EventStream, Offset, ProjectLabel}
import ch.epfl.bluebrain.nexus.cli.utils.Resources
import ch.epfl.bluebrain.nexus.cli.{logRetryErrors, CliErrOr, CliError, Console}
import fs2.Stream
import org.http4s.Uri
import retry.CatsEffect._
import retry.RetryPolicy
import retry.syntax.all._

import scala.annotation.tailrec

class LiteratureProjection[F[_]: ContextShift](
    console: Console[F],
    esc: EventStreamClient[F],
    es: ElasticSearchClient[F],
    bbs: BlueBrainSearchClient[F],
    cfg: AppConfig
)(implicit blocker: Blocker, F: ConcurrentEffect[F], T: Timer[F])
    extends Resources {

  private type ComputedVectors = List[Either[CliError, (Event, ProjectLabel, String, String, Embedding, String)]]
  private val abstractKey = "abstract"
  private val bodyKey     = "articleBody"

  private val lc: LiteratureConfig                          = cfg.literature
  private val equalIgnoreRawJson: Eq[(Event, ProjectLabel)] = Eq.instance {
    case ((ev1, proj1), (ev2, proj2)) => ev1.resourceId == ev2.resourceId && ev1.rev == ev2.rev && proj1 == proj2
  }

  implicit private val printCfg: PrintConfig       = lc.print
  implicit private val c: Console[F]               = console
  implicit private val retryPolicy: RetryPolicy[F] = cfg.env.httpClient.retry.retryPolicy

  private def paperIndexPayload(modelDimension: Int) =
    jsonContentOf(
      "/literature/elastic_search_paper_index.json",
      Map(quote("{model_dims}") -> modelDimension.toString)
    )

  def run: F[Unit] =
    for {
      _           <- console.println("Starting literature projection...")
      offset      <- Offset.load(lc.offsetFile)
      _           <- if (offset.isEmpty) initializeIndices else F.unit
      eventStream <- projectsEventStream(offset)
      stream       = executeStream(eventStream)
      saveOffset   = writeOffsetPeriodically(eventStream)
      _           <- F.race(stream, saveOffset)
    } yield ()

  private def projectsEventStream(offset: Option[Offset]) =
    lc.projects.toList match {
      case ((org, proj), _) :: Nil => esc(org, proj, offset)
      case _                       => esc(offset)
    }

  private def paperIndex(modelName: String) =
    lc.elasticSearch.modelIndicesMap(modelName)

  private def paperId(ev: Event, proj: ProjectLabel): String =
    s"${proj.show}_${ev.resourceId.renderString}"

  private def initializeIndices: F[Unit] =
    lc.blueBrainSearch.modelTypes
      .foldM(()) {
        case (_, ModelType(name, dimensions)) =>
          val idx = paperIndex(name)
          EitherT(es.deleteIndex(idx)) >>
            EitherT(es.createIndex(idx, paperIndexPayload(dimensions))).as(())
      }
      .rethrowT

  private def executeStream(eventStream: EventStream[F]): F[Unit] = {
    implicit def logOnError[A] = logRetryErrors[F, A]("fetching SSE")
    def successCondition[A]    = cfg.env.httpClient.retry.condition.notRetryFromEither[A] _
    val compiledStream         = eventStream.value.flatMap { stream =>
      stream
        .map {
          case Right((ev, org, proj)) =>
            val existsType = lc.projects.get((org, proj)).exists(pc => ev.resourceTypes.exists(pc.types.contains))
            Right(Option.when(existsType)((ev, org, proj)))
          case Left(err)              => Left(err)
        }
        .through(printEventProgress(console))
        .flatMap {
          case (ev, _, proj) =>
            (for {
              abstractSection <- extractSectionSentences(abstractKey, ev.raw)
              bodySection     <- extractSectionSentences(bodyKey, ev.raw)
            } yield (abstractSection ++ bodySection).flatMap {
              case (section, sentences) => sentences.map(text => (ev, proj, section, text))
            }) match {
              case Some(sentences) => Stream(sentences.toSeq.map(Right.apply): _*)
              case None => Stream(Left((ev, proj, textExtractionErr(ev.resourceId))))
            }
        }
        .evalMap[F, ComputedVectors] {
          case Right((ev, proj, section, text)) =>
            lc.blueBrainSearch.modelTypes.map {
              case ModelType(modelName, _) =>
                bbs.embedding(modelName, text).map(_.map(emb => (ev, proj, section, text, emb, modelName)))
            }.sequence
          case Left((ev, proj, err))            =>
            console
              .printlnErr(s"Computing embedding for paper '${ev.resourceId}' on project '${proj.show}' failed")
              .as(List(Left(err)))
        }
        .flatMap(list => Stream(list: _*))
        .through(printProjectionProgress(console, sentenceEmbeddingsProcessLine))
        .groupAdjacentBy { case (ev, proj, _, _, _, _) => (ev, proj) }(equalIgnoreRawJson)
        .evalMap {
          case ((ev, proj), chunks) =>
            val sentences = chunks.map {
              case (_, _, section, text, Embedding(vector), modelName) => (section, text, vector, modelName)
            }.toList
            writeJsonPaper(ev, proj, sentences)
        }
        .through(printProjectionProgress(console, paperProcessLine))
        .attempt
        .map(_.leftMap(err => Unexpected(Option(err.getMessage).getOrElse(""))).map(_ => ()))
        .compile
        .lastOrError
    }

    compiledStream.retryingM(successCondition) >> F.unit
  }

  private def textExtractionErr(resourceId: Uri): CliError =
    JsonTransformationError(s"Could not extract texts from payload on event with id '$resourceId'")

  private def sourceExtractionErr(resourceId: Uri): CliError =
    JsonTransformationError(s"Could not extract '_source' from payload on event with id '$resourceId'")

  private def extractSectionSentences(section: String, json: Json): Option[Option[(String, Seq[String])]] =
    json.hcursor
      .downField("_source")
      .get[Option[String]](section)
      .toOption
      .map { sectionStrOpt =>
        sectionStrOpt
          .map(_.split("\\n").toSeq.flatMap(splitIntoSentences).filterNot(_.isBlank))
          .map(section -> _)
      }

  private def splitIntoSentences(text: String): Seq[String] = {

    val iterator: BreakIterator = BreakIterator.getSentenceInstance(Locale.US)
    iterator.setText(text)

    @tailrec
    def inner(start: Int, end: Int, acc: Seq[String] = Vector.empty): Seq[String] =
      if (end == BreakIterator.DONE) acc
      else inner(end, iterator.next, acc :+ text.substring(start, end))
    inner(iterator.first, iterator.next)
  }

  private def writeJsonPaper(
      ev: Event,
      proj: ProjectLabel,
      sentences: Seq[(String, String, Seq[Double], String)]
  ): F[CliErrOr[Unit]] = {
    ev.raw.hcursor.get[JsonObject]("_source") match {
      case Left(_)          => F.pure(Left(sourceExtractionErr(ev.resourceId)))
      case Right(sourceObj) =>
        val jsonByModel = sentences.foldLeft(Map.empty[String, Seq[Json]]) {
          case (acc, (section, text, vector, modelName)) =>
            val jsonSeq = acc.getOrElse(modelName, Vector.empty)
            val json    = Json.obj(
              "text"      -> text.asJson,
              "embedding" -> vector.asJson,
              "index"     -> jsonSeq.size.asJson,
              "section"   -> section.asJson
            )
            acc + (modelName -> (jsonSeq :+ json))
        }
        jsonByModel.toList
          .foldM(()) {
            case (_, (modelName, jsonSeq)) =>
              val document = sourceObj.add("sentences", Json.arr(jsonSeq: _*)).asJson
              val id       = paperId(ev, proj)
              EitherT(es.index(paperIndex(modelName), id, document).map[CliErrOr[Unit]](identity)).semiflatMap { _ =>
                console.println(s"Indexed paper with id '$id' for model '$modelName'")
              }
          }
          .value
    }
  }

  private def sentenceEmbeddingsProcessLine(success: Long, errors: Long): String =
    s"Processed ${success + errors} sentence embeddings (success: $success, errors: $errors)"

  private def paperProcessLine(success: Long, errors: Long): String =
    s"Processed ${success + errors} papers (success: $success, errors: $errors)"

  private def writeOffsetPeriodically(sseStream: EventStream[F]): F[Unit] =
    Stream
      .repeatEval {
        sseStream.currentEventId().flatMap {
          case Some(offset) => offset.write(lc.offsetFile)
          case None         => F.unit
        }
      }
      .metered(lc.offsetSaveInterval)
      .compile
      .drain
}
