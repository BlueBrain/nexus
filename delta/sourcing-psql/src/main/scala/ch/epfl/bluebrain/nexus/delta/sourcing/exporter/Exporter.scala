package ch.epfl.bluebrain.nexus.delta.sourcing.exporter

import cats.effect.IO
import cats.effect.kernel.Clock
import cats.effect.std.Semaphore
import ch.epfl.bluebrain.nexus.delta.kernel.Logger
import ch.epfl.bluebrain.nexus.delta.sourcing.Transactors
import ch.epfl.bluebrain.nexus.delta.sourcing.config.QueryConfig
import ch.epfl.bluebrain.nexus.delta.sourcing.exporter.Exporter.ExportResult
import ch.epfl.bluebrain.nexus.delta.sourcing.implicits._
import ch.epfl.bluebrain.nexus.delta.sourcing.offset.Offset
import ch.epfl.bluebrain.nexus.delta.sourcing.query.{RefreshStrategy, StreamingQuery}
import doobie.Fragments
import doobie.implicits._
import doobie.util.query.Query0
import fs2.Stream
import fs2.io.file.{Files, Path}
import io.circe.syntax.EncoderOps

import java.time.Instant

trait Exporter {

  def events(query: ExportEventQuery): IO[ExportResult]

}

object Exporter {

  private val logger = Logger[Exporter]

  final case class ExportResult(json: Path, success: Path, start: Instant, end: Instant)

  def apply(config: ExportConfig, clock: Clock[IO], xas: Transactors): IO[Exporter] =
    Semaphore[IO](config.permits.toLong).map(new ExporterImpl(config, _, clock, xas))

  private class ExporterImpl(config: ExportConfig, semaphore: Semaphore[IO], clock: Clock[IO], xas: Transactors)
      extends Exporter {

    val queryConfig = QueryConfig(config.batchSize, RefreshStrategy.Stop)
    override def events(query: ExportEventQuery): IO[ExportResult] = {
      val projectFilter     = Fragments.orOpt(
        query.projects.map { project => sql"(org = ${project.organization} and project = ${project.project})" }
      )
      def q(offset: Offset) =
        sql"""SELECT ordering, type, org, project, id, rev, value, instant
             |FROM public.scoped_events
             |${Fragments.whereAndOpt(projectFilter, offset.asFragment)}
             |ORDER BY ordering
             |""".stripMargin.query[RowEvent]

      val exportIO = for {
        start          <- clock.realTimeInstant
        _              <- logger.info(s"Starting export for projects ${query.projects} from offset ${query.offset}")
        targetDirectory = config.target / query.output.value
        _              <- Files[IO].createDirectory(targetDirectory)
        exportFile      = targetDirectory / s"$start.json"
        _              <- exportToFile(q, query.offset, exportFile)
        end            <- clock.realTimeInstant
        exportSuccess   = targetDirectory / s"$start.success"
        _              <- writeSuccessFile(query, exportSuccess)
        _              <-
          logger.info(
            s"Export for projects ${query.projects} from offset' ${query.offset}' after ${end.getEpochSecond - start.getEpochSecond} seconds."
          )
      } yield ExportResult(exportFile, exportSuccess, start, end)

      semaphore.permit.use { _ => exportIO }
    }

    private def exportToFile(query: Offset => Query0[RowEvent], start: Offset, targetFile: Path) = {
      StreamingQuery[RowEvent](start, query, _.ordering, queryConfig, xas)
        .map(_.asJson.noSpaces)
        .intersperse("\n")
        .through(Files[IO].writeUtf8(targetFile))
        .compile
        .drain
    }

    private def writeSuccessFile(query: ExportEventQuery, targetFile: Path) =
      Stream(query.asJson.toString()).through(Files[IO].writeUtf8(targetFile)).compile.drain
  }

}
