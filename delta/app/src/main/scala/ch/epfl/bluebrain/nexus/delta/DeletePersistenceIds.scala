package ch.epfl.bluebrain.nexus.delta

import akka.actor.ActorSystem
import akka.persistence.cassandra.cleanup.Cleanup
import com.typesafe.scalalogging.Logger
import monix.bio.Task
import monix.execution.Scheduler

/**
  * Helper to delete a list of persistence ids at start up
  */
object DeletePersistenceIds {

  private val logger = Logger[DeletePersistenceIds.type]

  /**
    * Delete the given persistence ids
    * @param persistenceIds the persistence ids to delete
    */
  def delete(persistenceIds: Seq[String])(implicit as: ActorSystem, sc: Scheduler): Unit = {
    logger.info(s"Deleting persistence ids ${persistenceIds.mkString(",")}...")
    Task
      .deferFuture(new Cleanup(as).deleteAll(persistenceIds, neverUsePersistenceIdAgain = false))
      .tapEval { _ =>
        Task.pure(logger.info(s"The persistence ids ${persistenceIds.mkString(",")} have been successfully deleted"))
      }
      .tapError { e =>
        Task.pure(logger.error(s"The persistence ids ${persistenceIds.mkString(",")} could not be deleted", e))
      }
      .void
      .runSyncUnsafe()
  }

}
