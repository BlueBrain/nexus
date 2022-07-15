package ch.epfl.bluebrain.nexus.delta.plugins.storage

import akka.actor.typed.ActorSystem
import cats.effect.Clock
import ch.epfl.bluebrain.nexus.delta.kernel.RetryStrategyConfig
import ch.epfl.bluebrain.nexus.delta.kernel.utils.UUIDF
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.Files.FilesAggregate
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.contexts.{files => fileCtxId}
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model.{FileEvent, FileRejection}
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.routes.FilesRoutes
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.schemas.{files => filesSchemaId}
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.{FileEventExchange, Files}
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.Storages.{StoragesAggregate, StoragesCache}
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.StoragesConfig.StorageTypeConfig
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.contexts.{storages => storageCtxId, storagesMetadata => storageMetaCtxId}
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.migration.RemoteStorageMigrationImpl
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.{StorageEvent, StorageRejection, StorageStatsCollection}
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.remote.client.RemoteDiskStorageClient
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.routes.StoragesRoutes
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.schemas.{storage => storagesSchemaId}
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.{StorageEventExchange, Storages, StoragesStatistics}
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.api.JsonLdApi
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.{ContextValue, RemoteContextResolution}
import ch.epfl.bluebrain.nexus.delta.rdf.utils.JsonKeyOrdering
import ch.epfl.bluebrain.nexus.delta.sdk._
import ch.epfl.bluebrain.nexus.delta.sdk.acls.AclCheck
import ch.epfl.bluebrain.nexus.delta.sdk.crypto.Crypto
import ch.epfl.bluebrain.nexus.delta.sdk.directives.DeltaSchemeDirectives
import ch.epfl.bluebrain.nexus.delta.sdk.eventlog.EventLogUtils.databaseEventLog
import ch.epfl.bluebrain.nexus.delta.sdk.fusion.FusionConfig
import ch.epfl.bluebrain.nexus.delta.sdk.http.{HttpClient, HttpClientConfig, HttpClientWorthRetry}
import ch.epfl.bluebrain.nexus.delta.sdk.identities.Identities
import ch.epfl.bluebrain.nexus.delta.sdk.identities.model.ServiceAccount
import ch.epfl.bluebrain.nexus.delta.sdk.migration.RemoteStorageMigration
import ch.epfl.bluebrain.nexus.delta.sdk.model._
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.PaginationConfig
import ch.epfl.bluebrain.nexus.delta.sdk.permissions.Permissions
import ch.epfl.bluebrain.nexus.delta.sdk.projects.FetchContext
import ch.epfl.bluebrain.nexus.delta.sdk.projects.FetchContext.ContextRejection
import ch.epfl.bluebrain.nexus.delta.sdk.projects.model.ApiMappings
import ch.epfl.bluebrain.nexus.delta.sdk.resolvers.ResolverContextResolution
import ch.epfl.bluebrain.nexus.delta.sourcing.EventLog
import ch.epfl.bluebrain.nexus.delta.sourcing.config.DatabaseConfig
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Label
import ch.epfl.bluebrain.nexus.delta.sourcing.projections.Projection
import com.typesafe.config.Config
import izumi.distage.model.definition.{Id, ModuleDef}
import monix.bio.UIO
import monix.execution.Scheduler

/**
  * Storages and Files wiring
  */
class StoragePluginModule(priority: Int) extends ModuleDef {

  implicit private val classLoader: ClassLoader = getClass.getClassLoader

  make[StoragePluginConfig].fromEffect { cfg: Config => StoragePluginConfig.load(cfg) }

  make[StorageTypeConfig].from { cfg: StoragePluginConfig => cfg.storages.storageTypeConfig }

  make[EventLog[Envelope[StorageEvent]]].fromEffect { databaseEventLog[StorageEvent](_, _) }

  make[HttpClient].named("storage").from { (cfg: StoragePluginConfig, as: ActorSystem[Nothing], sc: Scheduler) =>
    def defaultHttpClientConfig = HttpClientConfig(RetryStrategyConfig.AlwaysGiveUp, HttpClientWorthRetry.never, true)
    HttpClient()(
      cfg.storages.storageTypeConfig.remoteDisk.fold(defaultHttpClientConfig)(_.client),
      as.classicSystem,
      sc
    )
  }

