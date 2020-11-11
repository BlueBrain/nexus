package ch.epfl.bluebrain.nexus.delta.sdk.testkit

import akka.persistence.query.Offset
import cats.effect.Clock
import cats.syntax.all._
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.RemoteContextResolution
import ch.epfl.bluebrain.nexus.delta.sdk.Resources.{moduleType, sourceAsJsonLD}
import ch.epfl.bluebrain.nexus.delta.sdk._
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Identity.Subject
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.{ApiMappings, ProjectRef}
import ch.epfl.bluebrain.nexus.delta.sdk.model.resources.ResourceCommand._
import ch.epfl.bluebrain.nexus.delta.sdk.model.resources.ResourceRejection._
import ch.epfl.bluebrain.nexus.delta.sdk.model.resources.ResourceState.Initial
import ch.epfl.bluebrain.nexus.delta.sdk.model.resources.{ResourceCommand, ResourceEvent, ResourceRejection}
import ch.epfl.bluebrain.nexus.delta.sdk.model.{Envelope, Label, ResourceRef}
import ch.epfl.bluebrain.nexus.delta.sdk.testkit.ResourcesDummy.ResourcesJournal
import ch.epfl.bluebrain.nexus.delta.sdk.utils.UUIDF
import ch.epfl.bluebrain.nexus.testkit.IOSemaphore
import io.circe.Json
import monix.bio.{IO, Task, UIO}

/**
  * A dummy Resources implementation
  *
  * @param journal     the journal to store events
  * @param projects    the projects operations bundle
  * @param fetchSchema a function to retrieve the schema based on the schema iri
  * @param semaphore   a semaphore for serializing write operations on the journal
  */
