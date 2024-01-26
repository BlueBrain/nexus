package ch.epfl.bluebrain.nexus.delta.sourcing.exporter

import cats.effect.IO
import cats.effect.kernel.Clock
import cats.effect.std.Semaphore
import ch.epfl.bluebrain.nexus.delta.kernel.Logger
import ch.epfl.bluebrain.nexus.delta.sourcing.Transactors
import ch.epfl.bluebrain.nexus.delta.sourcing.exporter.Exporter.ExportResult
import ch.epfl.bluebrain.nexus.delta.sourcing.implicits._
import doobie.Fragments
import doobie.implicits._
import doobie.util.fragment.Fragment
import fs2.io.file.{Files, Path}

import java.time.Instant

trait Exporter {

  def events(query: ExportEventQuery): IO[ExportResult]

}

object Exporter {

  private val logger = Logger[Exporter]

  final case class ExportResult(json: Path, success: Path, start: Instant, end: Instant)

  def apply(config: ExportConfig, clock: Clock[IO], xas: Transactors): IO[Exporter] =
    Semaphore[IO](config.permits.toLong).map(new ExporterImpl(config.target, _, clock, xas))

  private class ExporterImpl(rootDirectory: Path, semaphore: Semaphore[IO], clock: Clock[IO], xas: Transactors)
      extends Exporter {
    override def events(query: ExportEventQuery): IO[ExportResult] = {
      val projectFilter = Fragments.orOpt(
        query.projects.map { project => sql"(org = ${project.organization} and project = ${project.project})" }
      )
      val q             = asJson(sql"""SELECT *
             |FROM public.scoped_events
             |${Fragments.whereAndOpt(projectFilter, query.offset.asFragment)}
             |ORDER BY ordering
             |""".stripMargin)

      val exportIO = for {
        start          <- clock.realTimeInstant
        _              <- logger.info(s"Starting export for projects ${query.projects} from offset ${query.offset}")
        targetDirectory = rootDirectory / query.id.value
        _              <- Files[IO].createDirectory(targetDirectory)
        exportFile      = targetDirectory / s"$start.json"
        _              <- exportToFile(q, exportFile)
        end            <- clock.realTimeInstant
        exportSuccess   = targetDirectory / s"$start.success"
        _               = println(exportFile)
        _              <- Files[IO].createFile(exportSuccess)
        _              <-
          logger.info(
            s"Export for projects ${query.projects} from offset' ${query.offset}' after ${end.getEpochSecond - start.getEpochSecond} seconds."
          )
      } yield ExportResult(exportFile, exportSuccess, start, end)

      semaphore.permit.use { _ => exportIO }
    }

    private def exportToFile(q: Fragment, targetFile: Path) =
      q.query[String]
        .stream
        .intersperse("\n")
        .transact(xas.streaming)
        .through(Files[IO].writeUtf8(targetFile))
        .compile
        .drain

    private def asJson(query: Fragment) =
      sql"""(select row_to_json(t) from ($query) t)"""
  }

}
