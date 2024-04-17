package ch.epfl.bluebrain.nexus.ship

import cats.effect.IO
import ch.epfl.bluebrain.nexus.delta.kernel.utils.ThrowableUtils
import ch.epfl.bluebrain.nexus.delta.sourcing.Transactors
import ch.epfl.bluebrain.nexus.ship.ShipCommand.RunCommand
import doobie.implicits._
import doobie.postgres.implicits._
import ch.epfl.bluebrain.nexus.delta.sourcing.implicits._
import io.circe.syntax.EncoderOps

import java.time.Instant

object ShipSummaryStore {

  def save(
      xas: Transactors,
      start: Instant,
      end: Instant,
      command: RunCommand,
      reportOrError: Either[Throwable, ImportReport]
  ): IO[Unit] = {
    val success = reportOrError.isRight
    val insert  = reportOrError match {
      case Left(error)   => {
        val errorMessage = ThrowableUtils.stackTraceAsString(error)
        sql""" INSERT INTO ship_runs(started_at, ended_at, command, success, error) VALUES($start, $end, ${command.asJson}, $success, $errorMessage)"""
      }
      case Right(report) =>
        sql""" INSERT INTO ship_runs(started_at, ended_at, command , success, report) VALUES($start, $end, ${command.asJson}, $success, ${report.asJson})"""
    }
    insert.update.run.transact(xas.write).void
  }
}
