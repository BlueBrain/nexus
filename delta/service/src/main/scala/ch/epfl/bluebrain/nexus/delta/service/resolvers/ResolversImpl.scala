package ch.epfl.bluebrain.nexus.delta.service.resolvers

import akka.actor.typed.ActorSystem
import akka.persistence.query.Offset
import cats.effect.Clock
import cats.implicits._
import ch.epfl.bluebrain.nexus.delta.kernel.RetryStrategy
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.contexts
import ch.epfl.bluebrain.nexus.delta.sdk.Resolvers._
import ch.epfl.bluebrain.nexus.delta.sdk._
import ch.epfl.bluebrain.nexus.delta.sdk.cache.{CompositeKeyValueStore, KeyValueStoreConfig}
import ch.epfl.bluebrain.nexus.delta.sdk.jsonld.JsonLdSourceProcessor.JsonLdSourceResolvingDecoder
import ch.epfl.bluebrain.nexus.delta.sdk.model.IdSegment.IriSegment
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.{Caller, Identity}
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.{Project, ProjectRef}
import ch.epfl.bluebrain.nexus.delta.sdk.model.resolvers.ResolverCommand.{CreateResolver, DeprecateResolver, TagResolver, UpdateResolver}
import ch.epfl.bluebrain.nexus.delta.sdk.model.resolvers.ResolverRejection._
import ch.epfl.bluebrain.nexus.delta.sdk.model.resolvers.ResolverState.{Current, Initial}
import ch.epfl.bluebrain.nexus.delta.sdk.model.resolvers._
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.Pagination.FromPagination
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.ResultEntry.UnscoredResultEntry
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.SearchParams.ResolverSearchParams
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.SearchResults.UnscoredSearchResults
import ch.epfl.bluebrain.nexus.delta.sdk.model.{Envelope, IdSegment, TagLabel}
import ch.epfl.bluebrain.nexus.delta.sdk.utils.UUIDF
import ch.epfl.bluebrain.nexus.delta.service.resolvers.ResolversImpl.{ResolversAggregate, ResolversCache}
import ch.epfl.bluebrain.nexus.delta.service.syntax._
import ch.epfl.bluebrain.nexus.sourcing._
import ch.epfl.bluebrain.nexus.sourcing.config.AggregateConfig
import ch.epfl.bluebrain.nexus.sourcing.processor.EventSourceProcessor.persistenceId
import ch.epfl.bluebrain.nexus.sourcing.processor.ShardedAggregate
import ch.epfl.bluebrain.nexus.sourcing.projections.StreamSupervisor
import com.typesafe.scalalogging.Logger
import io.circe.Json
import monix.bio.{IO, Task, UIO}
import monix.execution.Scheduler

