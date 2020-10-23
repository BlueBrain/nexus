package ch.epfl.bluebrain.nexus.delta.service.acls

import akka.actor.typed.ActorSystem
import akka.persistence.query.Offset
import cats.effect.Clock
import ch.epfl.bluebrain.nexus.delta.sdk.model.Envelope
import ch.epfl.bluebrain.nexus.delta.sdk.model.acls.AclCommand._
import ch.epfl.bluebrain.nexus.delta.sdk.model.acls.AclRejection._
import ch.epfl.bluebrain.nexus.delta.sdk.model.acls._
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.{Caller, Identity}
import ch.epfl.bluebrain.nexus.delta.sdk.syntax._
import ch.epfl.bluebrain.nexus.delta.sdk.{AclResource, Acls, Permissions}
import ch.epfl.bluebrain.nexus.delta.service.acls.AclsImpl.{entityType, AclsAggregate, AclsCache}
import ch.epfl.bluebrain.nexus.delta.service.cache.{KeyValueStore, KeyValueStoreConfig}
import ch.epfl.bluebrain.nexus.sourcing._
import ch.epfl.bluebrain.nexus.sourcing.processor._
import ch.epfl.bluebrain.nexus.sourcing.projections.StreamSupervisor
import com.typesafe.scalalogging.Logger
import monix.bio.{IO, Task, UIO}
import monix.execution.Scheduler

final class AclsImpl private (agg: AclsAggregate, eventLog: EventLog[Envelope[AclEvent]], index: AclsCache)
    extends Acls {

  private val component: String = "acls"

  override def fetch(address: AclAddress): UIO[Option[AclResource]] =
    agg.state(address.string).map(_.toResource).named("fetchAcl", component)

  override def fetchAt(address: AclAddress, rev: Long): IO[AclRejection.RevisionNotFound, Option[AclResource]] =
    if (rev == 0L) UIO.pure(None).named("fetchAclsAt", component, Map("rev" -> rev))
    else
      eventLog
        .currentEventsByPersistenceId(
          EventSourceProcessor.persistenceId(entityType, address.string),
          Long.MinValue,
          Long.MaxValue
        )
        .takeWhile(_.event.rev <= rev)
        .fold[AclState](AclState.Initial) { case (state, event) =>
          Acls.next(state, event.event)
        }
        .compile
        .last
        .hideErrors
        .flatMap {
          case Some(state) if state.rev == rev => UIO.pure(state.toResource)
          case Some(_)                         =>
            fetch(address).flatMap {
              case Some(res) => IO.raiseError(RevisionNotFound(rev, res.rev))
              case None      => IO.pure(None)
            }
          case None                            => IO.raiseError(RevisionNotFound(rev, 0L))
        }
        .named("fetchAclsAt", component, Map("rev" -> rev))

  override def list(filter: AclAddressFilter): UIO[AclCollection]                                              =
    index.values
      .map(as => AclCollection(as.toSeq: _*).fetch(filter))
      .named("listAcls", component, Map("withAncestors" -> filter.withAncestors))

  override def listSelf(filter: AclAddressFilter)(implicit caller: Caller): UIO[AclCollection]                 =
    list(filter)
      .map(_.filter(caller.identities))
      .named("listSelfAcls", component, Map("withAncestors" -> filter.withAncestors))

  override def replace(address: AclAddress, acl: Acl, rev: Long)(implicit
      caller: Identity.Subject
  ): IO[AclRejection, AclResource]                                                                             =
    eval(ReplaceAcl(address, acl, rev, caller)).named("replaceAcls", component)

  override def append(address: AclAddress, acl: Acl, rev: Long)(implicit
      caller: Identity.Subject
  ): IO[AclRejection, AclResource] =
    eval(AppendAcl(address, acl, rev, caller)).named("appendAcls", component)

  override def subtract(address: AclAddress, acl: Acl, rev: Long)(implicit
      caller: Identity.Subject
  ): IO[AclRejection, AclResource] =
    eval(SubtractAcl(address, acl, rev, caller)).named("subtractAcls", component)

  override def delete(address: AclAddress, rev: Long)(implicit
      caller: Identity.Subject
  ): IO[AclRejection, AclResource] =
    eval(DeleteAcl(address, rev, caller)).named("deleteAcls", component)

  private def eval(cmd: AclCommand): IO[AclRejection, AclResource] =
    for {
      evaluationResult <- agg.evaluate(cmd.address.string, cmd).mapError(_.value)
      resource         <- IO.fromOption(
                            evaluationResult.state.toResource,
                            UnexpectedInitialState(cmd.address)
                          )
      _                <- index.put(cmd.address, resource)
    } yield resource

}

