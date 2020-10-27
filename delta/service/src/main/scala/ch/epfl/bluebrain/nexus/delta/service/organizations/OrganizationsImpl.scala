package ch.epfl.bluebrain.nexus.delta.service.organizations

import java.util.UUID

import akka.actor.typed.ActorSystem
import akka.persistence.query.Offset
import cats.effect.Clock
import ch.epfl.bluebrain.nexus.delta.sdk.model._
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Identity.Subject
import ch.epfl.bluebrain.nexus.delta.sdk.model.organizations.OrganizationCommand._
import ch.epfl.bluebrain.nexus.delta.sdk.model.organizations.OrganizationRejection.{RevisionNotFound, UnexpectedInitialState}
import ch.epfl.bluebrain.nexus.delta.sdk.model.organizations.{OrganizationEvent, OrganizationState, _}
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.ResultEntry.UnscoredResultEntry
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.SearchResults.UnscoredSearchResults
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.{Pagination, SearchParams, SearchResults}
import ch.epfl.bluebrain.nexus.delta.sdk.syntax._
import ch.epfl.bluebrain.nexus.delta.sdk.utils.UUIDF
import ch.epfl.bluebrain.nexus.delta.sdk.{OrganizationResource, Organizations}
import ch.epfl.bluebrain.nexus.delta.service.cache.{KeyValueStore, KeyValueStoreConfig}
import ch.epfl.bluebrain.nexus.delta.service.organizations.OrganizationsImpl._
import ch.epfl.bluebrain.nexus.sourcing._
import ch.epfl.bluebrain.nexus.sourcing.processor.ShardedAggregate
import ch.epfl.bluebrain.nexus.sourcing.projections.StreamSupervisor
import com.typesafe.scalalogging.Logger
import monix.bio.{IO, Task, UIO}
import monix.execution.Scheduler

final class OrganizationsImpl private (
    agg: OrganizationsAggregate,
    eventLog: EventLog[Envelope[OrganizationEvent]],
    cache: OrganizationsCache
) extends Organizations {

  private val component: String = "organizations"

  override def create(
      label: Label,
      description: Option[String]
  )(implicit caller: Subject): IO[OrganizationRejection, OrganizationResource] =
    eval(CreateOrganization(label, description, caller)).named("createOrganization", component)

  override def update(
      label: Label,
      description: Option[String],
      rev: Long
  )(implicit caller: Subject): IO[OrganizationRejection, OrganizationResource] =
    eval(UpdateOrganization(label, rev, description, caller)).named("updateOrganization", component)

  override def deprecate(
      label: Label,
      rev: Long
  )(implicit caller: Subject): IO[OrganizationRejection, OrganizationResource] =
    eval(DeprecateOrganization(label, rev, caller)).named("deprecateOrganization", component)

  override def fetch(label: Label): UIO[Option[OrganizationResource]] =
    agg.state(label.value).map(_.toResource).named("fetchOrganization", component)

  override def fetchAt(label: Label, rev: Long): IO[RevisionNotFound, Option[OrganizationResource]] =
    if (rev == 0L) UIO.pure(None).named("fetchOrganizationAt", component)
    else {
      eventLog
        .currentEventsByPersistenceId(s"$entityType-$label", Long.MinValue, Long.MaxValue)
        .takeWhile(_.event.rev <= rev)
        .fold[OrganizationState](OrganizationState.Initial) { case (state, event) =>
          Organizations.next(state, event.event)
        }
        .compile
        .last
        .hideErrors
        .flatMap {
          case Some(state) if state.rev == rev => UIO.pure(state.toResource)
          case Some(_)                         =>
            fetch(label).flatMap {
              case Some(res) => IO.raiseError(RevisionNotFound(rev, res.rev))
              case None      => IO.pure(None)
            }
          case None                            => IO.raiseError(RevisionNotFound(rev, 0L))
        }
        .named("fetchOrganizationAt", component)
    }

  override def fetch(uuid: UUID): UIO[Option[OrganizationResource]] =
    fetchFromCache(uuid)
      .flatMap {
        case Some(label) => fetch(label)
        case None        => UIO.pure(None)
      }
      .named("fetchOrganizationByUuid", component)

  override def fetchAt(uuid: UUID, rev: Long): IO[RevisionNotFound, Option[OrganizationResource]] =
    fetchFromCache(uuid)
      .flatMap {
        case Some(label) => fetchAt(label, rev)
        case None        => UIO.pure(None)
      }
      .named("fetchOrganizationAtByUuid", component)

  private def fetchFromCache(uuid: UUID): UIO[Option[Label]]                                  =
    cache.collectFirst { case (label, resource) if resource.value.uuid == uuid => label }

  private def eval(cmd: OrganizationCommand): IO[OrganizationRejection, OrganizationResource] =
    for {
      evaluationResult <- agg.evaluate(cmd.label.value, cmd).mapError(_.value)
      resource         <- IO.fromOption(evaluationResult.state.toResource, UnexpectedInitialState(cmd.label))
      _                <- cache.put(cmd.label, resource)
    } yield resource

  override def list(
      pagination: Pagination.FromPagination,
      params: SearchParams.OrganizationSearchParams
  ): UIO[SearchResults.UnscoredSearchResults[OrganizationResource]] =
    cache.values
      .map { resources =>
        val results = resources.filter(params.matches).toVector.sortBy(_.createdAt)
        UnscoredSearchResults(
          results.size.toLong,
          results.map(UnscoredResultEntry(_)).slice(pagination.from, pagination.from + pagination.size)
        )
      }
      .named("listOrganizations", component)

  override def events(offset: Offset): fs2.Stream[Task, Envelope[OrganizationEvent]] =
    eventLog.eventsByTag(entityType, offset)

  override def currentEvents(offset: Offset): fs2.Stream[Task, Envelope[OrganizationEvent]] =
    eventLog.currentEventsByTag(entityType, offset)

}

