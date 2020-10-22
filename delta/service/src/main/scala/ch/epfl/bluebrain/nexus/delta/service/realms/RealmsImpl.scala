package ch.epfl.bluebrain.nexus.delta.service.realms

import akka.actor.typed.ActorSystem
import akka.http.scaladsl.model.Uri
import akka.persistence.query.{NoOffset, Offset}
import cats.effect.Clock
import ch.epfl.bluebrain.nexus.delta.sdk.model._
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Identity
import ch.epfl.bluebrain.nexus.delta.sdk.model.realms.RealmCommand.{CreateRealm, DeprecateRealm, UpdateRealm}
import ch.epfl.bluebrain.nexus.delta.sdk.model.realms.RealmRejection.{RevisionNotFound, UnexpectedInitialState}
import ch.epfl.bluebrain.nexus.delta.sdk.model.realms._
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.ResultEntry.UnscoredResultEntry
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.SearchResults.UnscoredSearchResults
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.{Pagination, SearchParams, SearchResults}
import ch.epfl.bluebrain.nexus.delta.sdk.{RealmResource, Realms}
import ch.epfl.bluebrain.nexus.delta.service.cache.{KeyValueStore, KeyValueStoreConfig}
import ch.epfl.bluebrain.nexus.delta.service.realms.RealmsImpl.{entityType, RealmsAggregate, RealmsCache}
import ch.epfl.bluebrain.nexus.sourcing._
import ch.epfl.bluebrain.nexus.sourcing.processor.ShardedAggregate
import fs2.Stream
import monix.bio.{IO, Task, UIO}

final class RealmsImpl private (
    agg: RealmsAggregate,
    eventLog: EventLog[Envelope[RealmEvent]],
    index: RealmsCache
)(implicit base: BaseUri)
    extends Realms {

  override def create(label: Label, name: Name, openIdConfig: Uri, logo: Option[Uri])(implicit
      caller: Identity.Subject
  ): IO[RealmRejection, RealmResource] = {
    val command = CreateRealm(label, name, openIdConfig, logo, caller)
    eval(command)
  }

  override def update(label: Label, rev: Long, name: Name, openIdConfig: Uri, logo: Option[Uri])(implicit
      caller: Identity.Subject
  ): IO[RealmRejection, RealmResource] = {
    val command = UpdateRealm(label, rev, name, openIdConfig, logo, caller)
    eval(command)
  }

  override def deprecate(label: Label, rev: Long)(implicit
      caller: Identity.Subject
  ): IO[RealmRejection, RealmResource] =
    eval(DeprecateRealm(label, rev, caller))

  private def eval(cmd: RealmCommand): IO[RealmRejection, RealmResource] =
    for {
      evaluationResult <- agg.evaluate(cmd.label.value, cmd).mapError(_.value)
      resource         <- IO.fromOption(
                            evaluationResult.state.toResource,
                            UnexpectedInitialState(cmd.label)
                          )
      _                <- index.put(cmd.label, resource)
    } yield resource

  override def fetch(label: Label): UIO[Option[RealmResource]] =
    agg.state(label.value).map(_.toResource)

  override def fetchAt(label: Label, rev: Long): IO[RealmRejection.RevisionNotFound, Option[RealmResource]] =
    if (rev == 0L) UIO.pure(RealmState.Initial.toResource)
    else {
      eventLog
        .currentEventsByPersistenceId(s"realms-${label.value}", Long.MinValue, Long.MaxValue)
        .takeWhile(_.event.rev <= rev)
        .fold[RealmState](RealmState.Initial) {
          case (state, event) => Realms.next(state, event.event)
        }
        .compile
        .last
        .hideErrors
        .flatMap {
          case Some(state) if state.rev == rev => UIO.pure(state.toResource)
          case Some(_)                         => fetch(label).flatMap(res => IO.raiseError(RevisionNotFound(rev, res.fold(0L)(_.rev))))
          case None                            => IO.raiseError(RevisionNotFound(rev, 0L))
        }
    }

  override def list(
      pagination: Pagination.FromPagination,
      params: SearchParams.RealmSearchParams
  ): UIO[SearchResults.UnscoredSearchResults[RealmResource]] =
    index.values.map { set =>
      val results = filter(set, params).toList.sortBy(_.createdAt.toEpochMilli)
      UnscoredSearchResults(
        results.size.toLong,
        results.map(UnscoredResultEntry(_)).slice(pagination.from, pagination.from + pagination.size)
      )
    }

  private def filter(resources: Set[RealmResource], params: SearchParams.RealmSearchParams): Set[RealmResource] =
    resources.filter {
      case ResourceF(_, rev, types, deprecated, _, createdBy, _, updatedBy, _, realm) =>
        params.issuer.forall(_ == realm.issuer) &&
          params.createdBy.forall(_ == createdBy.id) &&
          params.updatedBy.forall(_ == updatedBy.id) &&
          params.rev.forall(_ == rev) &&
          params.types.subsetOf(types) &&
          params.deprecated.forall {
            _ == deprecated
          }
    }

  def events(offset: Offset = NoOffset): Stream[Task, Envelope[RealmEvent]] =
    eventLog.currentEventsByTag(entityType, offset)
}

