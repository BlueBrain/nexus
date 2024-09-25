package ch.epfl.bluebrain.nexus.ship

import cats.effect.IO
import ch.epfl.bluebrain.nexus.delta.sourcing.postgres.Doobie
import ch.epfl.bluebrain.nexus.ship.ShipCommand.RunCommand
import ch.epfl.bluebrain.nexus.testkit.mu.NexusSuite
import doobie.syntax.all._
import doobie.postgres.implicits._
import munit.AnyFixture
import ch.epfl.bluebrain.nexus.delta.sourcing.offset.Offset
import ch.epfl.bluebrain.nexus.ship.ShipSummaryStoreSuite.ReportRow
import fs2.io.file.Path

import java.time.Instant

class ShipSummaryStoreSuite extends NexusSuite with Doobie.Fixture {

  override def munitFixtures: Seq[AnyFixture[_]] = List(doobie)

  private lazy val xas = doobie()

  private def readLast: IO[ReportRow] =
    sql"""SELECT started_at, ended_at, success, error is NOT NULL, report is NOT NULL
                                             |FROM public.ship_runs
                                             |ORDER by ordering DESC
                                             |LIMIT 1
                                             |""".stripMargin
      .query[(Instant, Instant, Boolean, Boolean, Boolean)]
      .unique
      .transact(xas.read)
      .map { case (start, end, success, hasError, hasReport) =>
        ReportRow(start, end, success, hasError, hasReport)
      }

  private def assertSaveRun(
      start: Instant,
      end: Instant,
      command: RunCommand,
      reportOrError: Either[Throwable, ImportReport]
  ) = {
    for {
      _   <- ShipSummaryStore.save(xas, start, end, command, reportOrError)
      row <- readLast
    } yield {
      assertEquals(row.start, start)
      assertEquals(row.end, end)
      assertEquals(row.success, reportOrError.isRight)
      assertEquals(row.hasError, reportOrError.isLeft)
      assertEquals(row.hasReport, reportOrError.isRight)
    }
  }

  private val start      = Instant.parse("2024-04-17T10:00:00.000Z")
  private val end        = Instant.parse("2024-04-17T11:00:00.000Z")
  private val runCommand = RunCommand(Path("/data"), None, Offset.start, RunMode.Local)

  test("Save a failed run") {
    val error = new IllegalStateException("BOOM !")
    assertSaveRun(start, end, runCommand, Left(error))
  }

  test("Save a successful run") {
    val report = ImportReport(Offset.at(5L), Instant.now(), Map.empty)
    assertSaveRun(start, end, runCommand, Right(report))
  }

}

object ShipSummaryStoreSuite {

  final case class ReportRow(start: Instant, end: Instant, success: Boolean, hasError: Boolean, hasReport: Boolean)

}
