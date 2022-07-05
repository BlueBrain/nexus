package ch.epfl.bluebrain.nexus.delta.service.resources

import akka.actor.typed.ActorSystem
import akka.persistence.query.Offset
import cats.effect.Clock
import ch.epfl.bluebrain.nexus.delta.kernel.utils.UUIDF
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.api.JsonLdApi
import ch.epfl.bluebrain.nexus.delta.sdk.ResolverResolution.ResourceResolution
import ch.epfl.bluebrain.nexus.delta.sdk.ResourceIdCheck.IdAvailability
import ch.epfl.bluebrain.nexus.delta.sdk.Resources._
import ch.epfl.bluebrain.nexus.delta.sdk._
import ch.epfl.bluebrain.nexus.delta.sdk.identities.model.Caller
import ch.epfl.bluebrain.nexus.delta.sdk.implicits._
import ch.epfl.bluebrain.nexus.delta.sdk.jsonld.JsonLdSourceProcessor.JsonLdSourceResolvingParser
import ch.epfl.bluebrain.nexus.delta.sdk.model._
import ch.epfl.bluebrain.nexus.delta.sdk.model.resolvers.ResolverContextResolution
import ch.epfl.bluebrain.nexus.delta.sdk.model.resources.ResourceCommand._
import ch.epfl.bluebrain.nexus.delta.sdk.model.resources.ResourceRejection._
import ch.epfl.bluebrain.nexus.delta.sdk.model.resources.ResourceState.Initial
import ch.epfl.bluebrain.nexus.delta.sdk.model.resources._
import ch.epfl.bluebrain.nexus.delta.sdk.model.schemas.Schema
import ch.epfl.bluebrain.nexus.delta.sdk.projects.FetchContext
import ch.epfl.bluebrain.nexus.delta.sdk.projects.model.ProjectContext
import ch.epfl.bluebrain.nexus.delta.service.resources.ResourcesImpl.ResourcesAggregate
import ch.epfl.bluebrain.nexus.delta.sourcing._
import ch.epfl.bluebrain.nexus.delta.sourcing.config.AggregateConfig
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.Subject
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Tag.UserTag
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{Label, ProjectRef, ResourceRef}
import ch.epfl.bluebrain.nexus.delta.sourcing.processor.EventSourceProcessor._
import ch.epfl.bluebrain.nexus.delta.sourcing.processor.ShardedAggregate
import fs2.Stream
import io.circe.Json
import monix.bio.{IO, Task, UIO}

