package ch.epfl.bluebrain.nexus.delta.service.organizations

import akka.actor.typed.ActorSystem
import akka.persistence.query.Offset
import cats.effect.Clock
import ch.epfl.bluebrain.nexus.delta.kernel.RetryStrategy
import ch.epfl.bluebrain.nexus.delta.kernel.utils.UUIDF
import ch.epfl.bluebrain.nexus.delta.sdk.Organizations.moduleType
import ch.epfl.bluebrain.nexus.delta.sdk.cache.{KeyValueStore, KeyValueStoreConfig}
import ch.epfl.bluebrain.nexus.delta.sdk.model._
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Identity.Subject
import ch.epfl.bluebrain.nexus.delta.sdk.model.organizations.OrganizationCommand._
import ch.epfl.bluebrain.nexus.delta.sdk.model.organizations.OrganizationRejection._
import ch.epfl.bluebrain.nexus.delta.sdk.model.organizations.OrganizationState.Initial
import ch.epfl.bluebrain.nexus.delta.sdk.model.organizations._
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.ResultEntry.UnscoredResultEntry
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.SearchResults.UnscoredSearchResults
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.{Pagination, SearchParams, SearchResults}
import ch.epfl.bluebrain.nexus.delta.sdk.syntax._
import ch.epfl.bluebrain.nexus.delta.sdk.{EventTags, OrganizationResource, Organizations, ScopeInitialization}
import ch.epfl.bluebrain.nexus.delta.service.organizations.OrganizationsImpl._
import ch.epfl.bluebrain.nexus.delta.sourcing._
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Label
import ch.epfl.bluebrain.nexus.delta.sourcing.processor.EventSourceProcessor.persistenceId
import ch.epfl.bluebrain.nexus.delta.sourcing.processor.ShardedAggregate
import ch.epfl.bluebrain.nexus.delta.sourcing.projections.stream.DaemonStreamCoordinator
import com.typesafe.scalalogging.Logger
import fs2.Stream
import monix.bio.{IO, Task, UIO}
import monix.execution.Scheduler

import java.util.UUID

final class OrganizationsImpl private (
    agg: OrganizationsAggregate,
    eventLog: EventLog[Envelope[OrganizationEvent]],
    cache: OrganizationsCache,
    scopeInitializations: Set[ScopeInitialization]
) extends Organizations {

  override def create(
      label: Label,
      description: Option[String]
  )(implicit caller: Subject): IO[OrganizationRejection, OrganizationResource] =
    for {
      resource <- eval(CreateOrganization(label, description, caller)).named("createOrganization", moduleType)
      _        <- IO.parTraverseUnordered(scopeInitializations)(_.onOrganizationCreation(resource.value, caller))
                    .void
                    .mapError(OrganizationInitializationFailed)
                    .named("initializeOrganization", moduleType)
    } yield resource

  override def update(
      label: Label,
      description: Option[String],
      rev: Long
  )(implicit caller: Subject): IO[OrganizationRejection, OrganizationResource] =
    eval(UpdateOrganization(label, rev, description, caller)).named("updateOrganization", moduleType)

  override def deprecate(
      label: Label,
      rev: Long
  )(implicit caller: Subject): IO[OrganizationRejection, OrganizationResource] =
    eval(DeprecateOrganization(label, rev, caller)).named("deprecateOrganization", moduleType)

  override def fetch(label: Label): IO[OrganizationNotFound, OrganizationResource] =
    agg
      .state(label.value)
      .map(_.toResource)
      .flatMap(IO.fromOption(_, OrganizationNotFound(label)))
      .named("fetchOrganization", moduleType)

  override def fetchAt(label: Label, rev: Long): IO[OrganizationRejection.NotFound, OrganizationResource] =
    eventLog
      .fetchStateAt(persistenceId(moduleType, label.value), rev, Initial, Organizations.next)
      .bimap(RevisionNotFound(rev, _), _.toResource)
      .flatMap(IO.fromOption(_, OrganizationNotFound(label)))
      .named("fetchOrganizationAt", moduleType)

  override def fetch(uuid: UUID): IO[OrganizationNotFound, OrganizationResource] =
    fetchFromCache(uuid).flatMap(fetch).named("fetchOrganizationByUuid", moduleType)

  override def fetchAt(uuid: UUID, rev: Long): IO[OrganizationRejection.NotFound, OrganizationResource] =
    super.fetchAt(uuid, rev).named("fetchOrganizationAtByUuid", moduleType)

  private def fetchFromCache(uuid: UUID): IO[OrganizationNotFound, Label]                     =
    cache.collectFirstOr { case (label, resource) if resource.value.uuid == uuid => label }(OrganizationNotFound(uuid))

  private def eval(cmd: OrganizationCommand): IO[OrganizationRejection, OrganizationResource] =
    for {
      evaluationResult <- agg.evaluate(cmd.label.value, cmd).mapError(_.value)
      resource         <- IO.fromOption(evaluationResult.state.toResource, UnexpectedInitialState(cmd.label))
      _                <- cache.put(cmd.label, resource)
    } yield resource

  override def list(
      pagination: Pagination.FromPagination,
      params: SearchParams.OrganizationSearchParams,
      ordering: Ordering[OrganizationResource]
  ): UIO[SearchResults.UnscoredSearchResults[OrganizationResource]] =
    cache.values
      .map { resources =>
        val results = resources.filter(params.matches).sorted(ordering)
        UnscoredSearchResults(
          results.size.toLong,
          results.map(UnscoredResultEntry(_)).slice(pagination.from, pagination.from + pagination.size)
        )
      }
      .named("listOrganizations", moduleType)

  override def events(offset: Offset): Stream[Task, Envelope[OrganizationEvent]] =
    eventLog.eventsByTag(moduleType, offset)

  override def currentEvents(offset: Offset): Stream[Task, Envelope[OrganizationEvent]] =
    eventLog.currentEventsByTag(moduleType, offset)

}

