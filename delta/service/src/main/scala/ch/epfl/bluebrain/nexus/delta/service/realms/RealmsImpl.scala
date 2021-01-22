package ch.epfl.bluebrain.nexus.delta.service.realms

import akka.actor.typed.ActorSystem
import akka.http.scaladsl.model.Uri
import akka.persistence.query.{NoOffset, Offset}
import cats.effect.Clock
import ch.epfl.bluebrain.nexus.delta.kernel.RetryStrategy
import ch.epfl.bluebrain.nexus.delta.sdk.Realms.moduleType
import ch.epfl.bluebrain.nexus.delta.sdk.cache.{KeyValueStore, KeyValueStoreConfig}
import ch.epfl.bluebrain.nexus.delta.sdk.model._
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Identity.Subject
import ch.epfl.bluebrain.nexus.delta.sdk.model.realms.RealmCommand.{CreateRealm, DeprecateRealm, UpdateRealm}
import ch.epfl.bluebrain.nexus.delta.sdk.model.realms.RealmRejection.{RealmNotFound, RevisionNotFound, UnexpectedInitialState}
import ch.epfl.bluebrain.nexus.delta.sdk.model.realms.RealmState.Initial
import ch.epfl.bluebrain.nexus.delta.sdk.model.realms._
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.ResultEntry.UnscoredResultEntry
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.SearchResults.UnscoredSearchResults
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.{Pagination, SearchParams, SearchResults}
import ch.epfl.bluebrain.nexus.delta.sdk.{RealmResource, Realms}
import ch.epfl.bluebrain.nexus.delta.service.realms.RealmsImpl._
import ch.epfl.bluebrain.nexus.delta.service.syntax._
import ch.epfl.bluebrain.nexus.sourcing._
import ch.epfl.bluebrain.nexus.sourcing.processor.EventSourceProcessor.persistenceId
import ch.epfl.bluebrain.nexus.sourcing.processor.ShardedAggregate
import ch.epfl.bluebrain.nexus.sourcing.projections.StreamSupervisor
import com.typesafe.scalalogging.Logger
import fs2.Stream
import monix.bio.{IO, Task, UIO}
import monix.execution.Scheduler

final class RealmsImpl private (
    agg: RealmsAggregate,
    eventLog: EventLog[Envelope[RealmEvent]],
    index: RealmsCache
) extends Realms {

  override def create(
      label: Label,
      name: Name,
      openIdConfig: Uri,
      logo: Option[Uri]
  )(implicit caller: Subject): IO[RealmRejection, RealmResource] = {
    val command = CreateRealm(label, name, openIdConfig, logo, caller)
    eval(command).named("createRealm", moduleType)
  }

  override def update(
      label: Label,
      rev: Long,
      name: Name,
      openIdConfig: Uri,
      logo: Option[Uri]
  )(implicit caller: Subject): IO[RealmRejection, RealmResource] = {
    val command = UpdateRealm(label, rev, name, openIdConfig, logo, caller)
    eval(command).named("updateRealm", moduleType)
  }

  override def deprecate(label: Label, rev: Long)(implicit caller: Subject): IO[RealmRejection, RealmResource] =
    eval(DeprecateRealm(label, rev, caller)).named("deprecateRealm", moduleType)

  private def eval(cmd: RealmCommand): IO[RealmRejection, RealmResource] =
    for {
      evaluationResult <- agg.evaluate(cmd.label.value, cmd).mapError(_.value)
      resource         <- IO.fromOption(evaluationResult.state.toResource, UnexpectedInitialState(cmd.label))
      _                <- index.put(cmd.label, resource)
    } yield resource

  override def fetch(label: Label): IO[RealmNotFound, RealmResource] =
    agg
      .state(label.value)
      .map(_.toResource)
      .flatMap(IO.fromOption(_, RealmNotFound(label)))
      .named("fetchRealm", moduleType)

  override def fetchAt(label: Label, rev: Long): IO[RealmRejection.NotFound, RealmResource] =
    eventLog
      .fetchStateAt(persistenceId(moduleType, label.value), rev, Initial, Realms.next)
      .bimap(RevisionNotFound(rev, _), _.toResource)
      .flatMap(IO.fromOption(_, RealmNotFound(label)))
      .named("fetchRealmAt", moduleType)

  override def list(
      pagination: Pagination.FromPagination,
      params: SearchParams.RealmSearchParams,
      ordering: Ordering[RealmResource]
  ): UIO[SearchResults.UnscoredSearchResults[RealmResource]] =
    index.values
      .map { resources =>
        val results = resources.filter(params.matches).sorted(ordering)
        UnscoredSearchResults(
          results.size.toLong,
          results.map(UnscoredResultEntry(_)).slice(pagination.from, pagination.from + pagination.size)
        )
      }
      .named("listRealms", moduleType)

  override def events(offset: Offset = NoOffset): Stream[Task, Envelope[RealmEvent]] =
    eventLog.eventsByTag(moduleType, offset)

  override def currentEvents(offset: Offset): Stream[Task, Envelope[RealmEvent]] =
    eventLog.currentEventsByTag(moduleType, offset)

}

