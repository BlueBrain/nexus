package ch.epfl.bluebrain.nexus.delta.service.resolvers

import akka.actor.typed.ActorSystem
import akka.persistence.query.Offset
import cats.effect.Clock
import ch.epfl.bluebrain.nexus.delta.kernel.RetryStrategy
import ch.epfl.bluebrain.nexus.delta.kernel.utils.UUIDF
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.contexts
import ch.epfl.bluebrain.nexus.delta.sdk.Resolvers._
import ch.epfl.bluebrain.nexus.delta.sdk.ResourceIdCheck.IdAvailability
import ch.epfl.bluebrain.nexus.delta.sdk._
import ch.epfl.bluebrain.nexus.delta.sdk.cache.{CompositeKeyValueStore, KeyValueStoreConfig}
import ch.epfl.bluebrain.nexus.delta.sdk.jsonld.JsonLdSourceProcessor.JsonLdSourceResolvingDecoder
import ch.epfl.bluebrain.nexus.delta.sdk.model._
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.{Caller, Identity}
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.ProjectFetchOptions.{NotDeprecated, VerifyQuotaResources}
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.{Project, ProjectRef}
import ch.epfl.bluebrain.nexus.delta.sdk.model.resolvers.ResolverCommand.{CreateResolver, DeprecateResolver, TagResolver, UpdateResolver}
import ch.epfl.bluebrain.nexus.delta.sdk.model.resolvers.ResolverRejection._
import ch.epfl.bluebrain.nexus.delta.sdk.model.resolvers.ResolverState.Initial
import ch.epfl.bluebrain.nexus.delta.sdk.model.resolvers._
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.Pagination.FromPagination
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.ResultEntry.UnscoredResultEntry
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.SearchParams.ResolverSearchParams
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.SearchResults.UnscoredSearchResults
import ch.epfl.bluebrain.nexus.delta.sdk.syntax._
import ch.epfl.bluebrain.nexus.delta.service.resolvers.ResolversImpl.{ResolversAggregate, ResolversCache}
import ch.epfl.bluebrain.nexus.delta.sourcing._
import ch.epfl.bluebrain.nexus.delta.sourcing.config.AggregateConfig
import ch.epfl.bluebrain.nexus.delta.sourcing.processor.EventSourceProcessor.persistenceId
import ch.epfl.bluebrain.nexus.delta.sourcing.processor.ShardedAggregate
import ch.epfl.bluebrain.nexus.delta.sourcing.projections.stream.DaemonStreamCoordinator
import com.typesafe.scalalogging.Logger
import fs2.Stream
import io.circe.Json
import monix.bio.{IO, Task, UIO}
import monix.execution.Scheduler

