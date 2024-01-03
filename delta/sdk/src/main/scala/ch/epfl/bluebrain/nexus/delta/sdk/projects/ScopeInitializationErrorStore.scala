package ch.epfl.bluebrain.nexus.delta.sdk.projects

import cats.effect.{Clock, IO}
import cats.implicits._
import ch.epfl.bluebrain.nexus.delta.sdk.error.ServiceError.ScopeInitializationFailed
import ch.epfl.bluebrain.nexus.delta.sourcing.Transactors
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{EntityType, ProjectRef}
import doobie.implicits._
import doobie.postgres.implicits._

trait ScopeInitializationErrorStore {

  /**
    * Save a scope initialization error
    * @param entityType
    *   type of the entity this error is for
    * @param project
    *   project for which the error occurred
    * @param e
    *   the error to save
    */
  def save(entityType: EntityType, project: ProjectRef, e: ScopeInitializationFailed): IO[Unit]

}

object ScopeInitializationErrorStore {

  def apply(xas: Transactors, clock: Clock[IO]): ScopeInitializationErrorStore =
    (entityType: EntityType, project: ProjectRef, e: ScopeInitializationFailed) => {
      clock.realTimeInstant.flatMap { instant =>
        sql"""
           |INSERT INTO scope_initialization_errors (type, org, project, message, instant)
           |VALUES ($entityType, ${project.organization}, ${project.project}, ${e.getMessage}, $instant)
           |""".stripMargin.update.run.void.transact(xas.write)
      }
    }

}