  make[StoragesCache].from { (cfg: StoragePluginConfig, as: ActorSystem[Nothing]) => Storages.cache(cfg.storages)(as) }

  make[StoragesAggregate].fromEffect {
    (
        config: StoragePluginConfig,
        resourceIdCheck: ResourceIdCheck,
        permissions: Permissions,
        crypto: Crypto,
        client: HttpClient @Id("storage"),
        as: ActorSystem[Nothing],
        clock: Clock[UIO]
    ) => Storages.aggregate(config.storages, resourceIdCheck, permissions.fetchPermissionSet, crypto)(client, as, clock)
  }

  make[Storages]
    .fromEffect {
      (
          cfg: StoragePluginConfig,
          log: EventLog[Envelope[StorageEvent]],
          fetchContext: FetchContext[ContextRejection],
          cache: StoragesCache,
          agg: StoragesAggregate,
          api: JsonLdApi,
          uuidF: UUIDF,
          contextResolution: ResolverContextResolution,
          as: ActorSystem[Nothing],
          scheduler: Scheduler,
          serviceAccount: ServiceAccount
      ) =>
        Storages(
          cfg.storages,
          log,
          contextResolution,
          fetchContext.mapRejection(StorageRejection.ProjectContextRejection),
          cache,
          agg,
          serviceAccount
        )(
          api,
          uuidF,
          scheduler,
          as
        )
    }

  make[StoragesRoutes].from {
    (
        cfg: StoragePluginConfig,
        crypto: Crypto,
        identities: Identities,
        aclCheck: AclCheck,
        storages: Storages,
        storagesStatistics: StoragesStatistics,
        schemeDirectives: DeltaSchemeDirectives,
        indexingAction: IndexingAction @Id("aggregate"),
        baseUri: BaseUri,
        s: Scheduler,
        cr: RemoteContextResolution @Id("aggregate"),
        ordering: JsonKeyOrdering,
        fusionConfig: FusionConfig
    ) =>
      {
        val paginationConfig: PaginationConfig = cfg.storages.pagination
        new StoragesRoutes(identities, aclCheck, storages, storagesStatistics, schemeDirectives, indexingAction)(
          baseUri,
          crypto,
          paginationConfig,
          s,
          cr,
          ordering,
          fusionConfig
        )
      }
  }

  make[EventLog[Envelope[FileEvent]]].fromEffect { databaseEventLog[FileEvent](_, _) }

  make[Projection[StorageStatsCollection]].fromEffect {
    (database: DatabaseConfig, system: ActorSystem[Nothing], clock: Clock[UIO]) =>
      Projection(database, StorageStatsCollection.empty, system, clock)
  }

  make[StoragesStatistics].fromEffect {
    (
        files: Files,
        storages: Storages,
        projection: Projection[StorageStatsCollection],
        eventLog: EventLog[Envelope[FileEvent]],
        cfg: StoragePluginConfig,
        uuidF: UUIDF,
        as: ActorSystem[Nothing],
        sc: Scheduler
    ) =>
      StoragesStatistics(files, storages, projection, eventLog.eventsByTag(Files.moduleType, _), cfg.storages)(
        uuidF,
        as,
        sc
      )
  }

  make[FilesAggregate].fromEffect {
    (cfg: StoragePluginConfig, resourceIdCheck: ResourceIdCheck, as: ActorSystem[Nothing], clock: Clock[UIO]) =>
      Files.aggregate(cfg.files.aggregate, resourceIdCheck)(as, clock)
  }

