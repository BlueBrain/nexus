package ch.epfl.bluebrain.nexus.delta.sdk.testkit

import akka.persistence.query.Offset
import cats.effect.Clock
import ch.epfl.bluebrain.nexus.delta.kernel.Lens
import ch.epfl.bluebrain.nexus.delta.kernel.utils.UUIDF
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.api.JsonLdApi
import ch.epfl.bluebrain.nexus.delta.sdk.ResolverResolution.ResourceResolution
import ch.epfl.bluebrain.nexus.delta.sdk.ResourceIdCheck.IdAvailability
import ch.epfl.bluebrain.nexus.delta.sdk.Resources._
import ch.epfl.bluebrain.nexus.delta.sdk._
import ch.epfl.bluebrain.nexus.delta.sdk.jsonld.JsonLdSourceProcessor.JsonLdSourceResolvingParser
import ch.epfl.bluebrain.nexus.delta.sdk.model._
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Caller
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Identity.Subject
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.ProjectFetchOptions._
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.Project
import ch.epfl.bluebrain.nexus.delta.sdk.model.resolvers.ResolverContextResolution
import ch.epfl.bluebrain.nexus.delta.sdk.model.resources.ResourceCommand._
import ch.epfl.bluebrain.nexus.delta.sdk.model.resources.ResourceRejection._
import ch.epfl.bluebrain.nexus.delta.sdk.model.resources.ResourceState.Initial
import ch.epfl.bluebrain.nexus.delta.sdk.model.resources.{ResourceCommand, ResourceEvent, ResourceRejection, ResourceState}
import ch.epfl.bluebrain.nexus.delta.sdk.model.schemas.Schema
import ch.epfl.bluebrain.nexus.delta.sdk.testkit.ResourcesDummy.ResourcesJournal
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{Label, ResourceRef}
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Tag.UserTag
import ch.epfl.bluebrain.nexus.delta.sourcing.model.ProjectRef
import ch.epfl.bluebrain.nexus.testkit.IOSemaphore
import fs2.Stream
import io.circe.Json
import monix.bio.{IO, Task, UIO}

/**
  * A dummy Resources implementation
  *
  * @param journal
  *   the journal to store events
  * @param orgs
  *   the organizations operations bundle
  * @param projects
  *   the projects operations bundle
  * @param resourceResolution
  *   to resolve schemas using resolvers
  * @param semaphore
  *   a semaphore for serializing write operations on the journal
  */
