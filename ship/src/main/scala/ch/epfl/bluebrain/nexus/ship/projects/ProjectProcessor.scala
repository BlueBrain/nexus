package ch.epfl.bluebrain.nexus.ship.projects

import cats.effect.IO
import ch.epfl.bluebrain.nexus.delta.kernel.Logger
import ch.epfl.bluebrain.nexus.delta.sdk.ScopeInitializer
import ch.epfl.bluebrain.nexus.delta.sdk.model.BaseUri
import ch.epfl.bluebrain.nexus.delta.sdk.organizations.FetchActiveOrganization
import ch.epfl.bluebrain.nexus.delta.sdk.projects.model.ProjectEvent._
import ch.epfl.bluebrain.nexus.delta.sdk.projects.model.ProjectRejection.NotFound
import ch.epfl.bluebrain.nexus.delta.sdk.projects.model.{ApiMappings, ProjectEvent, ProjectFields, ProjectRejection}
import ch.epfl.bluebrain.nexus.delta.sdk.projects.{Projects, ProjectsImpl, ValidateProjectDeletion}
import ch.epfl.bluebrain.nexus.delta.sourcing.Transactors
import ch.epfl.bluebrain.nexus.delta.sourcing.config.EventLogConfig
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.Subject
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{EntityType, ProjectRef}
import ch.epfl.bluebrain.nexus.ship.error.ShipError.ProjectDeletionIsNotAllowed
import ch.epfl.bluebrain.nexus.ship.projects.ProjectProcessor.logger
import ch.epfl.bluebrain.nexus.ship.{EventClock, EventProcessor, EventUUIDF, ImportStatus}
import io.circe.Decoder

final class ProjectProcessor private (projects: Projects, clock: EventClock, uuidF: EventUUIDF)
    extends EventProcessor[ProjectEvent] {
  override def resourceType: EntityType = Projects.entityType

  override def decoder: Decoder[ProjectEvent] = ProjectEvent.serializer.codec

  override def evaluate(event: ProjectEvent): IO[ImportStatus] = {
    for {
      _      <- clock.setInstant(event.instant)
      _      <- uuidF.setUUID(event.uuid)
      result <- evaluateInternal(event)
    } yield result
  }

  private def evaluateInternal(event: ProjectEvent): IO[ImportStatus] = {
    implicit val s: Subject = event.subject
    val projectRef          = event.project
    val cRev                = event.rev - 1
    event match {
      case ProjectCreated(_, _, _, _, _, description, apiMappings, base, vocab, enforceSchema, _, _) =>
        val fields = ProjectFields(description, apiMappings, Some(base), Some(vocab), enforceSchema)
        projects.create(projectRef, fields)
      case ProjectUpdated(_, _, _, _, _, description, apiMappings, base, vocab, enforceSchema, _, _) =>
        val fields = ProjectFields(description, apiMappings, Some(base), Some(vocab), enforceSchema)
        projects.update(projectRef, cRev, fields)
      case _: ProjectDeprecated                                                                      =>
        projects.deprecate(projectRef, cRev)
      case _: ProjectUndeprecated                                                                    =>
        projects.undeprecate(projectRef, cRev)
      case _: ProjectMarkedForDeletion                                                               =>
        IO.raiseError(ProjectDeletionIsNotAllowed(projectRef))
    }
  }.redeemWith(
    {
      case notFound: NotFound      => IO.raiseError(notFound)
      case error: ProjectRejection => logger.warn(error)(error.reason).as(ImportStatus.Dropped)
      case other                   => IO.raiseError(other)
    },
    _ => IO.pure(ImportStatus.Success)
  )
}

object ProjectProcessor {

  private val logger      = Logger[ProjectProcessor]
  def apply(fetchActiveOrg: FetchActiveOrganization, config: EventLogConfig, clock: EventClock, xas: Transactors)(
      implicit base: BaseUri
  ): IO[ProjectProcessor] =
    for {
      uuidF <- EventUUIDF.init()
    } yield {
      val disableDeletion: ValidateProjectDeletion = (p: ProjectRef) => IO.raiseError(ProjectDeletionIsNotAllowed(p))
      val projects                                 = ProjectsImpl(
        fetchActiveOrg,
        disableDeletion,
        ScopeInitializer.noop,
        ApiMappings.empty,
        config,
        xas,
        clock
      )(base, uuidF)
      new ProjectProcessor(projects, clock, uuidF)
    }
}