final class ResolversImpl private (
    agg: ResolversAggregate,
    eventLog: EventLog[Envelope[ResolverEvent]],
    index: ResolversCache,
    projects: Projects,
    sourceDecoder: JsonLdSourceResolvingDecoder[ResolverRejection, ResolverValue]
) extends Resolvers {

  override def create(projectRef: ProjectRef, source: Json)(implicit
      caller: Caller
  ): IO[ResolverRejection, ResolverResource] = {
    for {
      p                    <- projects.fetchActiveProject(projectRef)
      (iri, resolverValue) <- sourceDecoder(p, source)
      res                  <- eval(CreateResolver(iri, projectRef, resolverValue, source, caller), p)
    } yield res
  }.named("createResolver", moduleType)

  override def create(id: IdSegment, projectRef: ProjectRef, source: Json)(implicit
      caller: Caller
  ): IO[ResolverRejection, ResolverResource] = {
    for {
      p             <- projects.fetchActiveProject(projectRef)
      iri           <- expandIri(id, p)
      resolverValue <- sourceDecoder(p, iri, source)
      res           <- eval(CreateResolver(iri, projectRef, resolverValue, source, caller), p)
    } yield res
  }.named("createResolver", moduleType)

  override def create(id: IdSegment, projectRef: ProjectRef, resolverValue: ResolverValue)(implicit
      caller: Caller
  ): IO[ResolverRejection, ResolverResource] = {
    for {
      p     <- projects.fetchActiveProject(projectRef)
      iri   <- expandIri(id, p)
      source = ResolverValue.generateSource(iri, resolverValue)
      res   <- eval(CreateResolver(iri, projectRef, resolverValue, source, caller), p)
    } yield res
  }.named("createResolver", moduleType)

  override def update(id: IdSegment, projectRef: ProjectRef, rev: Long, source: Json)(implicit
      caller: Caller
  ): IO[ResolverRejection, ResolverResource] = {
    for {
      p             <- projects.fetchActiveProject(projectRef)
      iri           <- expandIri(id, p)
      resolverValue <- sourceDecoder(p, iri, source)
      res           <- eval(UpdateResolver(iri, projectRef, resolverValue, source, rev, caller), p)
    } yield res
  }.named("updateResolver", moduleType)

  override def update(id: IdSegment, projectRef: ProjectRef, rev: Long, resolverValue: ResolverValue)(implicit
      caller: Caller
  ): IO[ResolverRejection, ResolverResource] = {
    for {
      p     <- projects.fetchActiveProject(projectRef)
      iri   <- expandIri(id, p)
      source = ResolverValue.generateSource(iri, resolverValue)
      res   <- eval(UpdateResolver(iri, projectRef, resolverValue, source, rev, caller), p)
    } yield res
  }.named("updateResolver", moduleType)

  override def tag(id: IdSegment, projectRef: ProjectRef, tag: TagLabel, tagRev: Long, rev: Long)(implicit
      subject: Identity.Subject
  ): IO[ResolverRejection, ResolverResource] = {
    for {
      p   <- projects.fetchActiveProject(projectRef)
      iri <- expandIri(id, p)
      res <- eval(TagResolver(iri, projectRef, tagRev, tag, rev, subject), p)
    } yield res
  }.named("tagResolver", moduleType)

  override def deprecate(id: IdSegment, projectRef: ProjectRef, rev: Long)(implicit
      subject: Identity.Subject
  ): IO[ResolverRejection, ResolverResource] = {
    for {
      p   <- projects.fetchActiveProject(projectRef)
      iri <- expandIri(id, p)
      res <- eval(DeprecateResolver(iri, projectRef, rev, subject), p)
    } yield res
  }.named("deprecateResolver", moduleType)

  override def fetch(id: IdSegment, projectRef: ProjectRef): IO[ResolverRejection, ResolverResource] =
    fetch(id, projectRef, None).named("fetchResolver", moduleType)

  override def fetchActiveResolver(id: Iri, projectRef: ProjectRef): IO[ResolverRejection, Resolver] =
    currentState(projectRef, id)
      .flatMap {
        case Initial                    => IO.raiseError(ResolverNotFound(id, projectRef))
        case c: Current if c.deprecated => IO.raiseError(ResolverIsDeprecated(id))
        case c: Current                 => IO.pure(c.resolver)
      }
      .named("fetchActiveResolver", moduleType)

  override def fetchAt(id: IdSegment, projectRef: ProjectRef, rev: Long): IO[ResolverRejection, ResolverResource] =
    fetch(id, projectRef, Some(rev)).named("fetchResolverAt", moduleType)

  private def fetch(id: IdSegment, projectRef: ProjectRef, rev: Option[Long]) =
    for {
      p     <- projects.fetchProject(projectRef)
      iri   <- expandIri(id, p)
      state <- rev.fold(currentState(projectRef, iri))(stateAt(projectRef, iri, _))
      res   <- IO.fromOption(state.toResource(p.apiMappings, p.base), ResolverNotFound(iri, projectRef))
    } yield res

  override def fetchBy(
      id: IdSegment,
      projectRef: ProjectRef,
      tag: TagLabel
  ): IO[ResolverRejection, ResolverResource] =
    super.fetchBy(id, projectRef, tag).named("fetchResolverBy", moduleType)

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

  override def events(offset: Offset): fs2.Stream[Task, Envelope[ResolverEvent]] =
    eventLog.eventsByTag(moduleType, offset)

  private def currentState(projectRef: ProjectRef, iri: Iri): IO[ResolverRejection, ResolverState] =
    agg.state(identifier(projectRef, iri))

  private def stateAt(projectRef: ProjectRef, iri: Iri, rev: Long) =
    eventLog
      .fetchStateAt(persistenceId(moduleType, identifier(projectRef, iri)), rev, Initial, Resolvers.next)
      .leftMap(RevisionNotFound(rev, _))

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
  )(implicit as: ActorSystem[Nothing], sc: Scheduler) =
    StreamSupervisor.runAsSingleton(
      "ResolverIndex",
      streamTask = Task.delay(
        eventLog
          .eventsByTag(moduleType, Offset.noOffset)
          .mapAsync(config.indexing.concurrency)(envelope =>
            resolvers
              .fetch(IriSegment(envelope.event.id), envelope.event.project)
              .redeemCauseWith(_ => IO.unit, res => index.put(res.value.project, res.value.id, res))
          )
      ),
      retryStrategy = RetryStrategy(
        config.indexing.retry,
        _ => true,
        RetryStrategy.logError(logger, "resolvers indexing")
      )
    )

  private def aggregate(config: AggregateConfig)(implicit as: ActorSystem[Nothing], clock: Clock[UIO]) = {
    val definition = PersistentEventDefinition(
      entityType = moduleType,
      initialState = Initial,
      next = Resolvers.next,
      evaluate = Resolvers.evaluate,
      tagger = (event: ResolverEvent) =>
        Set(
          moduleType,
          s"${Projects.moduleType}=${event.project}",
          s"${Organizations.moduleType}=${event.project.organization}"
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
    * Constructs a Resolver instance
    * @param config   the resolvers configuration
    * @param eventLog the event log for ResolverEvent
    * @param projects a Projects instance
    * @param contextResolution the context resolver
    */
  final def apply(
      config: ResolversConfig,
      eventLog: EventLog[Envelope[ResolverEvent]],
      projects: Projects,
      contextResolution: ResolverContextResolution
  )(implicit
      uuidF: UUIDF,
      clock: Clock[UIO],
      scheduler: Scheduler,
      as: ActorSystem[Nothing]
  ): UIO[Resolvers] = {
    for {
      agg          <- aggregate(config.aggregate)
      index        <- UIO.delay(cache(config))
      sourceDecoder =
        new JsonLdSourceResolvingDecoder[ResolverRejection, ResolverValue](contexts.resolvers, contextResolution, uuidF)
      resolvers     = new ResolversImpl(agg, eventLog, index, projects, sourceDecoder)
      _            <- UIO.delay(startIndexing(config, eventLog, index, resolvers))
    } yield resolvers
  }

}