  make[Files]
    .fromEffect {
      (
          cfg: StoragePluginConfig,
          storageTypeConfig: StorageTypeConfig,
          log: EventLog[Envelope[FileEvent]],
          client: HttpClient @Id("storage"),
          aclCheck: AclCheck,
          fetchContext: FetchContext[ContextRejection],
          storages: Storages,
          storagesStatistics: StoragesStatistics,
          agg: FilesAggregate,
          uuidF: UUIDF,
          as: ActorSystem[Nothing],
          scheduler: Scheduler
      ) =>
        Files(
          cfg.files,
          storageTypeConfig,
          log,
          aclCheck,
          fetchContext.mapRejection(FileRejection.ProjectContextRejection),
          storages,
          storagesStatistics,
          agg
        )(
          client,
          uuidF,
          scheduler,
          as
        )
    }

  make[FilesRoutes].from {
    (
        cfg: StoragePluginConfig,
        identities: Identities,
        aclCheck: AclCheck,
        files: Files,
        schemeDirectives: DeltaSchemeDirectives,
        indexingAction: IndexingAction @Id("aggregate"),
        baseUri: BaseUri,
        s: Scheduler,
        cr: RemoteContextResolution @Id("aggregate"),
        ordering: JsonKeyOrdering,
        fusionConfig: FusionConfig
    ) =>
      val storageConfig = cfg.storages.storageTypeConfig
      new FilesRoutes(identities, aclCheck, files, schemeDirectives, indexingAction)(
        baseUri,
        storageConfig,
        s,
        cr,
        ordering,
        fusionConfig
      )
  }

  many[ServiceDependency].addSet {
    (cfg: StorageTypeConfig, client: HttpClient @Id("storage"), as: ActorSystem[Nothing]) =>
      val remoteStorageClient = cfg.remoteDisk.map { r =>
        new RemoteDiskStorageClient(r.defaultEndpoint)(client, as.classicSystem)
      }
      remoteStorageClient.fold(Set.empty[RemoteStorageServiceDependency])(client =>
        Set(new RemoteStorageServiceDependency(client))
      )
  }

  make[StorageScopeInitialization]
  many[ScopeInitialization].ref[StorageScopeInitialization]

  many[MetadataContextValue].addEffect(MetadataContextValue.fromFile("contexts/storages-metadata.json"))

  many[MetadataContextValue].addEffect(MetadataContextValue.fromFile("contexts/files.json"))

  many[RemoteContextResolution].addEffect {
    for {
      storageCtx     <- ContextValue.fromFile("contexts/storages.json")
      storageMetaCtx <- ContextValue.fromFile("contexts/storages-metadata.json")
      fileCtx        <- ContextValue.fromFile("contexts/files.json")
    } yield RemoteContextResolution.fixed(
      storageCtxId     -> storageCtx,
      storageMetaCtxId -> storageMetaCtx,
      fileCtxId        -> fileCtx
    )
  }

  many[ResourceToSchemaMappings].add(
    ResourceToSchemaMappings(Label.unsafe("storages") -> storagesSchemaId, Label.unsafe("files") -> filesSchemaId)
  )

  many[ApiMappings].add(Storages.mappings + Files.mappings)

  many[PriorityRoute].add { (storagesRoutes: StoragesRoutes) =>
    PriorityRoute(priority, storagesRoutes.routes, requiresStrictEntity = true)
  }

  many[PriorityRoute].add { (fileRoutes: FilesRoutes) =>
    PriorityRoute(priority, fileRoutes.routes, requiresStrictEntity = false)
  }

  many[ReferenceExchange].add { (storages: Storages, crypto: Crypto) =>
    Storages.referenceExchange(storages)(crypto)
  }

  many[ReferenceExchange].add { (files: Files, config: StorageTypeConfig) =>
    Files.referenceExchange(files)(config)
  }

  make[StorageEventExchange]
  make[FileEventExchange]
  many[EventExchange].ref[StorageEventExchange].ref[FileEventExchange]
  many[EventExchange].named("resources").ref[StorageEventExchange].ref[FileEventExchange]

  if (sys.env.contains("MIGRATION_REMOTE_STORAGE")) {
    make[RemoteStorageMigration].fromEffect((as: ActorSystem[Nothing], databaseConfig: DatabaseConfig) =>
      RemoteStorageMigrationImpl(as, databaseConfig.cassandra)
    )
  }
}
