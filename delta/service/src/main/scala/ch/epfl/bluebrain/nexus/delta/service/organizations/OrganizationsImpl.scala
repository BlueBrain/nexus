package ch.epfl.bluebrain.nexus.delta.service.organizations

import java.util.UUID

import akka.actor.typed.ActorSystem
import akka.persistence.query.Offset
import cats.effect.Clock
import cats.implicits._
import ch.epfl.bluebrain.nexus.delta.kernel.RetryStrategy
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
import ch.epfl.bluebrain.nexus.delta.sdk.utils.UUIDF
import ch.epfl.bluebrain.nexus.delta.sdk.{OrganizationResource, Organizations}
import ch.epfl.bluebrain.nexus.delta.service.organizations.OrganizationsImpl._
import ch.epfl.bluebrain.nexus.delta.service.syntax._
import ch.epfl.bluebrain.nexus.delta.service.utils.ApplyOwnerPermissions
import ch.epfl.bluebrain.nexus.sourcing._
import ch.epfl.bluebrain.nexus.sourcing.processor.EventSourceProcessor.persistenceId
import ch.epfl.bluebrain.nexus.sourcing.processor.ShardedAggregate
import ch.epfl.bluebrain.nexus.sourcing.projections.StreamSupervisor
import com.typesafe.scalalogging.Logger
import monix.bio.{IO, Task, UIO}
import monix.execution.Scheduler

final class OrganizationsImpl private (
    agg: OrganizationsAggregate,
    eventLog: EventLog[Envelope[OrganizationEvent]],
    cache: OrganizationsCache,
    applyOwnerPermissions: ApplyOwnerPermissions
) extends Organizations {

  override def create(
      label: Label,
      description: Option[String]
  )(implicit caller: Subject): IO[OrganizationRejection, OrganizationResource] =
    eval(CreateOrganization(label, description, caller))
      .named("createOrganization", moduleType) <* applyOwnerPermissions
      .onOrganization(label, caller)
      .leftMap(OwnerPermissionsFailed(label, _))
      .named(
        "applyOwnerPermissions",
        moduleType
      )

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

  override def events(offset: Offset): fs2.Stream[Task, Envelope[OrganizationEvent]] =
    eventLog.eventsByTag(moduleType, offset)

  override def currentEvents(offset: Offset): fs2.Stream[Task, Envelope[OrganizationEvent]] =
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
      orgs: Organizations
  )(implicit as: ActorSystem[Nothing], sc: Scheduler) =
    StreamSupervisor.runAsSingleton(
      "OrganizationsIndex",
      streamTask = Task.delay(
        eventLog
          .eventsByTag(moduleType, Offset.noOffset)
          .mapAsync(config.indexing.concurrency)(envelope =>
            orgs.fetch(envelope.event.label).redeemCauseWith(_ => IO.unit, res => index.put(res.value.label, res))
          )
      ),
      retryStrategy = RetryStrategy(
        config.indexing.retry,
        _ => true,
        RetryStrategy.logError(logger, "organizations indexing")
      )
    )

  private def aggregate(
      config: OrganizationsConfig
  )(implicit as: ActorSystem[Nothing], clock: Clock[UIO], uuidF: UUIDF): UIO[OrganizationsAggregate] = {
    val definition = PersistentEventDefinition(
      entityType = moduleType,
      initialState = OrganizationState.Initial,
      next = Organizations.next,
      evaluate = Organizations.evaluate,
      tagger = (_: OrganizationEvent) => Set(moduleType),
      snapshotStrategy = config.aggregate.snapshotStrategy.strategy,
      stopStrategy = config.aggregate.stopStrategy.persistentStrategy
    )

    ShardedAggregate.persistentSharded(
      definition = definition,
      config = config.aggregate.processor,
      retryStrategy = RetryStrategy.alwaysGiveUp
      // TODO: configure the number of shards
    )
  }

  private def apply(
      agg: OrganizationsAggregate,
      eventLog: EventLog[Envelope[OrganizationEvent]],
      cache: OrganizationsCache,
      applyOwnerPermissions: ApplyOwnerPermissions
  ): OrganizationsImpl =
    new OrganizationsImpl(agg, eventLog, cache, applyOwnerPermissions)

  /**
    * Constructs a [[Organizations]] instance.
    *
    * @param config                the organization configuration
    * @param eventLog              the event log for [[OrganizationEvent]]
    * @param applyOwnerPermissions to apply owner permissions on organization creation
    */
  final def apply(
      config: OrganizationsConfig,
      eventLog: EventLog[Envelope[OrganizationEvent]],
      applyOwnerPermissions: ApplyOwnerPermissions
  )(implicit
      uuidF: UUIDF = UUIDF.random,
      as: ActorSystem[Nothing],
      sc: Scheduler,
      clock: Clock[UIO]
  ): UIO[Organizations] =
    for {
      agg          <- aggregate(config)
      index        <- UIO.delay(cache(config))
      organizations = apply(agg, eventLog, index, applyOwnerPermissions)
      _            <- UIO.delay(startIndexing(config, eventLog, index, organizations))
    } yield organizations
}