final class ResourcesDummy private (
    journal: ResourcesJournal,
    projects: Projects,
    fetchSchema: ResourceRef => UIO[Option[SchemaResource]],
    semaphore: IOSemaphore
)(implicit clock: Clock[UIO], uuidF: UUIDF, rcr: RemoteContextResolution)
    extends Resources {

  override def create(
      projectRef: ProjectRef,
      schema: ResourceRef,
      source: Json
  )(implicit caller: Subject): IO[ResourceRejection, DataResource] =
    for {
      project                  <- projects.fetchActiveProject(projectRef).leftMap(WrappedProjectRejection)
      jsonld                   <- sourceAsJsonLD(project, source)
      (id, compacted, expanded) = jsonld
      res                      <- eval(CreateResource(id, projectRef, schema, source, compacted, expanded, caller), project.apiMappings)
    } yield res

  override def create(
      id: Iri,
      projectRef: ProjectRef,
      schema: ResourceRef,
      source: Json
  )(implicit caller: Subject): IO[ResourceRejection, DataResource] =
    for {
      jsonld               <- sourceAsJsonLD(id, source)
      (compacted, expanded) = jsonld
      project              <- projects.fetchActiveProject(projectRef).leftMap(WrappedProjectRejection)
      res                  <- eval(CreateResource(id, projectRef, schema, source, compacted, expanded, caller), project.apiMappings)
    } yield res

  override def update(
      id: Iri,
      projectRef: ProjectRef,
      schemaOpt: Option[ResourceRef],
      rev: Long,
      source: Json
  )(implicit caller: Subject): IO[ResourceRejection, DataResource] =
    for {
      jsonld               <- sourceAsJsonLD(id, source)
      (compacted, expanded) = jsonld
      project              <- projects.fetchActiveProject(projectRef).leftMap(WrappedProjectRejection)
      mapping               = project.apiMappings
      res                  <- eval(UpdateResource(id, projectRef, schemaOpt, source, compacted, expanded, rev, caller), mapping)
    } yield res

  override def tag(
      id: Iri,
      projectRef: ProjectRef,
      schemaOpt: Option[ResourceRef],
      tag: Label,
      tagRev: Long,
      rev: Long
  )(implicit caller: Subject): IO[ResourceRejection, DataResource] =
    for {
      project <- projects.fetchActiveProject(projectRef).leftMap(WrappedProjectRejection)
      res     <- eval(TagResource(id, projectRef, schemaOpt, tagRev, tag, rev, caller), project.apiMappings)
    } yield res

  override def deprecate(
      id: Iri,
      projectRef: ProjectRef,
      schemaOpt: Option[ResourceRef],
      rev: Long
  )(implicit caller: Subject): IO[ResourceRejection, DataResource] =
    for {
      project <- projects.fetchActiveProject(projectRef).leftMap(WrappedProjectRejection)
      res     <- eval(DeprecateResource(id, projectRef, schemaOpt, rev, caller), project.apiMappings)
    } yield res

  override def fetch(
      id: Iri,
      projectRef: ProjectRef,
      schemaOpt: Option[ResourceRef]
  ): IO[ResourceRejection, Option[DataResource]] =
    for {
      project  <- projects.fetchProject(projectRef).leftMap(WrappedProjectRejection)
      stateOpt <- journal.currentState((projectRef, id), Initial, Resources.next)
      resource  = stateOpt.flatMap(_.toResource(project.apiMappings))
    } yield validateSameSchema(resource, schemaOpt)

  override def fetchAt(
      id: Iri,
      projectRef: ProjectRef,
      schemaOpt: Option[ResourceRef],
      rev: Long
  ): IO[ResourceRejection, Option[DataResource]] =
    for {
      project  <- projects.fetchProject(projectRef).leftMap(WrappedProjectRejection)
      stateOpt <- journal.stateAt((projectRef, id), rev, Initial, Resources.next, RevisionNotFound.apply)
      resource  = stateOpt.flatMap(_.toResource(project.apiMappings))
    } yield validateSameSchema(resource, schemaOpt)

  private def eval(cmd: ResourceCommand, am: ApiMappings): IO[ResourceRejection, DataResource] =
    semaphore.withPermit {
      for {
        state <- journal.currentState((cmd.project, cmd.id), Initial, Resources.next).map(_.getOrElse(Initial))
        event <- Resources.evaluate(fetchSchema)(state, cmd)
        _     <- journal.add(event)
        res   <- IO.fromEither(Resources.next(state, event).toResource(am).toRight(UnexpectedInitialState(cmd.id)))
      } yield res
    }

  override def events(offset: Offset): fs2.Stream[Task, Envelope[ResourceEvent]] =
    journal.events(offset)

  override def currentEvents(offset: Offset): fs2.Stream[Task, Envelope[ResourceEvent]] =
    journal.currentEvents(offset)

  private def validateSameSchema(
      resourceOpt: Option[DataResource],
      schemaOpt: Option[ResourceRef]
  ): Option[DataResource] =
    resourceOpt match {
      case Some(value) if schemaOpt.forall(_ == value.schema) => Some(value)
      case _                                                  => None
    }
}

object ResourcesDummy {

  type ResourceIdentifier = (ProjectRef, Iri)

  type ResourcesJournal = Journal[ResourceIdentifier, ResourceEvent]

  implicit private val eventLens: Lens[ResourceEvent, ResourceIdentifier] =
    (event: ResourceEvent) => (event.project, event.id)

  /**
    * Creates a resources dummy instance
    *
    * @param projects    the projects operations bundle
    * @param fetchSchema a function to retrieve the schema based on the schema iri
    */
  def apply(
      projects: Projects,
      fetchSchema: ResourceRef => UIO[Option[SchemaResource]]
  )(implicit clock: Clock[UIO], uuidF: UUIDF, rcr: RemoteContextResolution): UIO[ResourcesDummy] =
    for {
      journal <- Journal(moduleType)
      sem     <- IOSemaphore(1L)
    } yield new ResourcesDummy(journal, projects, fetchSchema, sem)

}