final class ResourcesDummy private (
    val journal: ResourcesJournal,
    orgs: Organizations,
    projects: Projects,
    resourceResolution: ResourceResolution[Schema],
    idAvailability: IdAvailability[ResourceAlreadyExists],
    semaphore: IOSemaphore,
    sourceParser: JsonLdSourceResolvingParser[ResourceRejection]
)(implicit api: JsonLdApi, clock: Clock[UIO])
    extends Resources {

  private val eval = Resources.evaluate(resourceResolution, idAvailability)(_, _)

  override def create(
      projectRef: ProjectRef,
      schema: IdSegment,
      source: Json
  )(implicit caller: Caller): IO[ResourceRejection, DataResource] =
    for {
      project                    <- projects.fetchProject(projectRef, notDeprecatedOrDeletedWithQuotas)
      schemeRef                  <- expandResourceRef(schema, project)
      (iri, compacted, expanded) <- sourceParser(project, source)
      res                        <- eval(CreateResource(iri, projectRef, schemeRef, source, compacted, expanded, caller), project)
    } yield res

  override def create(
      id: IdSegment,
      projectRef: ProjectRef,
      schema: IdSegment,
      source: Json
  )(implicit caller: Caller): IO[ResourceRejection, DataResource] =
    for {
      project               <- projects.fetchProject(projectRef, notDeprecatedOrDeletedWithQuotas)
      iri                   <- expandIri(id, project)
      schemeRef             <- expandResourceRef(schema, project)
      (compacted, expanded) <- sourceParser(project, iri, source)
      res                   <- eval(CreateResource(iri, projectRef, schemeRef, source, compacted, expanded, caller), project)
    } yield res

  override def update(
      id: IdSegment,
      projectRef: ProjectRef,
      schemaOpt: Option[IdSegment],
      rev: Long,
      source: Json
  )(implicit caller: Caller): IO[ResourceRejection, DataResource] =
    for {
      project               <- projects.fetchProject(projectRef, notDeprecatedOrDeletedWithEventQuotas)
      iri                   <- expandIri(id, project)
      schemeRefOpt          <- expandResourceRef(schemaOpt, project)
      (compacted, expanded) <- sourceParser(project, iri, source)
      res                   <-
        eval(UpdateResource(iri, projectRef, schemeRefOpt, source, compacted, expanded, rev, caller), project)
    } yield res

  override def tag(
      id: IdSegment,
      projectRef: ProjectRef,
      schemaOpt: Option[IdSegment],
      tag: UserTag,
      tagRev: Long,
      rev: Long
  )(implicit caller: Subject): IO[ResourceRejection, DataResource] =
    for {
      project      <- projects.fetchProject(projectRef, notDeprecatedOrDeletedWithEventQuotas)
      iri          <- expandIri(id, project)
      schemeRefOpt <- expandResourceRef(schemaOpt, project)
      res          <- eval(TagResource(iri, projectRef, schemeRefOpt, tagRev, tag, rev, caller), project)
    } yield res

  override def deleteTag(
      id: IdSegment,
      projectRef: ProjectRef,
      schemaOpt: Option[IdSegment],
      tag: UserTag,
      rev: Long
  )(implicit caller: Subject): IO[ResourceRejection, DataResource] =
    for {
      project      <- projects.fetchProject(projectRef, notDeprecatedOrDeletedWithEventQuotas)
      iri          <- expandIri(id, project)
      schemeRefOpt <- expandResourceRef(schemaOpt, project)
      res          <- eval(DeleteResourceTag(iri, projectRef, schemeRefOpt, tag, rev, caller), project)
    } yield res

  override def deprecate(
      id: IdSegment,
      projectRef: ProjectRef,
      schemaOpt: Option[IdSegment],
      rev: Long
  )(implicit caller: Subject): IO[ResourceRejection, DataResource] =
    for {
      project      <- projects.fetchProject(projectRef, notDeprecatedOrDeletedWithEventQuotas)
      iri          <- expandIri(id, project)
      schemeRefOpt <- expandResourceRef(schemaOpt, project)
      res          <- eval(DeprecateResource(iri, projectRef, schemeRefOpt, rev, caller), project)
    } yield res

  override def fetch(
      id: IdSegmentRef,
      projectRef: ProjectRef,
      schemaOpt: Option[IdSegment]
  ): IO[ResourceFetchRejection, DataResource] =
    id.asTag.fold(
      for {
        project              <- projects.fetchProject(projectRef)
        iri                  <- expandIri(id.value, project)
        schemeRefOpt         <- expandResourceRef(schemaOpt, project)
        state                <- id.asRev.fold(currentState(projectRef, iri))(id => stateAt(projectRef, iri, id.rev))
        resourceOpt           = state.toResource(project.apiMappings, project.base)
        resourceSameSchemaOpt = validateSameSchema(resourceOpt, schemeRefOpt)
        res                  <- IO.fromOption(resourceSameSchemaOpt, ResourceNotFound(iri, projectRef, schemeRefOpt))
      } yield res
    )(fetchBy(_, projectRef, schemaOpt))

  override def events(
      projectRef: ProjectRef,
      offset: Offset
  ): IO[ResourceRejection, Stream[Task, Envelope[ResourceEvent]]] =
    projects
      .fetchProject(projectRef)
      .as(journal.eventsByTag(Projects.projectTag(projectRef), offset))

  override def currentEvents(
      projectRef: ProjectRef,
      offset: Offset
  ): IO[ResourceRejection, Stream[Task, Envelope[ResourceEvent]]] =
    projects
      .fetchProject(projectRef)
      .as(journal.currentEventsByTag(Projects.projectTag(projectRef), offset))

  override def events(
      organization: Label,
      offset: Offset
  ): IO[WrappedOrganizationRejection, Stream[Task, Envelope[ResourceEvent]]] =
    orgs
      .fetchOrganization(organization)
      .as(journal.eventsByTag(Organizations.orgTag(organization), offset))

  override def events(offset: Offset): Stream[Task, Envelope[ResourceEvent]] =
    journal.events(offset)

  private def currentState(projectRef: ProjectRef, iri: Iri): IO[ResourceFetchRejection, ResourceState] =
    journal.currentState((projectRef, iri), Initial, Resources.next).map(_.getOrElse(Initial))

  private def stateAt(projectRef: ProjectRef, iri: Iri, rev: Long): IO[RevisionNotFound, ResourceState] =
    journal.stateAt((projectRef, iri), rev, Initial, Resources.next, RevisionNotFound.apply).map(_.getOrElse(Initial))

  private def eval(cmd: ResourceCommand, project: Project): IO[ResourceRejection, DataResource] =
    semaphore.withPermit {
      for {
        state     <- currentState(cmd.project, cmd.id)
        event     <- eval(state, cmd)
        _         <- journal.add(event)
        (am, base) = project.apiMappings -> project.base
        res       <- IO.fromOption(Resources.next(state, event).toResource(am, base), UnexpectedInitialState(cmd.id))
      } yield res
    }

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
      case None         => IO.none
      case Some(schema) => expandResourceRef(schema, project).map(Some.apply)
    }

  private def validateSameSchema(resourceOpt: Option[DataResource], schemaOpt: Option[ResourceRef]) =
    resourceOpt match {
      case Some(value) if schemaOpt.forall(_.iri == value.schema.iri) => Some(value)
      case _                                                          => None
    }
}

