package ch.epfl.bluebrain.nexus.delta.service.resources

import akka.actor.typed.ActorSystem
import akka.persistence.query.Offset
import cats.effect.Clock
import ch.epfl.bluebrain.nexus.delta.kernel.utils.UUIDF
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.sdk.ResolverResolution.ResourceResolution
import ch.epfl.bluebrain.nexus.delta.sdk.ResourceIdCheck.IdAvailability
import ch.epfl.bluebrain.nexus.delta.sdk.Resources._
import ch.epfl.bluebrain.nexus.delta.sdk._
import ch.epfl.bluebrain.nexus.delta.sdk.jsonld.JsonLdSourceProcessor.JsonLdSourceResolvingParser
import ch.epfl.bluebrain.nexus.delta.sdk.model._
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Caller
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Identity.Subject
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.{Project, ProjectRef}
import ch.epfl.bluebrain.nexus.delta.sdk.model.resolvers.ResolverContextResolution
import ch.epfl.bluebrain.nexus.delta.sdk.model.resources.ResourceCommand._
import ch.epfl.bluebrain.nexus.delta.sdk.model.resources.ResourceRejection._
import ch.epfl.bluebrain.nexus.delta.sdk.model.resources.ResourceState.Initial
import ch.epfl.bluebrain.nexus.delta.sdk.model.resources._
import ch.epfl.bluebrain.nexus.delta.sdk.model.schemas.Schema
import ch.epfl.bluebrain.nexus.delta.sdk.syntax._
import ch.epfl.bluebrain.nexus.delta.service.resources.ResourcesImpl.ResourcesAggregate
import ch.epfl.bluebrain.nexus.delta.sourcing._
import ch.epfl.bluebrain.nexus.delta.sourcing.config.AggregateConfig
import ch.epfl.bluebrain.nexus.delta.sourcing.processor.EventSourceProcessor._
import ch.epfl.bluebrain.nexus.delta.sourcing.processor.ShardedAggregate
import fs2.Stream
import io.circe.Json
import monix.bio.{IO, Task, UIO}