object OrganizationsImpl {

  type OrganizationsAggregate =
    Aggregate[String, OrganizationState, OrganizationCommand, OrganizationEvent, OrganizationRejection]

  type OrganizationsCache = KeyValueStore[Label, OrganizationResource]

  /**
    * The organizations entity type.
    */
  final val entityType: String = "organizations"

  /**
    * The organizations tag name.
    */
  final val organizationTag = "organization"

  private val logger: Logger = Logger[OrganizationsImpl]

  /**
    * Creates a new organization cache.
    */
  private def cache(config: OrganizationsConfig)(implicit as: ActorSystem[Nothing]): OrganizationsCache = {
    implicit val cfg: KeyValueStoreConfig           = config.keyValueStore
    val clock: (Long, OrganizationResource) => Long = (_, resource) => resource.rev
    KeyValueStore.distributed("organizations", clock)
  }

  private def startIndexing(
      config: OrganizationsConfig,
      eventLog: EventLog[Envelope[OrganizationEvent]],
      index: OrganizationsCache,
      organizations: Organizations
  )(implicit as: ActorSystem[Nothing], sc: Scheduler) =
    StreamSupervisor.runAsSingleton(
      "OrganizationsIndex",
      streamTask = Task.delay(
        eventLog
          .eventsByTag(organizationTag, Offset.noOffset)
          .mapAsync(config.indexing.concurrency)(envelope =>
            organizations.fetch(envelope.event.label).flatMap {
              case Some(org) => index.put(org.id, org)
              case None      => UIO.unit
            }
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
      entityType = entityType,
      initialState = OrganizationState.Initial,
      next = Organizations.next,
      evaluate = Organizations.evaluate,
      tagger = (_: OrganizationEvent) => Set(entityType),
      snapshotStrategy = SnapshotStrategy.SnapshotCombined(
        SnapshotStrategy.SnapshotPredicate((state: OrganizationState, _: OrganizationEvent, _: Long) =>
          state.deprecated
        ),
        SnapshotStrategy.SnapshotEvery(
          numberOfEvents = 500,
          keepNSnapshots = 1,
          deleteEventsOnSnapshot = false
        )
      )
    )

    ShardedAggregate.persistentSharded(
      definition = definition,
      config = config.aggregate,
      retryStrategy = RetryStrategy.alwaysGiveUp
      // TODO: configure the number of shards
    )
  }

  private def apply(
      agg: OrganizationsAggregate,
      eventLog: EventLog[Envelope[OrganizationEvent]],
      cache: OrganizationsCache
  ): OrganizationsImpl =
    new OrganizationsImpl(agg, eventLog, cache)

  /**
    * Constructs a [[Organizations]] instance.
    *
    * @param config   the organization configuration
    * @param eventLog the event log for [[OrganizationEvent]]
    */
  final def apply(
      config: OrganizationsConfig,
      eventLog: EventLog[Envelope[OrganizationEvent]]
  )(implicit
      uuidF: UUIDF = UUIDF.random,
      as: ActorSystem[Nothing],
      sc: Scheduler,
      clock: Clock[UIO]
  ): UIO[Organizations] =
    for {
      agg          <- aggregate(config)
      index         = cache(config)
      organizations = apply(agg, eventLog, index)
      _            <- UIO.delay(startIndexing(config, eventLog, index, organizations))
    } yield organizations
}