final class ResolversImpl private (
    agg: ResolversAggregate,
    eventLog: EventLog[Envelope[ResolverEvent]],
    index: ResolversCache,
    orgs: Organizations,
    projects: Projects,
    sourceDecoder: JsonLdSourceResolvingDecoder[ResolverRejection, ResolverValue]
) extends Resolvers {

  override def create(
      projectRef: ProjectRef,
      source: Json
  )(implicit caller: Caller): IO[ResolverRejection, ResolverResource] = {
    for {
      p                    <- projects.fetchProject(projectRef, Set(NotDeprecated, VerifyQuotaResources))
      (iri, resolverValue) <- sourceDecoder(p, source)
      res                  <- eval(CreateResolver(iri, projectRef, resolverValue, source, caller), p)
    } yield res
  }.named("createResolver", moduleType)

  override def create(
      id: IdSegment,
      projectRef: ProjectRef,
      source: Json
  )(implicit caller: Caller): IO[ResolverRejection, ResolverResource] = {
    for {
      p             <- projects.fetchProject(projectRef, Set(NotDeprecated, VerifyQuotaResources))
      iri           <- expandIri(id, p)
      resolverValue <- sourceDecoder(p, iri, source)
      res           <- eval(CreateResolver(iri, projectRef, resolverValue, source, caller), p)
    } yield res
  }.named("createResolver", moduleType)

  override def create(
      id: IdSegment,
      projectRef: ProjectRef,
      resolverValue: ResolverValue
  )(implicit caller: Caller): IO[ResolverRejection, ResolverResource] = {
    for {
      p     <- projects.fetchProject(projectRef, Set(NotDeprecated, VerifyQuotaResources))
      iri   <- expandIri(id, p)
      source = ResolverValue.generateSource(iri, resolverValue)
      res   <- eval(CreateResolver(iri, projectRef, resolverValue, source, caller), p)
    } yield res
  }.named("createResolver", moduleType)

  override def update(
      id: IdSegment,
      projectRef: ProjectRef,
      rev: Long,
      source: Json
  )(implicit caller: Caller): IO[ResolverRejection, ResolverResource] = {
    for {
      p             <- projects.fetchProject(projectRef, Set(NotDeprecated))
      iri           <- expandIri(id, p)
      resolverValue <- sourceDecoder(p, iri, source)
      res           <- eval(UpdateResolver(iri, projectRef, resolverValue, source, rev, caller), p)
    } yield res
  }.named("updateResolver", moduleType)

  override def update(
      id: IdSegment,
      projectRef: ProjectRef,
      rev: Long,
      resolverValue: ResolverValue
  )(implicit
      caller: Caller
  ): IO[ResolverRejection, ResolverResource] = {
    for {
      p     <- projects.fetchProject(projectRef, Set(NotDeprecated))
      iri   <- expandIri(id, p)
      source = ResolverValue.generateSource(iri, resolverValue)
      res   <- eval(UpdateResolver(iri, projectRef, resolverValue, source, rev, caller), p)
    } yield res
  }.named("updateResolver", moduleType)

  override def tag(
      id: IdSegment,
      projectRef: ProjectRef,
      tag: TagLabel,
      tagRev: Long,
      rev: Long
  )(implicit
      subject: Identity.Subject
  ): IO[ResolverRejection, ResolverResource] = {
    for {
      p   <- projects.fetchProject(projectRef, Set(NotDeprecated))
      iri <- expandIri(id, p)
      res <- eval(TagResolver(iri, projectRef, tagRev, tag, rev, subject), p)
    } yield res
  }.named("tagResolver", moduleType)

  override def deprecate(
      id: IdSegment,
      projectRef: ProjectRef,
      rev: Long
  )(implicit subject: Identity.Subject): IO[ResolverRejection, ResolverResource] = {
    for {
      p   <- projects.fetchProject(projectRef, Set(NotDeprecated))
      iri <- expandIri(id, p)
      res <- eval(DeprecateResolver(iri, projectRef, rev, subject), p)
    } yield res
  }.named("deprecateResolver", moduleType)

  override def fetch(id: IdSegmentRef, projectRef: ProjectRef): IO[ResolverRejection, ResolverResource] =
    id.asTag
      .fold(
        for {
          project <- projects.fetchProject(projectRef)
          iri     <- expandIri(id.value, project)
          state   <- id.asRev.fold(currentState(projectRef, iri))(id => stateAt(projectRef, iri, id.rev))
          res     <- IO.fromOption(state.toResource(project.apiMappings, project.base), ResolverNotFound(iri, projectRef))
        } yield res
      )(fetchBy(_, projectRef))
      .named("fetchResolver", moduleType)

  def list(
      pagination: FromPagination,
      params: ResolverSearchParams,
      ordering: Ordering[ResolverResource]
  ): UIO[UnscoredSearchResults[ResolverResource]] =
    params.project
      .fold(index.values)(index.get)
      .map { resources =>
        val results = resources.filter(params.matches).sorted(ordering)
        UnscoredSearchResults(
          results.size.toLong,
          results.map(UnscoredResultEntry(_)).slice(pagination.from, pagination.from + pagination.size)
        )
      }
      .named("listResolvers", moduleType)

  override def events(
      projectRef: ProjectRef,
      offset: Offset
  ): IO[ResolverRejection, Stream[Task, Envelope[ResolverEvent]]] =
    eventLog.projectEvents(projects, projectRef, offset)

  override def events(
      organization: Label,
      offset: Offset
  ): IO[WrappedOrganizationRejection, Stream[Task, Envelope[ResolverEvent]]] =
    eventLog.orgEvents(orgs, organization, offset)

  override def events(offset: Offset): Stream[Task, Envelope[ResolverEvent]] =
    eventLog.eventsByTag(moduleType, offset)

  private def currentState(projectRef: ProjectRef, iri: Iri): IO[ResolverRejection, ResolverState] =
    agg.state(identifier(projectRef, iri))

  private def stateAt(projectRef: ProjectRef, iri: Iri, rev: Long) =
    eventLog
      .fetchStateAt(persistenceId(moduleType, identifier(projectRef, iri)), rev, Initial, Resolvers.next)
      .mapError(RevisionNotFound(rev, _))

  private def eval(cmd: ResolverCommand, project: Project): IO[ResolverRejection, ResolverResource] =
    for {
      evaluationResult <- agg.evaluate(identifier(cmd.project, cmd.id), cmd).mapError(_.value)
      (am, base)        = project.apiMappings -> project.base
      res              <- IO.fromOption(evaluationResult.state.toResource(am, base), UnexpectedInitialState(cmd.id, project.ref))
      _                <- index.put(cmd.project, cmd.id, res)
    } yield res

  private def identifier(projectRef: ProjectRef, id: Iri): String =
    s"${projectRef}_$id"
}

