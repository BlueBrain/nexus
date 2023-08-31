package ch.epfl.bluebrain.nexus.delta.plugins.storage

import akka.actor
import akka.actor.typed.ActorSystem
import cats.effect.Clock
import ch.epfl.bluebrain.nexus.delta.kernel.utils.UUIDF
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.client.ElasticSearchClient
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.config.ElasticSearchViewsConfig
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.Files
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.contexts.{files => fileCtxId}
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model._
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.routes.FilesRoutes
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.schemas.{files => filesSchemaId}
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.StoragesConfig.StorageTypeConfig
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.contexts.{storages => storageCtxId, storagesMetadata => storageMetaCtxId}
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model._
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.StorageAccess
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.remote.RemoteStorageAuthTokenProvider
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.remote.client.RemoteDiskStorageClient
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.routes.StoragesRoutes
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.schemas.{storage => storagesSchemaId}
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.{StorageDeletionTask, Storages, StoragesStatistics}
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.api.JsonLdApi
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.{ContextValue, RemoteContextResolution}
import ch.epfl.bluebrain.nexus.delta.rdf.utils.JsonKeyOrdering
import ch.epfl.bluebrain.nexus.delta.sdk._
import ch.epfl.bluebrain.nexus.delta.sdk.acls.AclCheck
import ch.epfl.bluebrain.nexus.delta.sdk.deletion.ProjectDeletionTask
import ch.epfl.bluebrain.nexus.delta.sdk.directives.DeltaSchemeDirectives
import ch.epfl.bluebrain.nexus.delta.sdk.fusion.FusionConfig
import ch.epfl.bluebrain.nexus.delta.sdk.http.HttpClient
import ch.epfl.bluebrain.nexus.delta.sdk.identities.Identities
import ch.epfl.bluebrain.nexus.delta.sdk.identities.model.ServiceAccount
import ch.epfl.bluebrain.nexus.delta.sdk.model._
import ch.epfl.bluebrain.nexus.delta.sdk.model.metrics.ScopedEventMetricEncoder
import ch.epfl.bluebrain.nexus.delta.sdk.permissions.Permissions
import ch.epfl.bluebrain.nexus.delta.sdk.projects.FetchContext
import ch.epfl.bluebrain.nexus.delta.sdk.projects.FetchContext.ContextRejection
import ch.epfl.bluebrain.nexus.delta.sdk.projects.model.ApiMappings
import ch.epfl.bluebrain.nexus.delta.sdk.resolvers.ResolverContextResolution
import ch.epfl.bluebrain.nexus.delta.sdk.sse.SseEncoder
import ch.epfl.bluebrain.nexus.delta.sourcing.Transactors
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Label
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.Supervisor
import com.typesafe.config.Config
import izumi.distage.model.definition.{Id, ModuleDef}
import monix.bio.{Task, UIO}
import monix.execution.Scheduler

/**
  * Storages and Files wiring
  */
class StoragePluginModule(priority: Int) extends ModuleDef {

  implicit private val classLoader: ClassLoader = getClass.getClassLoader

  make[StoragePluginConfig].fromEffect { cfg: Config => StoragePluginConfig.load(cfg) }

  make[StorageTypeConfig].from { cfg: StoragePluginConfig => cfg.storages.storageTypeConfig }

  make[HttpClient].named("storage").from { (as: ActorSystem[Nothing], sc: Scheduler) =>
    HttpClient.noRetry()(as.classicSystem, sc)
  }

