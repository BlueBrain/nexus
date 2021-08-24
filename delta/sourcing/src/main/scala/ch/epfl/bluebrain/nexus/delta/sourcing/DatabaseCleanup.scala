package ch.epfl.bluebrain.nexus.delta.sourcing

import akka.actor.typed.ActorSystem
import akka.persistence.cassandra.cleanup.Cleanup
import ch.epfl.bluebrain.nexus.delta.sourcing.config.DatabaseConfig
import ch.epfl.bluebrain.nexus.delta.sourcing.config.DatabaseFlavour.{Cassandra, Postgres}
import doobie.implicits._
import doobie.util.transactor.Transactor
import monix.bio.Task

sealed trait DatabaseCleanup {

  /**
    * Deletes all the rows filtered by the persistence
    *
    * @param moduleType the first segment of the persistence id
    * @param project    the second segment of the persistence id
    * @param ids        the last segment of the persistence id
    */
  def deleteAll(moduleType: String, project: String, ids: Seq[String]): Task[Unit]

  /**
    * Deletes all the rows filtered by the persistence
    *
    * @see [[deleteAll(moduleType, project, ids)]]
    */
  def deleteAll(moduleType: String, project: String): Task[Unit] =
    deleteAll(moduleType, project, Seq.empty)
}

object DatabaseCleanup {

  def apply(config: DatabaseConfig)(implicit system: ActorSystem[Nothing]): DatabaseCleanup =
    config.flavour match {
      case Postgres  => postgres(config.postgres.transactor)
      case Cassandra => cassandra(new Cleanup(system.classicSystem))
    }

  private[sourcing] def postgres(xa: Transactor[Task]): DatabaseCleanup =
    new DatabaseCleanup {

      override def deleteAll(moduleType: String, project: String, ids: Seq[String]): Task[Unit] =
        sql"""DELETE FROM public.event_journal WHERE persistence_id LIKE $moduleType || '-' || $project || '%'""".update.run
          .transact(xa)
          .void
    }

  private[sourcing] def cassandra(cleanup: Cleanup): DatabaseCleanup =
    new DatabaseCleanup {
      private def persistenceId(moduleType: String, project: String, ids: Seq[String]): Seq[String] =
        if (ids.isEmpty) List(s"${moduleType}-${project}")
        else ids.map(id => s"${moduleType}-${project}_${id}")

      override def deleteAll(moduleType: String, project: String, ids: Seq[String]): Task[Unit] =
        Task
          .deferFuture(cleanup.deleteAll(persistenceId(moduleType, project, ids), neverUsePersistenceIdAgain = false))
          .void
    }
}
