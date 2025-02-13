package ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch

import cats.effect.IO
import cats.effect.std.Env
import cats.syntax.all._
import ch.epfl.bluebrain.nexus.delta.kernel.Logger
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.model.defaultViewId
import ch.epfl.bluebrain.nexus.delta.sourcing.Transactors
import ch.epfl.bluebrain.nexus.delta.sourcing.implicits._
import doobie.syntax.all._

trait MigrateDefaultIndexing {
  def run: IO[Unit]
}

object MigrateDefaultIndexing {

  private val logger = Logger[MigrateDefaultIndexing]

  private def trigger = Env[IO].get("MIGRATE_DEFAULT_INDEXING").map(_.getOrElse("false").toBoolean)

  def apply(xas: Transactors): MigrateDefaultIndexing = new MigrateDefaultIndexing {

    private def deleteDefaultViews() =
      sql"""
          DELETE FROM scoped_events WHERE type = 'elasticsearch' AND id = $defaultViewId;
          DELETE FROM scoped_states WHERE type = 'elasticsearch' AND id = $defaultViewId;
          DELETE FROM projection_offsets WHERE module = 'elasticsearch' AND resource_id = $defaultViewId;
        """.stripMargin.update.run.void.transact(xas.write)

    override def run: IO[Unit] =
      trigger.flatMap { enabled =>
        IO.whenA(enabled) {
          logger.info("Deleting existing default elasticsearch views and their progress...") >>
            deleteDefaultViews() >>
            logger.info("Deleting existing default elasticsearch views is complete.")
        }
      }
  }

}
