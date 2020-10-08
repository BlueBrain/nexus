package ch.epfl.bluebrain.nexus.delta.service.realms

import akka.actor.typed.ActorSystem
import akka.http.scaladsl.model.Uri
import cats.effect.Clock
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Identity
import ch.epfl.bluebrain.nexus.delta.sdk.model.realms.RealmCommand.{CreateRealm, DeprecateRealm, UpdateRealm}
import ch.epfl.bluebrain.nexus.delta.sdk.model.realms.RealmRejection.{RevisionNotFound, UnexpectedInitialState}
import ch.epfl.bluebrain.nexus.delta.sdk.model.realms._
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.ResultEntry.UnscoredResultEntry
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.SearchResults.UnscoredSearchResults
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.{Pagination, SearchParams, SearchResults}
import ch.epfl.bluebrain.nexus.delta.sdk.model.{Label, Name, ResourceF}
import ch.epfl.bluebrain.nexus.delta.sdk.{BaseUri, RealmResource, Realms}
import ch.epfl.bluebrain.nexus.delta.service.cache.{KeyValueStore, KeyValueStoreConfig}
import ch.epfl.bluebrain.nexus.delta.service.realms.RealmsImpl.{RealmsAggregate, RealmsCache}
import ch.epfl.bluebrain.nexus.sourcing._
import ch.epfl.bluebrain.nexus.sourcing.processor.ShardedAggregate
import monix.bio.{IO, UIO}

final class RealmsImpl private (agg: RealmsAggregate, index: RealmsCache)(implicit base: BaseUri) extends Realms {

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
    agg
      .stateAt(
        label.value,
        rev,
        RealmState.Initial,
        (e: RealmEvent) => e.rev,
        (s: RealmState) => s.rev,
        (provided: Long, current: Long) => RevisionNotFound(provided, current)
      )
      .map {
        _.toResource
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
      case ResourceF(_, rev, types, deprecated, _, createdBy, _, updatedBy, _, _) =>
        params.createdBy.forall(_.id == createdBy.id) &&
          params.updatedBy.forall(_.id == updatedBy.id) &&
          params.rev.forall(_ == rev) &&
          params.types.subsetOf(types) &&
          params.deprecated.forall {
            _ == deprecated
          }
    }
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
  def index(realmsConfig: RealmsConfig)(implicit as: ActorSystem[Nothing]): RealmsCache = {
    implicit val cfg: KeyValueStoreConfig    = realmsConfig.keyValueStore
    val clock: (Long, RealmResource) => Long = (_, resource) => resource.rev
    KeyValueStore.distributed("realms", clock)
  }

  final def aggregate(
      resolveWellKnown: Uri => IO[RealmRejection, WellKnown],
      existingRealms: UIO[Set[RealmResource]],
      eventLog: EventLog[RealmEvent],
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
      eventLog = eventLog,
      definition = definition,
      config = realmsConfig.aggregate,
      retryStrategy = RetryStrategy.alwaysGiveUp
      // TODO: configure the number of shards
    )
  }

  def apply(agg: RealmsAggregate, index: RealmsCache)(implicit base: BaseUri) =
    new RealmsImpl(agg, index)

  def apply(
      realmsConfig: RealmsConfig,
      resolveWellKnown: Uri => IO[RealmRejection, WellKnown],
      eventLog: EventLog[RealmEvent]
  )(implicit base: BaseUri, as: ActorSystem[Nothing], clock: Clock[UIO]): UIO[Realms] = {
    val i = index(realmsConfig)
    aggregate(
      resolveWellKnown,
      i.values,
      eventLog,
      realmsConfig
    ).map(apply(_, i))
  }

}