object RealmsImpl {

  type RealmsAggregate = Aggregate[String, RealmState, RealmCommand, RealmEvent, RealmRejection]

  type RealmsCache = KeyValueStore[Label, RealmResource]

  private val logger: Logger = Logger[RealmsImpl]

  private def index(realmsConfig: RealmsConfig)(implicit as: ActorSystem[Nothing]): RealmsCache = {
    implicit val cfg: KeyValueStoreConfig    = realmsConfig.keyValueStore
    val clock: (Long, RealmResource) => Long = (_, resource) => resource.rev
    KeyValueStore.distributed(moduleType, clock)
  }

  private def startIndexing(
      config: RealmsConfig,
      eventLog: EventLog[Envelope[RealmEvent]],
      index: RealmsCache,
      realms: Realms
  )(implicit as: ActorSystem[Nothing], sc: Scheduler) =
    StreamSupervisor.runAsSingleton(
      "RealmsIndex",
      streamTask = Task.delay(
        eventLog
          .eventsByTag(moduleType, Offset.noOffset)
          .mapAsync(config.indexing.concurrency)(envelope =>
            realms.fetch(envelope.event.label).redeemCauseWith(_ => IO.unit, res => index.put(res.value.label, res))
          )
      ),
      retryStrategy = RetryStrategy(
        config.indexing.retry,
        _ => true,
        RetryStrategy.logError(logger, "realms indexing")
      )
    )

  private def aggregate(
      resolveWellKnown: Uri => IO[RealmRejection, WellKnown],
      existingRealms: UIO[Set[RealmResource]],
      realmsConfig: RealmsConfig
  )(implicit as: ActorSystem[Nothing], clock: Clock[UIO]): UIO[RealmsAggregate] = {
    val definition = PersistentEventDefinition(
      entityType = moduleType,
      initialState = RealmState.Initial,
      next = Realms.next,
      evaluate = Realms.evaluate(resolveWellKnown, existingRealms),
      tagger = (_: RealmEvent) => Set(moduleType),
      snapshotStrategy = realmsConfig.aggregate.snapshotStrategy.strategy,
      stopStrategy = realmsConfig.aggregate.stopStrategy.persistentStrategy
    )

    ShardedAggregate.persistentSharded(
      definition = definition,
      config = realmsConfig.aggregate.processor,
      retryStrategy = RetryStrategy.alwaysGiveUp
      // TODO: configure the number of shards
    )
  }

  private def apply(
      agg: RealmsAggregate,
      eventLog: EventLog[Envelope[RealmEvent]],
      index: RealmsCache
  ): RealmsImpl =
    new RealmsImpl(agg, eventLog, index)

  /**
    * Constructs a [[Realms]] instance
    *
    * @param realmsConfig     the realm configuration
    * @param resolveWellKnown how to resolve the [[WellKnown]]
    * @param eventLog         the event log for [[RealmEvent]]
    */
  final def apply(
      realmsConfig: RealmsConfig,
      resolveWellKnown: Uri => IO[RealmRejection, WellKnown],
      eventLog: EventLog[Envelope[RealmEvent]]
  )(implicit as: ActorSystem[Nothing], sc: Scheduler, clock: Clock[UIO]): UIO[Realms] =
    for {
      i     <- UIO.delay(index(realmsConfig))
      agg   <- aggregate(resolveWellKnown, i.valuesSet, realmsConfig)
      realms = apply(agg, eventLog, i)
      _     <- UIO.delay(startIndexing(realmsConfig, eventLog, i, realms))
    } yield realms

}
