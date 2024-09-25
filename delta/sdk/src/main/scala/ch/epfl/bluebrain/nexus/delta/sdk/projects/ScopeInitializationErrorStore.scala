package ch.epfl.bluebrain.nexus.delta.sdk.projects

import cats.effect.{Clock, IO}
import cats.implicits._
import ch.epfl.bluebrain.nexus.delta.kernel.Logger
import ch.epfl.bluebrain.nexus.delta.sdk.error.ServiceError.ScopeInitializationFailed
import ch.epfl.bluebrain.nexus.delta.sdk.projects.ScopeInitializationErrorStore.ScopeInitErrorRow
import ch.epfl.bluebrain.nexus.delta.sourcing.Transactors
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{EntityType, Label, ProjectRef}
import doobie.syntax.all._
import doobie.generic.auto._
import doobie.postgres.implicits._

import java.time.Instant

/**
  * Persistent operations for errors raised by scope initialization
  */
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

  /**
    * Fetch all scope initialization errors
    */
  def fetch: IO[List[ScopeInitErrorRow]]

  /**
    * Delete all scope initialization errors for the provided project
    */
  def delete(project: ProjectRef): IO[Unit]
}

object ScopeInitializationErrorStore {

  private val logger = Logger[ScopeInitializationErrorStore]

  def apply(xas: Transactors, clock: Clock[IO]): ScopeInitializationErrorStore = {
    new ScopeInitializationErrorStore {
      override def save(entityType: EntityType, project: ProjectRef, e: ScopeInitializationFailed): IO[Unit] =
        clock.realTimeInstant
          .flatMap { instant =>
            sql"""
                 |INSERT INTO scope_initialization_errors (type, org, project, message, instant)
                 |VALUES ($entityType, ${project.organization}, ${project.project}, ${e.getMessage}, $instant)
                 |""".stripMargin.update.run.void.transact(xas.write)
          }
          .onError { e =>
            logger.error(e)(s"Failed to save error for '$entityType' initialization step on project '$project'")
          }

      override def fetch: IO[List[ScopeInitErrorRow]] =
        sql"""SELECT ordering, type, org, project, message, instant FROM scope_initialization_errors"""
          .query[ScopeInitErrorRow]
          .to[List]
          .transact(xas.read)

      override def delete(project: ProjectRef): IO[Unit] =
        sql"""DELETE FROM scope_initialization_errors WHERE project = ${project.project} AND org = ${project.organization}""".update.run.void
          .transact(xas.write)
    }
  }

  case class ScopeInitErrorRow(
      ordering: Int,
      entityType: EntityType,
      org: Label,
      project: Label,
      message: String,
      instant: Instant
  )

  /** A no-op error store that does not store anything */
  def noopStore: ScopeInitializationErrorStore = new ScopeInitializationErrorStore {
    override def save(entityType: EntityType, project: ProjectRef, e: ScopeInitializationFailed): IO[Unit] = IO.unit
    override def fetch: IO[List[ScopeInitErrorRow]]                                                        = IO.pure(List.empty)
    override def delete(project: ProjectRef): IO[Unit]                                                     = IO.unit
  }

}
