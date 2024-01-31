package ch.epfl.bluebrain.nexus.delta.sdk.projects

import cats.effect.IO
import cats.syntax.all._
import ch.epfl.bluebrain.nexus.delta.sdk.ProjectResource
import ch.epfl.bluebrain.nexus.delta.sdk.organizations.Organizations
import ch.epfl.bluebrain.nexus.delta.sdk.projects.model.ProjectRejection._
import ch.epfl.bluebrain.nexus.delta.sdk.projects.model.{ApiMappings, ProjectContext, ProjectRejection}
import ch.epfl.bluebrain.nexus.delta.sdk.quotas.Quotas
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.Subject
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{Label, ProjectRef}

/**
  * Define the rules to fetch project context for read and write operations
  */
abstract class FetchContext { self =>

  /**
    * The default api mappings
    */
  def defaultApiMappings: ApiMappings

  /**
    * Fetch a context for a read operation
    * @param ref
    *   the project to fetch the context from
    */
  def onRead(ref: ProjectRef): IO[ProjectContext]

  /**
    * Fetch context for a create operation
    * @param ref
    *   the project to fetch the context from
    * @param subject
    *   the current user
    */
  def onCreate(ref: ProjectRef)(implicit subject: Subject): IO[ProjectContext]

  /**
    * Fetch context for a modify operation
    * @param ref
    *   the project to fetch the context from
    * @param subject
    *   the current user
    */
  def onModify(ref: ProjectRef)(implicit subject: Subject): IO[ProjectContext]

}

object FetchContext {

  /**
    * Create a fetch context instance from an [[Organizations]], [[Projects]] and [[Quotas]] instances
    */
  def apply(organizations: Organizations, projects: Projects, quotas: Quotas): FetchContext =
    apply(
      organizations.fetchActiveOrganization(_).void,
      projects.defaultApiMappings,
      projects.fetch,
      quotas
    )

  def apply(
      fetchActiveOrganization: Label => IO[Unit],
      dam: ApiMappings,
      fetchProject: ProjectRef => IO[ProjectResource],
      quotas: Quotas
  ): FetchContext =
    new FetchContext {

      override def defaultApiMappings: ApiMappings = dam

      override def onRead(ref: ProjectRef): IO[ProjectContext] =
        fetchProject(ref).attemptNarrow[ProjectRejection].flatMap {
          case Left(rejection)                                   => IO.raiseError(rejection)
          case Right(project) if project.value.markedForDeletion => IO.raiseError(ProjectIsMarkedForDeletion(ref))
          case Right(project)                                    => IO.pure(project.value.context)
        }

      private def onWrite(ref: ProjectRef) =
        fetchProject(ref).attemptNarrow[ProjectRejection].flatMap {
          case Left(rejection)                                   => IO.raiseError(rejection)
          case Right(project) if project.value.markedForDeletion => IO.raiseError(ProjectIsMarkedForDeletion(ref))
          case Right(project) if project.deprecated              => IO.raiseError(ProjectIsDeprecated(ref))
          case Right(project)                                    => IO.pure(project.value.context)
        }

      override def onCreate(ref: ProjectRef)(implicit subject: Subject): IO[ProjectContext] =
        quotas.reachedForResources(ref, subject) >> onModify(ref)

      override def onModify(ref: ProjectRef)(implicit subject: Subject): IO[ProjectContext] =
        for {
          _       <- fetchActiveOrganization(ref.organization)
          _       <- quotas.reachedForEvents(ref, subject)
          context <- onWrite(ref)
        } yield context
    }
}
