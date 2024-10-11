package ch.epfl.bluebrain.nexus.delta.sourcing.exporter

import cats.effect.IO
import cats.effect.std.Semaphore
import ch.epfl.bluebrain.nexus.delta.kernel.Logger
import ch.epfl.bluebrain.nexus.delta.sourcing.Transactors
import ch.epfl.bluebrain.nexus.delta.sourcing.exporter.Exporter.ExportResult
import ch.epfl.bluebrain.nexus.delta.sourcing.implicits._
import ch.epfl.bluebrain.nexus.delta.sourcing.offset.Offset
import ch.epfl.bluebrain.nexus.delta.sourcing.query.{RefreshStrategy, StreamingQuery}
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.utils.StreamingUtils
import doobie.Fragments
import doobie.syntax.all._
import doobie.util.query.Query0
import fs2.Stream
import fs2.io.file._
import io.circe.syntax.EncoderOps

trait Exporter {

  def events(query: ExportEventQuery): IO[ExportResult]

}

object Exporter {

  private val logger = Logger[Exporter]

  private val fileFormat = "%012d"

  final case class ExportResult(targetDirectory: Path, success: Path)

  def apply(config: ExportConfig, xas: Transactors): IO[Exporter] =
    Semaphore[IO](config.permits.toLong).map(new ExporterImpl(config, _, xas))

  private class ExporterImpl(config: ExportConfig, semaphore: Semaphore[IO], xas: Transactors) extends Exporter {

    override def events(query: ExportEventQuery): IO[ExportResult] = {
      val projectFilter     = Fragments.orOpt(
        query.projects.map { project => sql"(org = ${project.organization} and project = ${project.project})" }
      )
      def q(offset: Offset) =
        sql"""SELECT ordering, type, org, project, id, rev, value, instant
             |FROM public.scoped_events
             |${Fragments.whereAndOpt(projectFilter, offset.asFragment)}
             |ORDER BY ordering
             |LIMIT ${config.batchSize}
             |""".stripMargin.query[RowEvent]

      val exportIO = for {
        _              <- logger.info(s"Starting export for projects ${query.projects} from offset ${query.offset}")
        targetDirectory = config.target / query.output.value
        _              <- Files[IO].createDirectory(targetDirectory)
        exportDuration <- exportToFile(q, query.offset, targetDirectory)
        exportSuccess   = targetDirectory / s"${paddedOffset(query.offset)}.success"
        _              <- writeSuccessFile(query, exportSuccess)
        _              <-
          logger.info(
            s"Export for projects ${query.projects} from offset' ${query.offset.value}' after ${exportDuration.toSeconds} seconds."
          )
      } yield ExportResult(targetDirectory, exportSuccess)

      semaphore.permit.use { _ => exportIO }
    }

    private def exportToFile(query: Offset => Query0[RowEvent], start: Offset, targetDirectory: Path) =
      Stream
        .eval(IO.ref(start))
        .flatMap { offsetRef =>
          def computePath = offsetRef.get.map { o =>
            targetDirectory / s"${paddedOffset(o)}.json"
          }

          StreamingQuery[RowEvent](start, query, _.ordering, RefreshStrategy.Stop, xas)
            .evalTap { rowEvent => offsetRef.set(rowEvent.ordering) }
            .map(_.asJson.noSpaces)
            .through(StreamingUtils.writeRotate(computePath, config.limitPerFile))
        }
        .compile
        .drain
        .timed
        .map(_._1)

    private def writeSuccessFile(query: ExportEventQuery, targetFile: Path) =
      Stream(query.asJson.toString()).through(Files[IO].writeUtf8(targetFile)).compile.drain

    private def paddedOffset(offset: Offset) = fileFormat.format(offset.value)
  }

}
