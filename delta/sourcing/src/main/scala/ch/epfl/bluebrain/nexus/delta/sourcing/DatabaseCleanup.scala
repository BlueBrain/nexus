package ch.epfl.bluebrain.nexus.delta.sourcing

import akka.actor.typed.ActorSystem
import akka.persistence.cassandra.cleanup.Cleanup
import ch.epfl.bluebrain.nexus.delta.kernel.utils.UrlUtils
import ch.epfl.bluebrain.nexus.delta.sourcing.config.DatabaseConfigOld
import ch.epfl.bluebrain.nexus.delta.sourcing.config.DatabaseFlavour.{Cassandra, Postgres}
import ch.epfl.bluebrain.nexus.delta.sourcing.processor.EventSourceProcessor
import com.typesafe.scalalogging.Logger
import doobie.implicits._
import monix.bio.Task

sealed trait DatabaseCleanup {

  /**
    * Deletes all the rows filtered by the persistence
    *
    * @param moduleType
    *   the first segment of the persistence id
    * @param project
    *   the second segment of the persistence id
    * @param ids
    *   the last segment of the persistence id
    */
  def deleteAll(moduleType: String, project: String, ids: Seq[String]): Task[Unit]

  /**
    * Deletes all the rows filtered by the persistence
    *
    * @see
    *   [[deleteAll(moduleType, project, ids)]]
    */
  def deleteAll(moduleType: String, project: String): Task[Unit] =
    deleteAll(moduleType, project, Seq.empty)
}

object DatabaseCleanup {

  private val logger: Logger = Logger[DatabaseCleanup.type]

  def apply(config: DatabaseConfigOld)(implicit system: ActorSystem[Nothing]): DatabaseCleanup =
    config.flavour match {
      case Postgres  => postgres(config)
      case Cassandra => cassandra(new Cleanup(system.classicSystem))
    }

  private[sourcing] def postgres(config: DatabaseConfigOld): DatabaseCleanup =
    new DatabaseCleanup {

      override def deleteAll(moduleType: String, project: String, ids: Seq[String]): Task[Unit] = {
        if (config.denyCleanup)
          Task.delay(
            logger.warn(
              s"Cleanup is disabled, no actual deletion will be performed for module '$moduleType' in project '$project'."
            )
          )
        else {
          Task.delay(
            logger.info(s"Cleaning events from module '$moduleType' for project '$project'.")
          ) >>
            sql"""DELETE FROM public.event_journal WHERE persistence_id LIKE $moduleType || '-' || ${UrlUtils.encode(
              project
            )} || '%'""".update.run
              .transact(config.postgres.transactor)
              .void
        }
      }
    }

  private[sourcing] def cassandra(cleanup: Cleanup): DatabaseCleanup =
    new DatabaseCleanup {
      override def deleteAll(moduleType: String, project: String, ids: Seq[String]): Task[Unit] = {
        val persistenceIds =
          if (ids.isEmpty) List(EventSourceProcessor.persistenceId(moduleType, project))
          else
            ids.map { id =>
              EventSourceProcessor.persistenceId(moduleType, s"${project}_$id")
            }
        Task.delay(
          logger.info(
            s"Cleaning events from module '$moduleType' for project '$project' with persistence ids: ${persistenceIds.mkString(",")}."
          )
        ) >>
          Task
            .deferFuture(
              cleanup.deleteAll(persistenceIds, neverUsePersistenceIdAgain = true)
            )
            .void
      }
    }
}
