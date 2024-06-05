package ch.epfl.bluebrain.nexus.delta.sdk.projects

import cats.effect.IO
import ch.epfl.bluebrain.nexus.delta.sdk.ProjectResource
import ch.epfl.bluebrain.nexus.delta.sdk.organizations.FetchActiveOrganization
import ch.epfl.bluebrain.nexus.delta.sdk.projects.model.ProjectRejection._
import ch.epfl.bluebrain.nexus.delta.sdk.projects.model.{ApiMappings, ProjectContext, ProjectState}
import ch.epfl.bluebrain.nexus.delta.sdk.quotas.Quotas
import ch.epfl.bluebrain.nexus.delta.sourcing.Transactors
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.Subject
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{Label, ProjectRef}
import ch.epfl.bluebrain.nexus.delta.sourcing.state.ScopedStateGet
import doobie.implicits._
import doobie.{Get, Put}

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

  def apply(dam: ApiMappings, xas: Transactors, quotas: Quotas): FetchContext = {
    def fetchProject(ref: ProjectRef, onWrite: Boolean) = {
      implicit val putId: Put[ProjectRef]      = ProjectState.serializer.putId
      implicit val getValue: Get[ProjectState] = ProjectState.serializer.getValue
      val xa                                   = if (onWrite) xas.write else xas.read
      ScopedStateGet
        .latest[ProjectRef, ProjectState](Projects.entityType, ref, ref)
        .transact(xa)
        .map(_.map(_.toResource(dam)))
    }

    val fetchActiveOrg: Label => IO[Unit] = FetchActiveOrganization(xas).apply(_).void
    apply(fetchActiveOrg, dam, fetchProject, quotas)
  }

  /**
    * Constructs a fetch context
    * @param fetchActiveOrg
    *   fetches the org and makes sure it exists and is not deprecated
    * @param dam
    *   the default api mappings defined by Nexus
    * @param fetchProject
    *   fetches the project in read / write context. The write context is more consistent as it points to the primary
    *   node while the read one can point to replicas and can suffer from replication delays
    * @param quotas
    *   the quotes
    */
  def apply(
      fetchActiveOrg: Label => IO[Unit],
      dam: ApiMappings,
      fetchProject: (ProjectRef, Boolean) => IO[Option[ProjectResource]],
      quotas: Quotas
  ): FetchContext =
    new FetchContext {

      override def defaultApiMappings: ApiMappings = dam

      override def onRead(ref: ProjectRef): IO[ProjectContext] =
        fetchProject(ref, false).flatMap {
          case None                                             => IO.raiseError(ProjectNotFound(ref))
          case Some(project) if project.value.markedForDeletion => IO.raiseError(ProjectIsMarkedForDeletion(ref))
          case Some(project)                                    => IO.pure(project.value.context)
        }

      private def onWrite(ref: ProjectRef) =
        fetchProject(ref, true).flatMap {
          case None                                             => IO.raiseError(ProjectNotFound(ref))
          case Some(project) if project.value.markedForDeletion => IO.raiseError(ProjectIsMarkedForDeletion(ref))
          case Some(project) if project.deprecated              => IO.raiseError(ProjectIsDeprecated(ref))
          case Some(project)                                    => IO.pure(project.value.context)
        }

      override def onCreate(ref: ProjectRef)(implicit subject: Subject): IO[ProjectContext] =
        quotas.reachedForResources(ref, subject) >> onModify(ref)

      override def onModify(ref: ProjectRef)(implicit subject: Subject): IO[ProjectContext] =
        for {
          _       <- fetchActiveOrg(ref.organization)
          _       <- quotas.reachedForEvents(ref, subject)
          context <- onWrite(ref)
        } yield context
    }
}
