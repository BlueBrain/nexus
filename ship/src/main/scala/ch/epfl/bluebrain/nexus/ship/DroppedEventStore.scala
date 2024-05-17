package ch.epfl.bluebrain.nexus.ship

import cats.effect.IO
import ch.epfl.bluebrain.nexus.delta.sourcing.Transactors
import ch.epfl.bluebrain.nexus.delta.sourcing.exporter.RowEvent
import ch.epfl.bluebrain.nexus.delta.sourcing.implicits._
import doobie.postgres.implicits._
import doobie.implicits._

final class DroppedEventStore(xas: Transactors) {

  def count: IO[Long] =
    sql"""SELECT COUNT(*) FROM ship_dropped_events""".stripMargin.query[Long].unique.transact(xas.read)

  def truncate =
    sql"""TRUNCATE ship_dropped_events""".update.run.transact(xas.write).void

  def save(rowEvent: RowEvent) =
    sql"""
         | INSERT INTO ship_dropped_events (
         |  ordering,
         |  type,
         |  org,
         |  project,
         |  id,
         |  rev,
         |  value,
         |  instant
         | )
         | VALUES (
         |  ${rowEvent.ordering},
         |  ${rowEvent.`type`},
         |  ${rowEvent.org},
         |  ${rowEvent.project},
         |  ${rowEvent.id},
         |  ${rowEvent.rev},
         |  ${rowEvent.value},
         |  ${rowEvent.instant}
         | )""".stripMargin.update.run.transact(xas.write).void

}
