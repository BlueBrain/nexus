package ch.epfl.bluebrain.nexus.delta.plugins.storage

import akka.actor
import akka.actor.typed.ActorSystem
import cats.effect.Clock
import ch.epfl.bluebrain.nexus.delta.kernel.RetryStrategyConfig
import ch.epfl.bluebrain.nexus.delta.kernel.database.Transactors
import ch.epfl.bluebrain.nexus.delta.kernel.utils.UUIDF
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.Files
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.contexts.{files => fileCtxId}
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model.{FileEvent, FileRejection}
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.routes.FilesRoutes
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.schemas.{files => filesSchemaId}
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.StoragesConfig.StorageTypeConfig
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.contexts.{storages => storageCtxId, storagesMetadata => storageMetaCtxId}
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.{StorageEvent, StorageRejection}
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.StorageAccess
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.remote.client.RemoteDiskStorageClient
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.routes.StoragesRoutes
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.schemas.{storage => storagesSchemaId}
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.{Storages, StoragesStatistics}
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.api.JsonLdApi
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.{ContextValue, RemoteContextResolution}
import ch.epfl.bluebrain.nexus.delta.rdf.utils.JsonKeyOrdering
import ch.epfl.bluebrain.nexus.delta.sdk._
import ch.epfl.bluebrain.nexus.delta.sdk.acls.AclCheck
import ch.epfl.bluebrain.nexus.delta.sdk.crypto.Crypto
import ch.epfl.bluebrain.nexus.delta.sdk.directives.DeltaSchemeDirectives
import ch.epfl.bluebrain.nexus.delta.sdk.fusion.FusionConfig
import ch.epfl.bluebrain.nexus.delta.sdk.http.{HttpClient, HttpClientConfig, HttpClientWorthRetry}
import ch.epfl.bluebrain.nexus.delta.sdk.identities.Identities
import ch.epfl.bluebrain.nexus.delta.sdk.identities.model.ServiceAccount
import ch.epfl.bluebrain.nexus.delta.sdk.model._
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.PaginationConfig
import ch.epfl.bluebrain.nexus.delta.sdk.permissions.Permissions
import ch.epfl.bluebrain.nexus.delta.sdk.projects.FetchContext
import ch.epfl.bluebrain.nexus.delta.sdk.projects.FetchContext.ContextRejection
import ch.epfl.bluebrain.nexus.delta.sdk.projects.model.ApiMappings
import ch.epfl.bluebrain.nexus.delta.sdk.resolvers.ResolverContextResolution
import ch.epfl.bluebrain.nexus.delta.sdk.sse.SseEncoder
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Label
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

  make[HttpClient].named("storage").from { (cfg: StoragePluginConfig, as: ActorSystem[Nothing], sc: Scheduler) =>
    def defaultHttpClientConfig = HttpClientConfig(RetryStrategyConfig.AlwaysGiveUp, HttpClientWorthRetry.never, true)
    HttpClient()(
      cfg.storages.storageTypeConfig.remoteDisk.fold(defaultHttpClientConfig)(_.client),
      as.classicSystem,
      sc
    )
  }

  make[Storages]
    .fromEffect {
      (
          fetchContext: FetchContext[ContextRejection],
          contextResolution: ResolverContextResolution,
          permissions: Permissions,
          crypto: Crypto,
          xas: Transactors,
          cfg: StoragePluginConfig,
          serviceAccount: ServiceAccount,
          api: JsonLdApi,
          client: HttpClient @Id("storage"),
          clock: Clock[UIO],
          uuidF: UUIDF,
          as: ActorSystem[Nothing]
      ) =>
        implicit val classicAs: actor.ActorSystem         = as.classicSystem
        implicit val storageTypeConfig: StorageTypeConfig = cfg.storages.storageTypeConfig
        implicit val c: HttpClient                        = client
        Storages(
          fetchContext.mapRejection(StorageRejection.ProjectContextRejection),
          contextResolution,
          permissions.fetchPermissionSet,
          StorageAccess.apply(_, _),
          crypto,
          xas,
          cfg.storages,
          serviceAccount
        )(
          api,
          clock,
          uuidF
        )
    }

  // TODO implement StoragesStatistics
  make[StoragesStatistics].fromValue(StoragesStatistics())

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

  make[Files]
    .from {
      (
          cfg: StoragePluginConfig,
          storageTypeConfig: StorageTypeConfig,
          client: HttpClient @Id("storage"),
          aclCheck: AclCheck,
          fetchContext: FetchContext[ContextRejection],
          storages: Storages,
          storagesStatistics: StoragesStatistics,
          xas: Transactors,
          clock: Clock[UIO],
          uuidF: UUIDF,
          as: ActorSystem[Nothing],
          scheduler: Scheduler
      ) =>
        Files(
          fetchContext.mapRejection(FileRejection.ProjectContextRejection),
          aclCheck,
          storages,
          storagesStatistics,
          xas,
          storageTypeConfig,
          cfg.files
        )(
          clock,
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

  many[SseEncoder[_]].add { (crypto: Crypto, base: BaseUri) => StorageEvent.sseEncoder(crypto)(base) }
  many[SseEncoder[_]].add { (base: BaseUri, config: StorageTypeConfig) => FileEvent.sseEncoder(base, config) }

  many[PriorityRoute].add { (storagesRoutes: StoragesRoutes) =>
    PriorityRoute(priority, storagesRoutes.routes, requiresStrictEntity = true)
  }

  many[PriorityRoute].add { (fileRoutes: FilesRoutes) =>
    PriorityRoute(priority, fileRoutes.routes, requiresStrictEntity = false)
  }
}