object RealmsImpl {

  type RealmsAggregate = Aggregate[String, RealmState, RealmCommand, RealmEvent, RealmRejection]

  type RealmsCache = KeyValueStore[Label, RealmResource]

  /**
    * The realms entity type.
    */
  final val entityType: String = "realms"

  /**
    * Creates a new realm index.
    */
  private def index(realmsConfig: RealmsConfig)(implicit as: ActorSystem[Nothing]): RealmsCache = {
    implicit val cfg: KeyValueStoreConfig    = realmsConfig.keyValueStore
    val clock: (Long, RealmResource) => Long = (_, resource) => resource.rev
    KeyValueStore.distributed("realms", clock)
  }

  private def aggregate(
      resolveWellKnown: Uri => IO[RealmRejection, WellKnown],
      existingRealms: UIO[Set[RealmResource]],
      realmsConfig: RealmsConfig
  )(implicit as: ActorSystem[Nothing], clock: Clock[UIO]): UIO[RealmsAggregate] = {
    val definition = PersistentEventDefinition(
      entityType = entityType,
      initialState = RealmState.Initial,
      next = Realms.next,
      evaluate = Realms.evaluate(resolveWellKnown, existingRealms),
      tagger = (_: RealmEvent) => Set(entityType),
      snapshotStrategy = SnapshotStrategy.SnapshotCombined(
        SnapshotStrategy.SnapshotPredicate((state: RealmState, _: RealmEvent, _: Long) => state.deprecated),
        SnapshotStrategy.SnapshotEvery(
          numberOfEvents = 500,
          keepNSnapshots = 1,
          deleteEventsOnSnapshot = false
        )
      )
    )

    ShardedAggregate.persistentSharded(
      definition = definition,
      config = realmsConfig.aggregate,
      retryStrategy = RetryStrategy.alwaysGiveUp
      // TODO: configure the number of shards
    )
  }

  /**
    * Constructs a [[Realms]] instance
    * @param agg the sharded aggregate
    * @param eventLog the event log
    * @param index the index
    * @param base the base uri of the system api
    */
  final def apply(
      agg: RealmsAggregate,
      eventLog: EventLog[Envelope[RealmEvent]],
      index: RealmsCache
  )(implicit
      base: BaseUri
  ): RealmsImpl =
    new RealmsImpl(agg, eventLog, index)

  /**
    * Constructs a [[Realms]] instance
    * @param realmsConfig the realm configuration
    * @param resolveWellKnown how to resolve the [[WellKnown]]
    * @param eventLog the event log for [[RealmEvent]]
    * @param base the base uri of the system api
    * @return
    */
  final def apply(
      realmsConfig: RealmsConfig,
      resolveWellKnown: Uri => IO[RealmRejection, WellKnown],
      eventLog: EventLog[Envelope[RealmEvent]]
  )(implicit base: BaseUri, as: ActorSystem[Nothing], clock: Clock[UIO]): UIO[Realms] = {
    val i = index(realmsConfig)
    aggregate(
      resolveWellKnown,
      i.values,
      realmsConfig
    ).map(apply(_, eventLog, i))
  }

}