  make[Storages]
    .fromEffect {
      (
          fetchContext: FetchContext[ContextRejection],
          contextResolution: ResolverContextResolution,
          remoteStorageAuthTokenProvider: RemoteStorageAuthTokenProvider,
          permissions: Permissions,
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
        implicit val a: RemoteStorageAuthTokenProvider    = remoteStorageAuthTokenProvider
        Storages(
          fetchContext.mapRejection(StorageRejection.ProjectContextRejection),
          contextResolution,
          permissions.fetchPermissionSet,
          StorageAccess.apply(_, _),
          xas,
          cfg.storages,
          serviceAccount
        )(
          api,
          clock,
          uuidF
        )
    }

  make[StoragesStatistics].from {
    (
        client: ElasticSearchClient,
        storages: Storages,
        config: ElasticSearchViewsConfig
    ) =>
      StoragesStatistics(
        client,
        storages.fetch(_, _).map(_.id),
        config.prefix
      )
  }

  make[StoragesRoutes].from {
    (
        identities: Identities,
        aclCheck: AclCheck,
        storages: Storages,
        storagesStatistics: StoragesStatistics,
        schemeDirectives: DeltaSchemeDirectives,
        indexingAction: IndexingAction @Id("aggregate"),
        shift: Storage.Shift,
        baseUri: BaseUri,
        s: Scheduler,
        cr: RemoteContextResolution @Id("aggregate"),
        ordering: JsonKeyOrdering,
        fusionConfig: FusionConfig
    ) =>
      {
        new StoragesRoutes(
          identities,
          aclCheck,
          storages,
          storagesStatistics,
          schemeDirectives,
          indexingAction(_, _, _)(shift, cr)
        )(
          baseUri,
          s,
          cr,
          ordering,
          fusionConfig
        )
      }
  }

  make[Storage.Shift].from { (storages: Storages, base: BaseUri) =>
    Storage.shift(storages)(base)
  }

  many[ResourceShift[_, _, _]].ref[Storage.Shift]

  make[RemoteStorageAuthTokenProvider].from { (cfg: StorageTypeConfig) =>
    RemoteStorageAuthTokenProvider(cfg)
  }

  make[Files]
    .fromEffect {
      (
          cfg: StoragePluginConfig,
          storageTypeConfig: StorageTypeConfig,
          client: HttpClient @Id("storage"),
          aclCheck: AclCheck,
          fetchContext: FetchContext[ContextRejection],
          storages: Storages,
          supervisor: Supervisor,
          storagesStatistics: StoragesStatistics,
          xas: Transactors,
          clock: Clock[UIO],
          uuidF: UUIDF,
          as: ActorSystem[Nothing],
          authProvider: RemoteStorageAuthTokenProvider,
          scheduler: Scheduler
      ) =>
        Task
          .delay(
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
              as,
              authProvider
            )
          )
          .tapEval { files =>
            Files.startDigestStream(files, supervisor, storageTypeConfig)
          }
    }

  make[FilesRoutes].from {
    (
        cfg: StoragePluginConfig,
        identities: Identities,
        aclCheck: AclCheck,
        files: Files,
        schemeDirectives: DeltaSchemeDirectives,
        indexingAction: IndexingAction @Id("aggregate"),
        shift: File.Shift,
        baseUri: BaseUri,
        s: Scheduler,
        cr: RemoteContextResolution @Id("aggregate"),
        ordering: JsonKeyOrdering,
        fusionConfig: FusionConfig
    ) =>
      val storageConfig = cfg.storages.storageTypeConfig
      new FilesRoutes(identities, aclCheck, files, schemeDirectives, indexingAction(_, _, _)(shift, cr))(
        baseUri,
        storageConfig,
        s,
        cr,
        ordering,
        fusionConfig
      )
  }

  make[File.Shift].from { (files: Files, base: BaseUri, storageTypeConfig: StorageTypeConfig) =>
    File.shift(files)(base, storageTypeConfig)
  }

  many[ResourceShift[_, _, _]].ref[File.Shift]

  many[ServiceDependency].addSet {
    (
        cfg: StorageTypeConfig,
        client: HttpClient @Id("storage"),
        as: ActorSystem[Nothing],
        remoteStorageAuthTokenProvider: RemoteStorageAuthTokenProvider
    ) =>
      val remoteStorageClient = cfg.remoteDisk.map { r =>
        new RemoteDiskStorageClient(r.defaultEndpoint)(client, as.classicSystem, remoteStorageAuthTokenProvider)
      }
      remoteStorageClient.fold(Set.empty[RemoteStorageServiceDependency])(client =>
        Set(new RemoteStorageServiceDependency(client))
      )
  }

  make[StorageScopeInitialization].from {
    (storages: Storages, serviceAccount: ServiceAccount, cfg: StoragePluginConfig) =>
      new StorageScopeInitialization(storages, serviceAccount, cfg.defaults)
  }

  many[ScopeInitialization].ref[StorageScopeInitialization]

  many[ProjectDeletionTask].add { (storages: Storages) => StorageDeletionTask(storages) }

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

  many[SseEncoder[_]].add { (base: BaseUri) => StorageEvent.sseEncoder(base) }
  many[SseEncoder[_]].add { (base: BaseUri, config: StorageTypeConfig) => FileEvent.sseEncoder(base, config) }

  many[ScopedEventMetricEncoder[_]].add { FileEvent.fileEventMetricEncoder }
  many[ScopedEventMetricEncoder[_]].add { () => StorageEvent.storageEventMetricEncoder }

  many[PriorityRoute].add { (storagesRoutes: StoragesRoutes) =>
    PriorityRoute(priority, storagesRoutes.routes, requiresStrictEntity = true)
  }
  many[PriorityRoute].add { (fileRoutes: FilesRoutes) =>
    PriorityRoute(priority, fileRoutes.routes, requiresStrictEntity = false)
  }
}
