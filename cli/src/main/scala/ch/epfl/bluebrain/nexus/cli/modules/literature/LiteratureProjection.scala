package ch.epfl.bluebrain.nexus.cli.modules.literature

import java.text.BreakIterator
import java.util.Locale
import java.util.regex.Pattern.quote

import _root_.io.circe.syntax._
import _root_.io.circe.{Json, JsonObject}
import cats.data.EitherT
import cats.effect.{Blocker, ConcurrentEffect, ContextShift, Timer}
import cats.implicits._
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
import ch.epfl.bluebrain.nexus.cli.{logRetryErrors, CliErrOffsetOr, CliErrOr, CliError, Console}
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

  private type ComputedVectors =
    List[Either[(Offset, CliError), (Event, Offset, ProjectLabel, String, String, Embedding, String)]]
  private val abstractKey = "abstract"
  private val bodyKey     = "articleBody"

  private val lc: LiteratureConfig = cfg.literature

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
          case Right((ev, off, org, proj)) =>
            val existsType = lc.projects.get((org, proj)).exists(pc => ev.resourceTypes.exists(pc.types.contains))
            Right(Option.when(existsType)((ev, off, org, proj)))
          case Left(err)                   => Left(err)
        }
        .through(printEventProgress(console, lc.errorFile))
        .flatMap {
          case (ev, off, _, proj) =>
            (for {
              abstractSection <- extractSectionSentences(abstractKey, ev.raw)
              bodySection     <- extractSectionSentences(bodyKey, ev.raw)
            } yield (abstractSection ++ bodySection).flatMap {
              case (section, sentences) => sentences.map(text => (ev, off, proj, section, text))
            }) match {
              case Some(sentences) => Stream(sentences.toSeq.map(Right.apply): _*)
              case None => Stream(Left((ev, off, proj, textExtractionErr(ev.resourceId))))
            }
        }
        .evalMap[F, ComputedVectors] {
          case Right((ev, off, proj, section, text)) =>
            lc.blueBrainSearch.modelTypes.map {
              case ModelType(modelName, _) =>
                bbs
                  .embedding(modelName, text)
                  .map(_.map(emb => (ev, off, proj, section, text, emb, modelName)))
                  .map(_.leftMap(off -> _))
            }.sequence
          case Left((ev, off, proj, err))            =>
            console
              .printlnErr(
                s"Computing embedding for paper '${ev.resourceId}' with offset '$off' on project '${proj.show}' failed"
              )
              .as(List(Left(off -> err)))
        }
        .flatMap(list => Stream(list: _*))
        .through(printProjectionProgress(console, lc.errorFile, sentenceEmbeddingsProcessLine))
        .groupAdjacentBy { case (ev, off, proj, _, _, _, _) => (ev, off, proj) } {
          case ((_, off1, _), (_, off2, _)) => off1 == off2
        }
        .evalMap {
          case ((ev, off, proj), chunks) =>
            val sentences = chunks.map {
              case (_, _, _, section, text, Embedding(vector), modelName) => (section, text, vector, modelName)
            }.toList
            writeJsonPaper(ev, off, proj, sentences)
        }
        .through(printProjectionProgress(console, lc.errorFile, paperProcessLine))
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

  private def splitIntoWords(sentence: String, maxWords: Int): Seq[String] = {
    val iterator: BreakIterator = BreakIterator.getWordInstance(Locale.US)
    iterator.setText(sentence)

    @tailrec
    def inner(
               start: Int,
               end: Int,
               currentSentenceOpt: Option[String] = None,
               currentWordsCount: Int = 0,
               sentences: Seq[String] = Vector.empty
             ): Seq[String] = {
      if (end == BreakIterator.DONE) {
        currentSentenceOpt.fold(sentences)(sentences :+ _)
      } else {
        val currentWord     = sentence.substring(start, end)
        val currentSentence = currentSentenceOpt.fold(currentWord)(c => s"$c$currentWord")
        val currentCount    = if (currentWord == " ") currentWordsCount else currentWordsCount + 1
        if (currentCount < maxWords) inner(end, iterator.next, Some(currentSentence), currentCount, sentences)
        else inner(end, iterator.next, sentences = sentences :+ currentSentence)
      }
    }

    inner(iterator.first, iterator.next)
  }

  private def splitIntoSentences(text: String): Seq[String] = {

    val iterator: BreakIterator = BreakIterator.getSentenceInstance(Locale.US)
    iterator.setText(text)

    @tailrec
    def inner(start: Int, end: Int, acc: Seq[String] = Vector.empty): Seq[String] =
      if (end == BreakIterator.DONE) acc
      else inner(end, iterator.next, acc ++ splitIntoWords(text.substring(start, end), lc.blueBrainSearch.maxWords))

    inner(iterator.first, iterator.next)
  }

  private def writeJsonPaper(
      ev: Event,
      off: Offset,
      proj: ProjectLabel,
      sentences: Seq[(String, String, Seq[Double], String)]
  ): F[CliErrOffsetOr[Unit]] = {
    ev.raw.hcursor.get[JsonObject]("_source") match {
      case Left(_)          => F.pure(Left(off -> sourceExtractionErr(ev.resourceId)))
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
              EitherT(es.index(paperIndex(modelName), id, document).map[CliErrOr[Unit]](identity))
                .semiflatMap { _ =>
                  console.println(s"Indexed paper with id '$id' for model '$modelName'")
                }
                .leftMap(off -> _)
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
