package ch.epfl.bluebrain.nexus.delta.service.resources

import akka.actor.typed.ActorSystem
import akka.persistence.query.Offset
import cats.effect.Clock
import cats.syntax.all._
import ch.epfl.bluebrain.nexus.delta.kernel.RetryStrategy
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
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
import ch.epfl.bluebrain.nexus.delta.sdk.model.resources.{ResourceCommand, ResourceEvent, ResourceRejection, ResourceState}
import ch.epfl.bluebrain.nexus.delta.sdk.model.schemas.Schema
import ch.epfl.bluebrain.nexus.delta.sdk.utils.UUIDF
import ch.epfl.bluebrain.nexus.delta.service.resources.ResourcesImpl.ResourcesAggregate
import ch.epfl.bluebrain.nexus.delta.service.syntax._
import ch.epfl.bluebrain.nexus.sourcing._
import ch.epfl.bluebrain.nexus.sourcing.config.AggregateConfig
import ch.epfl.bluebrain.nexus.sourcing.processor.EventSourceProcessor._
import ch.epfl.bluebrain.nexus.sourcing.processor.ShardedAggregate
import fs2.Stream
import io.circe.Json
import monix.bio.{IO, Task, UIO}

final class ResourcesImpl private (
    agg: ResourcesAggregate,
    orgs: Organizations,
    projects: Projects,
    eventLog: EventLog[Envelope[ResourceEvent]],
    sourceParser: JsonLdSourceResolvingParser[ResourceRejection]
) extends Resources {

  override def create(
      projectRef: ProjectRef,
      schema: IdSegment,
      source: Json
  )(implicit caller: Caller): IO[ResourceRejection, DataResource] = {
    for {
      project                    <- projects.fetchActiveProject(projectRef)
      schemeRef                  <- expandResourceRef(schema, project)
      (iri, compacted, expanded) <- sourceParser(project, source)
      res                        <- eval(CreateResource(iri, projectRef, schemeRef, source, compacted, expanded, caller), project)
    } yield res
  }.named("createResource", moduleType)

  override def create(
      id: IdSegment,
      projectRef: ProjectRef,
      schema: IdSegment,
      source: Json
  )(implicit caller: Caller): IO[ResourceRejection, DataResource] = {
    for {
      project               <- projects.fetchActiveProject(projectRef)
      iri                   <- expandIri(id, project)
      schemeRef             <- expandResourceRef(schema, project)
      (compacted, expanded) <- sourceParser(project, iri, source)
      res                   <- eval(CreateResource(iri, projectRef, schemeRef, source, compacted, expanded, caller), project)
    } yield res
  }.named("createResource", moduleType)

  override def update(
      id: IdSegment,
      projectRef: ProjectRef,
      schemaOpt: Option[IdSegment],
      rev: Long,
      source: Json
  )(implicit caller: Caller): IO[ResourceRejection, DataResource] = {
    for {
      project               <- projects.fetchActiveProject(projectRef)
      iri                   <- expandIri(id, project)
      schemeRefOpt          <- expandResourceRef(schemaOpt, project)
      (compacted, expanded) <- sourceParser(project, iri, source)
      res                   <-
        eval(UpdateResource(iri, projectRef, schemeRefOpt, source, compacted, expanded, rev, caller), project)
    } yield res
  }.named("updateResource", moduleType)

  override def tag(
      id: IdSegment,
      projectRef: ProjectRef,
      schemaOpt: Option[IdSegment],
      tag: TagLabel,
      tagRev: Long,
      rev: Long
  )(implicit caller: Subject): IO[ResourceRejection, DataResource] =
    (for {
      project      <- projects.fetchActiveProject(projectRef)
      iri          <- expandIri(id, project)
      schemeRefOpt <- expandResourceRef(schemaOpt, project)
      res          <- eval(TagResource(iri, projectRef, schemeRefOpt, tagRev, tag, rev, caller), project)
    } yield res).named("tagResource", moduleType)

  override def deprecate(
      id: IdSegment,
      projectRef: ProjectRef,
      schemaOpt: Option[IdSegment],
      rev: Long
  )(implicit caller: Subject): IO[ResourceRejection, DataResource] =
    (for {
      project      <- projects.fetchActiveProject(projectRef)
      iri          <- expandIri(id, project)
      schemeRefOpt <- expandResourceRef(schemaOpt, project)
      res          <- eval(DeprecateResource(iri, projectRef, schemeRefOpt, rev, caller), project)
    } yield res).named("deprecateResource", moduleType)

  override def fetch(
      id: IdSegment,
      projectRef: ProjectRef,
      schemaOpt: Option[IdSegment]
  ): IO[ResourceFetchRejection, DataResource] =
    fetch(id, projectRef, schemaOpt, None).named("fetchResource", moduleType)

  override def fetchAt(
      id: IdSegment,
      projectRef: ProjectRef,
      schemaOpt: Option[IdSegment],
      rev: Long
  ): IO[ResourceFetchRejection, DataResource] =
    fetch(id, projectRef, schemaOpt, Some(rev)).named("fetchResourceAt", moduleType)

  private def fetch(id: IdSegment, projectRef: ProjectRef, schemaOpt: Option[IdSegment], rev: Option[Long]) =
    for {
      project              <- projects.fetchProject(projectRef)
      iri                  <- expandIri(id, project)
      schemeRefOpt         <- expandResourceRef(schemaOpt, project)
      state                <- rev.fold(currentState(projectRef, iri))(stateAt(projectRef, iri, _))
      resourceOpt           = state.toResource(project.apiMappings, project.base)
      resourceSameSchemaOpt = validateSameSchema(resourceOpt, schemeRefOpt)
      res                  <- IO.fromOption(resourceSameSchemaOpt, ResourceNotFound(iri, projectRef, schemeRefOpt))
    } yield res

  override def fetchBy(
      id: IdSegment,
      projectRef: ProjectRef,
      schemaOpt: Option[IdSegment],
      tag: TagLabel
  ): IO[ResourceFetchRejection, DataResource] =
    super.fetchBy(id, projectRef, schemaOpt, tag).named("fetchResourceBy", moduleType)

  override def events(
      projectRef: ProjectRef,
      offset: Offset
  ): IO[ResourceRejection, Stream[Task, Envelope[ResourceEvent]]] =
    projects
      .fetchProject(projectRef)
      .as(eventLog.eventsByTag(s"${Projects.moduleType}=$projectRef", offset))

  override def events(
      organization: Label,
      offset: Offset
  ): IO[WrappedOrganizationRejection, Stream[Task, Envelope[ResourceEvent]]] =
    orgs
      .fetchOrganization(organization)
      .as(eventLog.eventsByTag(s"${Organizations.moduleType}=$organization", offset))

  override def events(offset: Offset): Stream[Task, Envelope[ResourceEvent]] =
    eventLog.eventsByTag(moduleType, offset)

  private def currentState(projectRef: ProjectRef, iri: Iri): IO[ResourceFetchRejection, ResourceState] =
    agg.state(identifier(projectRef, iri))

  private def stateAt(projectRef: ProjectRef, iri: Iri, rev: Long) =
    eventLog
      .fetchStateAt(persistenceId(moduleType, identifier(projectRef, iri)), rev, Initial, Resources.next)
      .leftMap(RevisionNotFound(rev, _))

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
      case None         => IO.pure(None)
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

  private def aggregate(config: AggregateConfig, resourceResolution: ResourceResolution[Schema])(implicit
      as: ActorSystem[Nothing],
      clock: Clock[UIO]
  ): UIO[ResourcesAggregate] = {
    val definition = PersistentEventDefinition(
      entityType = moduleType,
      initialState = Initial,
      next = Resources.next,
      evaluate = Resources.evaluate(resourceResolution),
      tagger = (ev: ResourceEvent) =>
        Set(
          moduleType,
          s"${Projects.moduleType}=${ev.project}",
          s"${Organizations.moduleType}=${ev.project.organization}"
        ),
      snapshotStrategy = config.snapshotStrategy.strategy,
      stopStrategy = config.stopStrategy.persistentStrategy
    )

    ShardedAggregate.persistentSharded(
      definition = definition,
      config = config.processor,
      retryStrategy = RetryStrategy.alwaysGiveUp
      // TODO: configure the number of shards
    )
  }

  /**
    * Constructs a [[Resources]] instance.
    *
    * @param projects the project operations bundle
    * @param resourceResolution to resolve schemas using resolvers
    * @param contextResolution the context resolver
    * @param config   the aggregate configuration
    * @param eventLog the event log for [[ResourceEvent]]
    */
  final def apply(
      orgs: Organizations,
      projects: Projects,
      resourceResolution: ResourceResolution[Schema],
      contextResolution: ResolverContextResolution,
      config: AggregateConfig,
      eventLog: EventLog[Envelope[ResourceEvent]]
  )(implicit
      uuidF: UUIDF = UUIDF.random,
      as: ActorSystem[Nothing],
      clock: Clock[UIO]
  ): UIO[Resources] =
    aggregate(config, resourceResolution).map(agg =>
      new ResourcesImpl(
        agg,
        orgs,
        projects,
        eventLog,
        new JsonLdSourceResolvingParser[ResourceRejection](None, contextResolution, uuidF)
      )
    )

}