final class ResourcesImpl private (
    agg: ResourcesAggregate,
    orgs: Organizations,
    projects: Projects,
    eventLog: EventLog[Envelope[ResourceEvent]],
    sourceParser: JsonLdSourceResolvingParser[ResourceRejection],
    indexingAction: IndexingAction
) extends Resources {

  override def create(
      projectRef: ProjectRef,
      schema: IdSegment,
      source: Json,
      indexing: Indexing
  )(implicit caller: Caller): IO[ResourceRejection, DataResource] = {
    for {
      project                    <- projects.fetchActiveProject(projectRef)
      schemeRef                  <- expandResourceRef(schema, project)
      (iri, compacted, expanded) <- sourceParser(project, source)
      res                        <- eval(CreateResource(iri, projectRef, schemeRef, source, compacted, expanded, caller), project)
      _                          <- indexingAction(projectRef, eventExchangeValue(res), indexing)
    } yield res
  }.named("createResource", moduleType)

  override def create(
      id: IdSegment,
      projectRef: ProjectRef,
      schema: IdSegment,
      source: Json,
      indexing: Indexing
  )(implicit caller: Caller): IO[ResourceRejection, DataResource] = {
    for {
      project               <- projects.fetchActiveProject(projectRef)
      iri                   <- expandIri(id, project)
      schemeRef             <- expandResourceRef(schema, project)
      (compacted, expanded) <- sourceParser(project, iri, source)
      res                   <- eval(CreateResource(iri, projectRef, schemeRef, source, compacted, expanded, caller), project)
      _                     <- indexingAction(projectRef, eventExchangeValue(res), indexing)
    } yield res
  }.named("createResource", moduleType)

  override def update(
      id: IdSegment,
      projectRef: ProjectRef,
      schemaOpt: Option[IdSegment],
      rev: Long,
      source: Json,
      indexing: Indexing
  )(implicit caller: Caller): IO[ResourceRejection, DataResource] = {
    for {
      project               <- projects.fetchActiveProject(projectRef)
      iri                   <- expandIri(id, project)
      schemeRefOpt          <- expandResourceRef(schemaOpt, project)
      (compacted, expanded) <- sourceParser(project, iri, source)
      res                   <-
        eval(UpdateResource(iri, projectRef, schemeRefOpt, source, compacted, expanded, rev, caller), project)
      _                     <- indexingAction(projectRef, eventExchangeValue(res), indexing)
    } yield res
  }.named("updateResource", moduleType)

  override def tag(
      id: IdSegment,
      projectRef: ProjectRef,
      schemaOpt: Option[IdSegment],
      tag: TagLabel,
      tagRev: Long,
      rev: Long,
      indexing: Indexing
  )(implicit caller: Subject): IO[ResourceRejection, DataResource] =
    (for {
      project      <- projects.fetchActiveProject(projectRef)
      iri          <- expandIri(id, project)
      schemeRefOpt <- expandResourceRef(schemaOpt, project)
      res          <- eval(TagResource(iri, projectRef, schemeRefOpt, tagRev, tag, rev, caller), project)
      _            <- indexingAction(projectRef, eventExchangeValue(res), indexing)
    } yield res).named("tagResource", moduleType)

  override def deprecate(
      id: IdSegment,
      projectRef: ProjectRef,
      schemaOpt: Option[IdSegment],
      rev: Long,
      indexing: Indexing
  )(implicit caller: Subject): IO[ResourceRejection, DataResource] =
    (for {
      project      <- projects.fetchActiveProject(projectRef)
      iri          <- expandIri(id, project)
      schemeRefOpt <- expandResourceRef(schemaOpt, project)
      res          <- eval(DeprecateResource(iri, projectRef, schemeRefOpt, rev, caller), project)
      _            <- indexingAction(projectRef, eventExchangeValue(res), indexing)
    } yield res).named("deprecateResource", moduleType)

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
    eventLog.projectEvents(projects, projectRef, offset)

  override def events(
      organization: Label,
      offset: Offset
  ): IO[WrappedOrganizationRejection, Stream[Task, Envelope[ResourceEvent]]] =
    eventLog.orgEvents(orgs, organization, offset)

  override def events(offset: Offset): Stream[Task, Envelope[ResourceEvent]] =
    eventLog.eventsByTag(moduleType, offset)

  private def currentState(projectRef: ProjectRef, iri: Iri): IO[ResourceFetchRejection, ResourceState] =
    agg.state(identifier(projectRef, iri))

  private def stateAt(projectRef: ProjectRef, iri: Iri, rev: Long) =
    eventLog
      .fetchStateAt(persistenceId(moduleType, identifier(projectRef, iri)), rev, Initial, Resources.next)
      .mapError(RevisionNotFound(rev, _))

  private def eval(cmd: ResourceCommand, project: Project): IO[ResourceRejection, DataResource] =
    for {
      evaluationResult <- agg.evaluate(identifier(cmd.project, cmd.id), cmd).mapError(_.value)
      (am, base)        = project.apiMappings -> project.base
      resource         <- IO.fromOption(evaluationResult.state.toResource(am, base), UnexpectedInitialState(cmd.id))
    } yield resource

  private def identifier(projectRef: ProjectRef, id: Iri): String =
    s"${projectRef}_$id"

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

object ResourcesImpl {

  type ResourcesAggregate =
    Aggregate[String, ResourceState, ResourceCommand, ResourceEvent, ResourceRejection]

  private def aggregate(
      config: AggregateConfig,
      resourceResolution: ResourceResolution[Schema],
      idAvailability: IdAvailability[ResourceAlreadyExists]
  )(implicit
      as: ActorSystem[Nothing],
      clock: Clock[UIO]
  ): UIO[ResourcesAggregate] = {
    val definition = PersistentEventDefinition(
      entityType = moduleType,
      initialState = Initial,
      next = Resources.next,
      evaluate = Resources.evaluate(resourceResolution, idAvailability),
      tagger = EventTags.forResourceEvents(moduleType),
      snapshotStrategy = config.snapshotStrategy.strategy,
      stopStrategy = config.stopStrategy.persistentStrategy
    )

    ShardedAggregate.persistentSharded(
      definition = definition,
      config = config.processor
    )
  }

  /**
    * Constructs a [[Resources]] instance.
    *
    * @param orgs the organization operations bundle
    * @param projects the project operations bundle
    * @param resourceResolution to resolve schemas using resolvers
    * @param resourceIdCheck to check whether an id already exists on another module upon creation
    * @param contextResolution the context resolver
    * @param config   the aggregate configuration
    * @param eventLog the event log for [[ResourceEvent]]
    */
  final def apply(
      orgs: Organizations,
      projects: Projects,
      resourceResolution: ResourceResolution[Schema],
      resourceIdCheck: ResourceIdCheck,
      contextResolution: ResolverContextResolution,
      config: AggregateConfig,
      eventLog: EventLog[Envelope[ResourceEvent]],
      indexingAction: IndexingAction
  )(implicit
      uuidF: UUIDF,
      as: ActorSystem[Nothing],
      clock: Clock[UIO]
  ): UIO[Resources] =
    apply(
      orgs,
      projects,
      resourceResolution,
      (project, id) => resourceIdCheck.isAvailableOr(project, id)(ResourceAlreadyExists(id, project)),
      contextResolution,
      config,
      eventLog,
      indexingAction
    )

  private[resources] def apply(
      orgs: Organizations,
      projects: Projects,
      resourceResolution: ResourceResolution[Schema],
      idAvailability: IdAvailability[ResourceAlreadyExists],
      contextResolution: ResolverContextResolution,
      config: AggregateConfig,
      eventLog: EventLog[Envelope[ResourceEvent]],
      consistentWrite: IndexingAction
  )(implicit
      uuidF: UUIDF = UUIDF.random,
      as: ActorSystem[Nothing],
      clock: Clock[UIO]
  ): UIO[Resources] =
    aggregate(config, resourceResolution, idAvailability).map(agg =>
      new ResourcesImpl(
        agg,
        orgs,
        projects,
        eventLog,
        JsonLdSourceResolvingParser[ResourceRejection](contextResolution, uuidF),
        consistentWrite
      )
    )

}
