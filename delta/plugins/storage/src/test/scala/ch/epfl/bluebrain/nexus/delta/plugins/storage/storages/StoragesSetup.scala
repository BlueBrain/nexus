package ch.epfl.bluebrain.nexus.delta.plugins.storage.storages

import akka.actor.typed.ActorSystem
import ch.epfl.bluebrain.nexus.delta.kernel.utils.UUIDF
import ch.epfl.bluebrain.nexus.delta.plugins.storage.RemoteContextResolutionFixture
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.StorageFixtures._
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.StoragesConfig.StorageTypeConfig
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.StorageEvent
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.StorageRejection.StorageFetchRejection
import ch.epfl.bluebrain.nexus.delta.sdk.eventlog.EventLogUtils
import ch.epfl.bluebrain.nexus.delta.sdk.identities.model.ServiceAccount
import ch.epfl.bluebrain.nexus.delta.sdk.model.Envelope
import ch.epfl.bluebrain.nexus.delta.sdk.permissions.model.Permission
import ch.epfl.bluebrain.nexus.delta.sdk.projects.FetchContext
import ch.epfl.bluebrain.nexus.delta.sdk.resolvers.ResolverContextResolution
import ch.epfl.bluebrain.nexus.delta.sdk.resolvers.model.ResourceResolutionReport
import ch.epfl.bluebrain.nexus.delta.sdk.testkit.ConfigFixtures
import ch.epfl.bluebrain.nexus.delta.sourcing.EventLog
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.User
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Label
import ch.epfl.bluebrain.nexus.testkit.{IOFixedClock, IOValues}
import monix.bio.IO
import monix.execution.Scheduler

trait StoragesSetup extends IOValues with RemoteContextResolutionFixture with ConfigFixtures with IOFixedClock {

  val serviceAccount: ServiceAccount = ServiceAccount(User("nexus-sa", Label.unsafe("sa")))

  def init(
      fetchContext: FetchContext[StorageFetchRejection],
      perms: Set[Permission]
  )(implicit as: ActorSystem[Nothing], uuid: UUIDF, sc: Scheduler): Storages =
    init(fetchContext, perms, config)

  def init(
      fetchContext: FetchContext[StorageFetchRejection],
      perms: Set[Permission],
      storageTypeConfig: StorageTypeConfig
  )(implicit as: ActorSystem[Nothing], uuid: UUIDF, sc: Scheduler): Storages = {
    for {
      eventLog   <- EventLog.postgresEventLog[Envelope[StorageEvent]](EventLogUtils.toEnvelope).hideErrors
      resolverCtx = new ResolverContextResolution(rcr, (_, _, _) => IO.raiseError(ResourceResolutionReport()))
      config      = StoragesConfig(aggregate, keyValueStore, pagination, cacheIndexing, persist, storageTypeConfig)
      agg        <- Storages.aggregate(config, (_, _) => IO.unit, (_, _) => IO.unit, IO.pure(perms), crypto)
      cache       = Storages.cache(config)
      storages   <- Storages(config, eventLog, resolverCtx, fetchContext, cache, agg, serviceAccount)
    } yield storages
  }.accepted
}

object StoragesSetup extends StoragesSetup
