package ch.epfl.bluebrain.nexus.delta.plugins.storage

import akka.actor.typed.ActorSystem
import cats.effect.Clock
import ch.epfl.bluebrain.nexus.delta.kernel.utils.UUIDF
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.Files
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model.FileEvent
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.routes.FilesRoutes
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.Storages
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.{Crypto, StorageEvent}
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.remote.client.RemoteDiskStorageClient
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.routes.StoragesRoutes
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.RemoteContextResolution
import ch.epfl.bluebrain.nexus.delta.rdf.utils.JsonKeyOrdering
import ch.epfl.bluebrain.nexus.delta.sdk._
import ch.epfl.bluebrain.nexus.delta.sdk.eventlog.EventLogUtils.databaseEventLog
import ch.epfl.bluebrain.nexus.delta.sdk.http.HttpClient
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.ServiceAccount
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.PaginationConfig
import ch.epfl.bluebrain.nexus.delta.sdk.model.{BaseUri, Envelope}
import ch.epfl.bluebrain.nexus.migration.{FilesMigration, StoragesMigration}
import ch.epfl.bluebrain.nexus.sourcing.EventLog
import izumi.distage.model.definition.ModuleDef
import monix.bio.UIO
import monix.execution.Scheduler

/**
  * Storages and Files wiring
  */
object StoragePluginModule extends ModuleDef {

  make[StoragePluginConfig].from { StoragePluginConfig.load(_) }

  make[EventLog[Envelope[StorageEvent]]].fromEffect { databaseEventLog[StorageEvent](_, _) }

  make[Storages]
    .fromEffect {
      (
          cfg: StoragePluginConfig,
          log: EventLog[Envelope[StorageEvent]],
          client: HttpClient,
          permissions: Permissions,
          orgs: Organizations,
          projects: Projects,
          clock: Clock[UIO],
          uuidF: UUIDF,
          rcr: RemoteContextResolution,
          as: ActorSystem[Nothing],
          scheduler: Scheduler
      ) =>
        Storages(cfg.storages, log, permissions, orgs, projects)(client, uuidF, clock, scheduler, as, rcr)
    }
    .aliased[StoragesMigration]

  make[StoragesRoutes].from {
    (
        cfg: StoragePluginConfig,
        identities: Identities,
        acls: Acls,
        organizations: Organizations,
        projects: Projects,
        storages: Storages,
        baseUri: BaseUri,
        s: Scheduler,
        cr: RemoteContextResolution,
        ordering: JsonKeyOrdering
    ) =>
      {
        val paginationConfig: PaginationConfig = cfg.storages.pagination
        val crypto: Crypto                     = cfg.storages.storageTypeConfig.encryption.crypto
        new StoragesRoutes(identities, acls, organizations, projects, storages)(
          baseUri,
          crypto,
          paginationConfig,
          s,
          cr,
          ordering
        )
      }
  }

  make[EventLog[Envelope[FileEvent]]].fromEffect { databaseEventLog[FileEvent](_, _) }

  make[Files]
    .fromEffect {
      (
          cfg: StoragePluginConfig,
          log: EventLog[Envelope[FileEvent]],
          client: HttpClient,
          acls: Acls,
          orgs: Organizations,
          projects: Projects,
          storages: Storages,
          clock: Clock[UIO],
          uuidF: UUIDF,
          as: ActorSystem[Nothing],
          scheduler: Scheduler
      ) =>
        Files(cfg.files, log, acls, orgs, projects, storages)(client, uuidF, clock, scheduler, as)
    }
    .aliased[FilesMigration]

  make[FilesRoutes].from {
    (
        cfg: StoragePluginConfig,
        identities: Identities,
        acls: Acls,
        organizations: Organizations,
        projects: Projects,
        files: Files,
        baseUri: BaseUri,
        s: Scheduler,
        cr: RemoteContextResolution,
        ordering: JsonKeyOrdering
    ) =>
      val storageConfig = cfg.storages.storageTypeConfig
      new FilesRoutes(identities, acls, organizations, projects, files)(baseUri, storageConfig, s, cr, ordering)
  }

  many[ServiceDependency].addSet { (cfg: StoragePluginConfig, client: HttpClient, as: ActorSystem[Nothing]) =>
    val remoteStorageClient = cfg.storages.storageTypeConfig.remoteDisk.map { r =>
      new RemoteDiskStorageClient(r.defaultEndpoint)(client, as.classicSystem)
    }
    remoteStorageClient.fold(Set.empty[RemoteStorageServiceDependency])(client =>
      Set(new RemoteStorageServiceDependency(client))
    )
  }

  make[StorageScopeInitialization].from { (storages: Storages, serviceAccount: ServiceAccount) =>
    new StorageScopeInitialization(storages, serviceAccount)
  }

  many[ScopeInitialization].ref[StorageScopeInitialization]

  make[StoragePlugin].from { new StoragePlugin(_, _) }
}