final class ResourcesImpl private (
    agg: ResourcesAggregate,
    fetchContext: FetchContext[ResourceFetchRejection],
    eventLog: EventLog[Envelope[ResourceEvent]],
    sourceParser: JsonLdSourceResolvingParser[ResourceRejection]
) extends Resources {

  override def create(
      projectRef: ProjectRef,
      schema: IdSegment,
      source: Json
  )(implicit caller: Caller): IO[ResourceRejection, DataResource] = {
    for {
      projectContext             <- fetchContext.onCreate(projectRef)
      schemeRef                  <- expandResourceRef(schema, projectContext)
      (iri, compacted, expanded) <- sourceParser(projectRef, projectContext, source)
      res                        <- eval(CreateResource(iri, projectRef, schemeRef, source, compacted, expanded, caller), projectContext)
    } yield res
  }.named("createResource", moduleType)

  override def create(
      id: IdSegment,
      projectRef: ProjectRef,
      schema: IdSegment,
      source: Json
  )(implicit caller: Caller): IO[ResourceRejection, DataResource] = {
    for {
      projectContext        <- fetchContext.onCreate(projectRef)
      iri                   <- expandIri(id, projectContext)
      schemeRef             <- expandResourceRef(schema, projectContext)
      (compacted, expanded) <- sourceParser(projectRef, projectContext, iri, source)
      res                   <- eval(CreateResource(iri, projectRef, schemeRef, source, compacted, expanded, caller), projectContext)
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
      projectContext        <- fetchContext.onModify(projectRef)
      iri                   <- expandIri(id, projectContext)
      schemeRefOpt          <- expandResourceRef(schemaOpt, projectContext)
      (compacted, expanded) <- sourceParser(projectRef, projectContext, iri, source)
      res                   <-
        eval(UpdateResource(iri, projectRef, schemeRefOpt, source, compacted, expanded, rev, caller), projectContext)
    } yield res
  }.named("updateResource", moduleType)

  override def tag(
      id: IdSegment,
      projectRef: ProjectRef,
      schemaOpt: Option[IdSegment],
      tag: UserTag,
      tagRev: Long,
      rev: Long
  )(implicit caller: Subject): IO[ResourceRejection, DataResource] =
    (for {
      projectContext <- fetchContext.onModify(projectRef)
      iri            <- expandIri(id, projectContext)
      schemeRefOpt   <- expandResourceRef(schemaOpt, projectContext)
      res            <- eval(TagResource(iri, projectRef, schemeRefOpt, tagRev, tag, rev, caller), projectContext)
    } yield res).named("tagResource", moduleType)

  override def deleteTag(
      id: IdSegment,
      projectRef: ProjectRef,
      schemaOpt: Option[IdSegment],
      tag: UserTag,
      rev: Long
  )(implicit caller: Subject): IO[ResourceRejection, DataResource] =
    (for {
      projectContext <- fetchContext.onModify(projectRef)
      iri            <- expandIri(id, projectContext)
      schemeRefOpt   <- expandResourceRef(schemaOpt, projectContext)
      res            <- eval(DeleteResourceTag(iri, projectRef, schemeRefOpt, tag, rev, caller), projectContext)
    } yield res).named("deleteResourceTag", moduleType)

  override def deprecate(
      id: IdSegment,
      projectRef: ProjectRef,
      schemaOpt: Option[IdSegment],
      rev: Long
  )(implicit caller: Subject): IO[ResourceRejection, DataResource] =
    (for {
      projectContext <- fetchContext.onModify(projectRef)
      iri            <- expandIri(id, projectContext)
      schemeRefOpt   <- expandResourceRef(schemaOpt, projectContext)
      res            <- eval(DeprecateResource(iri, projectRef, schemeRefOpt, rev, caller), projectContext)
    } yield res).named("deprecateResource", moduleType)

  override def fetch(
      id: IdSegmentRef,
      projectRef: ProjectRef,
      schemaOpt: Option[IdSegment]
  ): IO[ResourceFetchRejection, DataResource] =
    id.asTag.fold(
      for {
        projectContext       <- fetchContext.onRead(projectRef)
        iri                  <- expandIri(id.value, projectContext)
        schemeRefOpt         <- expandResourceRef(schemaOpt, projectContext)
        state                <- id.asRev.fold(currentState(projectRef, iri))(id => stateAt(projectRef, iri, id.rev))
        resourceOpt           = state.toResource(projectContext.apiMappings, projectContext.base)
        resourceSameSchemaOpt = validateSameSchema(resourceOpt, schemeRefOpt)
        res                  <- IO.fromOption(resourceSameSchemaOpt, ResourceNotFound(iri, projectRef, schemeRefOpt))
      } yield res
    )(fetchBy(_, projectRef, schemaOpt))

  override def events(
      projectRef: ProjectRef,
      offset: Offset
  ): IO[ResourceRejection, Stream[Task, Envelope[ResourceEvent]]] =
    IO.pure(Stream.empty)

  override def currentEvents(
      projectRef: ProjectRef,
      offset: Offset
  ): IO[ResourceRejection, Stream[Task, Envelope[ResourceEvent]]] =
    IO.pure(Stream.empty)

  override def events(
      organization: Label,
      offset: Offset
  ): IO[WrappedOrganizationRejection, Stream[Task, Envelope[ResourceEvent]]] =
    IO.pure(Stream.empty)

  override def events(offset: Offset): Stream[Task, Envelope[ResourceEvent]] =
    eventLog.eventsByTag(moduleType, offset)

  private def currentState(projectRef: ProjectRef, iri: Iri): IO[ResourceFetchRejection, ResourceState] =
    agg.state(identifier(projectRef, iri))

  private def stateAt(projectRef: ProjectRef, iri: Iri, rev: Long) =
    eventLog
      .fetchStateAt(persistenceId(moduleType, identifier(projectRef, iri)), rev, Initial, Resources.next)
      .mapError(RevisionNotFound(rev, _))

  private def eval(cmd: ResourceCommand, projectContext: ProjectContext): IO[ResourceRejection, DataResource] =
    for {
      evaluationResult <- agg.evaluate(identifier(cmd.project, cmd.id), cmd).mapError(_.value)
      (am, base)        = projectContext.apiMappings -> projectContext.base
      resource         <- IO.fromOption(evaluationResult.state.toResource(am, base), UnexpectedInitialState(cmd.id))
    } yield resource

  private def identifier(projectRef: ProjectRef, id: Iri): String =
    s"${projectRef}_$id"

  private def expandResourceRef(segment: IdSegment, context: ProjectContext): IO[InvalidResourceId, ResourceRef] =
    IO.fromOption(
      segment.toIri(context.apiMappings, context.base).map(ResourceRef(_)),
      InvalidResourceId(segment.asString)
    )

  private def expandResourceRef(
      segmentOpt: Option[IdSegment],
      context: ProjectContext
  ): IO[InvalidResourceId, Option[ResourceRef]] =
    segmentOpt match {
      case None         => IO.none
      case Some(schema) => expandResourceRef(schema, context).map(Some.apply)
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

  def aggregate(
      config: AggregateConfig,
      resourceResolution: ResourceResolution[Schema],
      resourceIdCheck: ResourceIdCheck
  )(implicit api: JsonLdApi, as: ActorSystem[Nothing], clock: Clock[UIO]): UIO[ResourcesAggregate] =
    aggregate(
      config,
      resourceResolution,
      (project, id) => resourceIdCheck.isAvailableOr(project, id)(ResourceAlreadyExists(id, project))
    )

  private def aggregate(
      config: AggregateConfig,
      resourceResolution: ResourceResolution[Schema],
      idAvailability: IdAvailability[ResourceAlreadyExists]
  )(implicit api: JsonLdApi, as: ActorSystem[Nothing], clock: Clock[UIO]): UIO[ResourcesAggregate] = {
    val definition = PersistentEventDefinition(
      entityType = moduleType,
      initialState = Initial,
      next = Resources.next,
      evaluate = Resources.evaluate(resourceResolution, idAvailability),
      tagger = (_: ResourceEvent) => Set.empty,
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
    * @param fetchContext
    *   to fetch the project context
    * @param contextResolution
    *   the context resolver
    * @param eventLog
    *   the event log for [[ResourceEvent]]
    */
  final def apply(
      fetchContext: FetchContext[ResourceFetchRejection],
      agg: ResourcesAggregate,
      contextResolution: ResolverContextResolution,
      eventLog: EventLog[Envelope[ResourceEvent]]
  )(implicit api: JsonLdApi, uuidF: UUIDF = UUIDF.random): Resources =
    new ResourcesImpl(
      agg,
      fetchContext,
      eventLog,
      JsonLdSourceResolvingParser[ResourceRejection](contextResolution, uuidF)
    )

}
