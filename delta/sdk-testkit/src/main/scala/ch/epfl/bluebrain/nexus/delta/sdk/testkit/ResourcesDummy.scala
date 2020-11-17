package ch.epfl.bluebrain.nexus.delta.sdk.testkit

import akka.persistence.query.Offset
import cats.effect.Clock
import cats.syntax.all._
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.RemoteContextResolution
import ch.epfl.bluebrain.nexus.delta.sdk.Resources.moduleType
import ch.epfl.bluebrain.nexus.delta.sdk._
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Identity.Subject
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.{Project, ProjectRef}
import ch.epfl.bluebrain.nexus.delta.sdk.model.resources.ResourceCommand._
import ch.epfl.bluebrain.nexus.delta.sdk.model.resources.ResourceRejection._
import ch.epfl.bluebrain.nexus.delta.sdk.model.resources.ResourceState.Initial
import ch.epfl.bluebrain.nexus.delta.sdk.model.resources.{ResourceCommand, ResourceEvent, ResourceRejection}
import ch.epfl.bluebrain.nexus.delta.sdk.model.{Envelope, IdSegment, Label, ResourceRef}
import ch.epfl.bluebrain.nexus.delta.sdk.testkit.ResourcesDummy.ResourcesJournal
import ch.epfl.bluebrain.nexus.delta.sdk.utils.UUIDF
import ch.epfl.bluebrain.nexus.testkit.IOSemaphore
import fs2.Stream
import io.circe.Json
import monix.bio.{IO, Task, UIO}

/**
  * A dummy Resources implementation
  *
  * @param journal     the journal to store events
  * @param orgs        the organizations operations bundle
  * @param projects    the projects operations bundle
  * @param fetchSchema a function to retrieve the schema based on the schema iri
  * @param semaphore   a semaphore for serializing write operations on the journal
  */