object ResourcesDummy {

  type ResourceIdentifier = (ProjectRef, Iri)

  type ResourcesJournal = Journal[ResourceIdentifier, ResourceEvent]

  implicit val eventLens: Lens[ResourceEvent, ResourceIdentifier] =
    (event: ResourceEvent) => (event.project, event.id)

  /**
    * Creates a resources dummy instance
    *
    * @param orgs
    *   the organizations operations bundle
    * @param projects
    *   the projects operations bundle
    * @param resourceResolution
    *   to resolve schemas using resolvers
    * @param contextResolution
    *   the context resolver
    */
  def apply(
      orgs: Organizations,
      projects: Projects,
      resourceResolution: ResourceResolution[Schema],
      idAvailability: IdAvailability[ResourceAlreadyExists],
      contextResolution: ResolverContextResolution
  )(implicit api: JsonLdApi, clock: Clock[UIO], uuidF: UUIDF): UIO[ResourcesDummy] =
    for {
      journal <- Journal(moduleType, 1L, EventTags.forResourceEvents(moduleType))
      sem     <- IOSemaphore(1L)
      parser   = JsonLdSourceResolvingParser[ResourceRejection](contextResolution, uuidF)
    } yield new ResourcesDummy(
      journal,
      orgs,
      projects,
      resourceResolution,
      idAvailability,
      sem,
      parser
    )

  /**
    * Creates a resources dummy instance
    *
    * @param orgs
    *   the organizations operations bundle
    * @param projects
    *   the projects operations bundle
    * @param resourceResolution
    *   to resolve schemas using resolvers
    * @param idAvailability
    *   to resolve schemas using resolvers
    * @param contextResolution
    *   the context resolver
    * @param journal
    *   underlying [[Journal]]
    */
  def apply(
      orgs: Organizations,
      projects: Projects,
      resourceResolution: ResourceResolution[Schema],
      idAvailability: IdAvailability[ResourceAlreadyExists],
      contextResolution: ResolverContextResolution,
      journal: ResourcesJournal
  )(implicit api: JsonLdApi, clock: Clock[UIO], uuidF: UUIDF): UIO[ResourcesDummy] =
    for {
      sem   <- IOSemaphore(1L)
      parser = JsonLdSourceResolvingParser[ResourceRejection](contextResolution, uuidF)
    } yield new ResourcesDummy(
      journal,
      orgs,
      projects,
      resourceResolution,
      idAvailability,
      sem,
      parser
    )

}