object OrganizationsImpl {

  type OrganizationsAggregate =
    Aggregate[String, OrganizationState, OrganizationCommand, OrganizationEvent, OrganizationRejection]

  type OrganizationsCache = KeyValueStore[Label, OrganizationResource]

  private val logger: Logger = Logger[OrganizationsImpl]

  /**
    * Creates a new organization cache.
    */
  private def cache(config: OrganizationsConfig)(implicit as: ActorSystem[Nothing]): OrganizationsCache = {
    implicit val cfg: KeyValueStoreConfig           = config.keyValueStore
    val clock: (Long, OrganizationResource) => Long = (_, resource) => resource.rev
    KeyValueStore.distributed(moduleType, clock)
  }

  private def startIndexing(
      config: OrganizationsConfig,
      eventLog: EventLog[Envelope[OrganizationEvent]],
      index: OrganizationsCache,
      orgs: Organizations,
      si: Set[ScopeInitialization]
  )(implicit uuidF: UUIDF, as: ActorSystem[Nothing], sc: Scheduler) =
    DaemonStreamCoordinator.run(
      "OrganizationsIndex",
      stream = eventLog
        .eventsByTag(moduleType, Offset.noOffset)
        .mapAsync(config.cacheIndexing.concurrency) { envelope =>
          orgs
            .fetch(envelope.event.label)
            .redeemCauseWith(
              _ => IO.unit,
              { resource =>
                index.put(resource.value.label, resource) >>
                  IO.when(!resource.deprecated && envelope.event.isCreated) {
                    IO.parTraverseUnordered(si)(_.onOrganizationCreation(resource.value, resource.createdBy))
                      .attempt
                      .void
                  }
              }
            )
        },
      retryStrategy = RetryStrategy.retryOnNonFatal(config.cacheIndexing.retry, logger, "organizations indexing")
    )

  private def aggregate(
      config: OrganizationsConfig
  )(implicit as: ActorSystem[Nothing], clock: Clock[UIO], uuidF: UUIDF): UIO[OrganizationsAggregate] = {
    val definition = PersistentEventDefinition(
      entityType = moduleType,
      initialState = OrganizationState.Initial,
      next = Organizations.next,
      evaluate = Organizations.evaluate,
      tagger = EventTags.forOrganizationScopedEvent(moduleType),
      snapshotStrategy = config.aggregate.snapshotStrategy.strategy,
      stopStrategy = config.aggregate.stopStrategy.persistentStrategy
    )

    ShardedAggregate.persistentSharded(
      definition = definition,
      config = config.aggregate.processor
    )
  }

  private def apply(
      agg: OrganizationsAggregate,
      eventLog: EventLog[Envelope[OrganizationEvent]],
      cache: OrganizationsCache,
      scopeInitializations: Set[ScopeInitialization]
  ): OrganizationsImpl =
    new OrganizationsImpl(agg, eventLog, cache, scopeInitializations)

  /**
    * Constructs a [[Organizations]] instance.
    *
    * @param config
    *   the organization configuration
    * @param eventLog
    *   the event log for [[OrganizationEvent]]
    * @param scopeInitializations
    *   the collection of registered scope initializations
    */
  final def apply(
      config: OrganizationsConfig,
      eventLog: EventLog[Envelope[OrganizationEvent]],
      scopeInitializations: Set[ScopeInitialization]
  )(implicit
      uuidF: UUIDF = UUIDF.random,
      as: ActorSystem[Nothing],
      sc: Scheduler,
      clock: Clock[UIO]
  ): Task[Organizations] =
    for {
      agg          <- aggregate(config)
      index        <- UIO.delay(cache(config))
      organizations = apply(agg, eventLog, index, scopeInitializations)
      _            <- startIndexing(config, eventLog, index, organizations, scopeInitializations)
    } yield organizations
}