final class ResourcesDummy private (
    journal: ResourcesJournal,
    orgs: Organizations,
    projects: Projects,
    fetchSchema: ResourceRef => UIO[Option[SchemaResource]],
    semaphore: IOSemaphore
)(implicit clock: Clock[UIO], uuidF: UUIDF, rcr: RemoteContextResolution)
    extends Resources {

  override def create(
      projectRef: ProjectRef,
      schema: IdSegment,
      source: Json
  )(implicit caller: Subject): IO[ResourceRejection, DataResource] =
    for {
      project                    <- fetchActiveProject(projectRef)
      schemeRef                  <- expandResourceRef(schema, project)
      (iri, compacted, expanded) <- ResourceSourceParser.asJsonLd(project, source)
      res                        <- eval(CreateResource(iri, projectRef, schemeRef, source, compacted, expanded, caller), project)
    } yield res

  override def create(
      id: IdSegment,
      projectRef: ProjectRef,
      schema: IdSegment,
      source: Json
  )(implicit caller: Subject): IO[ResourceRejection, DataResource] =
    for {
      project               <- fetchActiveProject(projectRef)
      iri                   <- expandIri(id, project)
      schemeRef             <- expandResourceRef(schema, project)
      (compacted, expanded) <- ResourceSourceParser.asJsonLd(project, iri, source)
      res                   <- eval(CreateResource(iri, projectRef, schemeRef, source, compacted, expanded, caller), project)
    } yield res

  override def update(
      id: IdSegment,
      projectRef: ProjectRef,
      schemaOpt: Option[IdSegment],
      rev: Long,
      source: Json
  )(implicit caller: Subject): IO[ResourceRejection, DataResource] =
    for {
      project               <- fetchActiveProject(projectRef)
      iri                   <- expandIri(id, project)
      schemeRefOpt          <- expandResourceRef(schemaOpt, project)
      (compacted, expanded) <- ResourceSourceParser.asJsonLd(project, iri, source)
      res                   <- eval(UpdateResource(iri, projectRef, schemeRefOpt, source, compacted, expanded, rev, caller), project)
    } yield res

  override def tag(
      id: IdSegment,
      projectRef: ProjectRef,
      schemaOpt: Option[IdSegment],
      tag: Label,
      tagRev: Long,
      rev: Long
  )(implicit caller: Subject): IO[ResourceRejection, DataResource] =
    for {
      project      <- fetchActiveProject(projectRef)
      iri          <- expandIri(id, project)
      schemeRefOpt <- expandResourceRef(schemaOpt, project)
      res          <- eval(TagResource(iri, projectRef, schemeRefOpt, tagRev, tag, rev, caller), project)
    } yield res

  override def deprecate(
      id: IdSegment,
      projectRef: ProjectRef,
      schemaOpt: Option[IdSegment],
      rev: Long
  )(implicit caller: Subject): IO[ResourceRejection, DataResource] =
    for {
      project      <- fetchActiveProject(projectRef)
      iri          <- expandIri(id, project)
      schemeRefOpt <- expandResourceRef(schemaOpt, project)
      res          <- eval(DeprecateResource(iri, projectRef, schemeRefOpt, rev, caller), project)
    } yield res

  override def fetch(
      id: IdSegment,
      projectRef: ProjectRef,
      schemaOpt: Option[IdSegment]
  ): IO[ResourceRejection, Option[DataResource]] =
    for {
      project      <- fetchProject(projectRef)
      iri          <- expandIri(id, project)
      schemeRefOpt <- expandResourceRef(schemaOpt, project)
      stateOpt     <- currentState(projectRef, iri)
      resource      = stateOpt.flatMap(_.toResource(project.apiMappings, project.base))
    } yield validateSameSchema(resource, schemeRefOpt)

  override def fetchAt(
      id: IdSegment,
      projectRef: ProjectRef,
      schemaOpt: Option[IdSegment],
      rev: Long
  ): IO[ResourceRejection, Option[DataResource]] =
    for {
      project      <- fetchProject(projectRef)
      iri          <- expandIri(id, project)
      schemeRefOpt <- expandResourceRef(schemaOpt, project)
      stateOpt     <- stateAt(projectRef, iri, rev)
      resource      = stateOpt.flatMap(_.toResource(project.apiMappings, project.base))
    } yield validateSameSchema(resource, schemeRefOpt)

  override def events(
      projectRef: ProjectRef,
      offset: Offset
  ): IO[WrappedProjectRejection, Stream[Task, Envelope[ResourceEvent]]] =
    projects
      .fetchProject(projectRef)
      .leftMap(WrappedProjectRejection)
      .as(journal.events(offset).filter(e => e.event.project == projectRef))

  override def events(
      organization: Label,
      offset: Offset
  ): IO[WrappedOrganizationRejection, Stream[Task, Envelope[ResourceEvent]]] =
    orgs
      .fetchOrganization(organization)
      .leftMap(WrappedOrganizationRejection)
      .as(journal.events(offset).filter(e => e.event.project.organization == organization))

  override def events(offset: Offset): Stream[Task, Envelope[ResourceEvent]] =
    journal.events(offset)

  private def currentState(projectRef: ProjectRef, iri: Iri) =
    journal.currentState((projectRef, iri), Initial, Resources.next)

  private def stateAt(projectRef: ProjectRef, iri: Iri, rev: Long) =
    journal.stateAt((projectRef, iri), rev, Initial, Resources.next, RevisionNotFound.apply)

  private def eval(cmd: ResourceCommand, project: Project): IO[ResourceRejection, DataResource] =
    semaphore.withPermit {
      for {
        state     <- journal.currentState((cmd.project, cmd.id), Initial, Resources.next).map(_.getOrElse(Initial))
        event     <- Resources.evaluate(fetchSchema)(state, cmd)
        _         <- journal.add(event)
        (am, base) = project.apiMappings -> project.base
        res       <- IO.fromEither(Resources.next(state, event).toResource(am, base).toRight(UnexpectedInitialState(cmd.id)))
      } yield res
    }

  private def fetchActiveProject(projectRef: ProjectRef) =
    projects.fetchActiveProject(projectRef).leftMap(WrappedProjectRejection)

  private def fetchProject(projectRef: ProjectRef) =
    projects.fetchProject(projectRef).leftMap(WrappedProjectRejection)

  private def expandIri(segment: IdSegment, project: Project) =
    IO.fromOption(segment.toIri(project.apiMappings, project.base), InvalidResourceId(segment.asString))

  private def expandResourceRef(segment: IdSegment, project: Project): IO[InvalidResourceId, ResourceRef] =
    IO.fromOption(
      segment.toIri(project.apiMappings, project.base).map(ResourceRef(_)),
      InvalidResourceId(segment.asString)
    )

  private def expandResourceRef(
      segmentOpt: Option[IdSegment],
      project: Project
  ): IO[InvalidResourceId, Option[ResourceRef]] =
    segmentOpt match {
      case None         => IO.pure(None)
      case Some(schema) => expandResourceRef(schema, project).map(Some.apply)
    }

  private def validateSameSchema(resourceOpt: Option[DataResource], schemaOpt: Option[ResourceRef]) =
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
    * @param orgs        the organizations operations bundle
    * @param projects    the projects operations bundle
    * @param fetchSchema a function to retrieve the schema based on the schema iri
    */
  def apply(
      orgs: Organizations,
      projects: Projects,
      fetchSchema: ResourceRef => UIO[Option[SchemaResource]]
  )(implicit clock: Clock[UIO], uuidF: UUIDF, rcr: RemoteContextResolution): UIO[ResourcesDummy] =
    for {
      journal <- Journal(moduleType)
      sem     <- IOSemaphore(1L)
    } yield new ResourcesDummy(journal, orgs, projects, fetchSchema, sem)

}