object AclsImpl {

  type AclsAggregate = Aggregate[String, AclState, AclCommand, AclEvent, AclRejection]

  type AclsCache = KeyValueStore[AclAddress, AclResource]

  final val entityType: String = "acl"

  final val aclTag = "acl"

  private val logger: Logger = Logger[AclsImpl]

  private def aggregate(
      permissions: UIO[Permissions],
      aggregateConfig: AggregateConfig
  )(implicit as: ActorSystem[Nothing], clock: Clock[UIO]): UIO[AclsAggregate] = {
    val definition = PersistentEventDefinition(
      entityType = entityType,
      initialState = AclState.Initial,
      next = Acls.next,
      evaluate = Acls.evaluate(permissions),
      tagger = (_: AclEvent) => Set(aclTag),
      snapshotStrategy =
        SnapshotStrategy.SnapshotEvery(numberOfEvents = 500, keepNSnapshots = 1, deleteEventsOnSnapshot = false),
      stopStrategy = StopStrategy.PersistentStopStrategy.never
    )
    ShardedAggregate
      .persistentSharded(
        definition = definition,
        config = aggregateConfig,
        retryStrategy = RetryStrategy.alwaysGiveUp
        // TODO: configure the number of shards
      )
  }

  private def cache(aclsConfig: AclsConfig)(implicit as: ActorSystem[Nothing]): AclsCache = {
    implicit val cfg: KeyValueStoreConfig  = aclsConfig.keyValueStore
    val clock: (Long, AclResource) => Long = (_, resource) => resource.rev
    KeyValueStore.distributed("acls", clock)
  }

  private def startIndexing(
      config: AclsConfig,
      eventLog: EventLog[Envelope[AclEvent]],
      index: AclsCache,
      acls: Acls
  )(implicit as: ActorSystem[Nothing], sc: Scheduler) =
    StreamSupervisor.runAsSingleton(
      "AclsIndex",
      streamTask = Task.delay(
        eventLog
          .eventsByTag(aclTag, Offset.noOffset)
          .mapAsync(config.indexing.concurrency)(envelope =>
            acls.fetch(envelope.event.address).flatMap {
              case Some(acl) => index.put(acl.id, acl)
              case None      => UIO.unit
            }
          )
      ),
      retryStrategy = RetryStrategy(
        config.indexing.retryStrategy,
        _ => true,
        RetryStrategy.logError(logger, "acls indexing")
      )
    )

  private def apply(agg: AclsAggregate, eventLog: EventLog[Envelope[AclEvent]], cache: AclsCache): AclsImpl =
    new AclsImpl(agg, eventLog, cache)

  /**
    * Constructs an [[AclsImpl]] instance.
    *
    * @param config       ACLs configurate
    * @param permissions  [[Permissions]] instance
    * @param eventLog     the event log
    */
  final def apply(
      config: AclsConfig,
      permissions: UIO[Permissions],
      eventLog: EventLog[Envelope[AclEvent]]
  )(implicit
      as: ActorSystem[Nothing],
      sc: Scheduler,
      clock: Clock[UIO]
  ): UIO[AclsImpl] =
    for {
      agg  <- aggregate(permissions, config.aggregate)
      index = cache(config)
      acls  = AclsImpl.apply(agg, eventLog, index)
      _    <- UIO.delay(startIndexing(config, eventLog, index, acls))
    } yield acls
}