object ResolversImpl {

  type ResolversAggregate = Aggregate[String, ResolverState, ResolverCommand, ResolverEvent, ResolverRejection]
  type ResolversCache     = CompositeKeyValueStore[ProjectRef, Iri, ResolverResource]

  private val logger: Logger = Logger[ResolversImpl]

  /**
    * Creates a new resolvers cache.
    */
  private def cache(config: ResolversConfig)(implicit as: ActorSystem[Nothing]): ResolversCache = {
    implicit val cfg: KeyValueStoreConfig       = config.keyValueStore
    val clock: (Long, ResolverResource) => Long = (_, resource) => resource.rev
    CompositeKeyValueStore(moduleType, clock)
  }

  private def startIndexing(
      config: ResolversConfig,
      eventLog: EventLog[Envelope[ResolverEvent]],
      index: ResolversCache,
      resolvers: Resolvers
  )(implicit uuidF: UUIDF, as: ActorSystem[Nothing], sc: Scheduler) =
    DaemonStreamCoordinator.run(
      "ResolverIndex",
      stream = eventLog
        .eventsByTag(moduleType, Offset.noOffset)
        .mapAsync(config.cacheIndexing.concurrency)(envelope =>
          resolvers
            .fetch(envelope.event.id, envelope.event.project)
            .redeemCauseWith(_ => IO.unit, res => index.put(res.value.project, res.value.id, res))
        ),
      retryStrategy = RetryStrategy.retryOnNonFatal(config.cacheIndexing.retry, logger, "resolvers indexing")
    )

  private def findResolver(index: ResolversCache)(project: ProjectRef, params: ResolverSearchParams): UIO[Option[Iri]] =
    index.find(project, params.matches).map(_.map(_.id))

  private def aggregate(
      config: AggregateConfig,
      findResolver: FindResolver,
      idAvailability: IdAvailability[ResourceAlreadyExists]
  )(implicit as: ActorSystem[Nothing], clock: Clock[UIO]) = {
    val definition = PersistentEventDefinition(
      entityType = moduleType,
      initialState = Initial,
      next = Resolvers.next,
      evaluate = Resolvers.evaluate(findResolver, idAvailability),
      tagger = EventTags.forProjectScopedEvent(moduleType),
      snapshotStrategy = config.snapshotStrategy.strategy,
      stopStrategy = config.stopStrategy.persistentStrategy
    )

    ShardedAggregate.persistentSharded(
      definition = definition,
      config = config.processor
    )
  }

  /**
    * Constructs a Resolver instance
    *
    * @param config            the resolvers configuration
    * @param eventLog          the event log for ResolverEvent
    * @param orgs              an Organizations instance
    * @param projects          a Projects instance
    * @param contextResolution the context resolver
    * @param resourceIdCheck   to check whether an id already exists on another module upon creation
    */
  final def apply(
      config: ResolversConfig,
      eventLog: EventLog[Envelope[ResolverEvent]],
      orgs: Organizations,
      projects: Projects,
      contextResolution: ResolverContextResolution,
      resourceIdCheck: ResourceIdCheck
  )(implicit
      uuidF: UUIDF,
      clock: Clock[UIO],
      scheduler: Scheduler,
      as: ActorSystem[Nothing]
  ): Task[Resolvers] =
    apply(
      config,
      eventLog,
      orgs,
      projects,
      contextResolution,
      (project, id) => resourceIdCheck.isAvailableOr(project, id)(ResourceAlreadyExists(id, project))
    )

  private[resolvers] def apply(
      config: ResolversConfig,
      eventLog: EventLog[Envelope[ResolverEvent]],
      orgs: Organizations,
      projects: Projects,
      contextResolution: ResolverContextResolution,
      idAvailability: IdAvailability[ResourceAlreadyExists]
  )(implicit
      uuidF: UUIDF,
      clock: Clock[UIO],
      scheduler: Scheduler,
      as: ActorSystem[Nothing]
  ): Task[Resolvers] = {
    for {
      index        <- UIO.delay(cache(config))
      agg          <- aggregate(config.aggregate, findResolver(index), idAvailability)
      sourceDecoder =
        new JsonLdSourceResolvingDecoder[ResolverRejection, ResolverValue](contexts.resolvers, contextResolution, uuidF)
      resolvers     = new ResolversImpl(agg, eventLog, index, orgs, projects, sourceDecoder)
      _            <- startIndexing(config, eventLog, index, resolvers)
    } yield resolvers
  }

}
