package ch.epfl.bluebrain.nexus.delta.plugins.storage

import akka.actor
import akka.actor.typed.ActorSystem
import cats.effect.{Clock, ContextShift, IO, Timer}
import cats.syntax.all._
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
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.remote.client.RemoteDiskStorageClient
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.routes.StoragesRoutes
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.schemas.{storage => storagesSchemaId}
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.{StorageDeletionTask, StoragePermissionProviderImpl, Storages, StoragesStatistics}
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.api.JsonLdApi
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.{ContextValue, RemoteContextResolution}
import ch.epfl.bluebrain.nexus.delta.rdf.utils.JsonKeyOrdering
import ch.epfl.bluebrain.nexus.delta.sdk.IndexingAction.AggregateIndexingAction
import ch.epfl.bluebrain.nexus.delta.sdk._
import ch.epfl.bluebrain.nexus.delta.sdk.acls.AclCheck
import ch.epfl.bluebrain.nexus.delta.sdk.auth.{AuthTokenProvider, Credentials}
import ch.epfl.bluebrain.nexus.delta.sdk.deletion.ProjectDeletionTask
import ch.epfl.bluebrain.nexus.delta.sdk.directives.DeltaSchemeDirectives
import ch.epfl.bluebrain.nexus.delta.sdk.fusion.FusionConfig
import ch.epfl.bluebrain.nexus.delta.sdk.http.HttpClient
import ch.epfl.bluebrain.nexus.delta.sdk.identities.Identities
import ch.epfl.bluebrain.nexus.delta.sdk.identities.model.ServiceAccount
import ch.epfl.bluebrain.nexus.delta.sdk.model._
import ch.epfl.bluebrain.nexus.delta.sdk.model.metrics.ScopedEventMetricEncoder
import ch.epfl.bluebrain.nexus.delta.sdk.permissions.{Permissions, StoragePermissionProvider}
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

/**
  * Storages and Files wiring
  */
class StoragePluginModule(priority: Int) extends ModuleDef {

  implicit private val classLoader: ClassLoader = getClass.getClassLoader

  make[StoragePluginConfig].fromEffect { cfg: Config => StoragePluginConfig.load(cfg) }

  make[StorageTypeConfig].from { cfg: StoragePluginConfig => cfg.storages.storageTypeConfig }

  make[HttpClient].named("storage").from { (as: ActorSystem[Nothing], timer: Timer[IO], cs: ContextShift[IO]) =>
    HttpClient.noRetry(compression = false)(as.classicSystem, timer, cs)
  }

  make[Storages]
    .fromEffect {
      (
          fetchContext: FetchContext[ContextRejection],
          contextResolution: ResolverContextResolution,
          remoteDiskStorageClient: RemoteDiskStorageClient,
          permissions: Permissions,
          xas: Transactors,
          cfg: StoragePluginConfig,
          serviceAccount: ServiceAccount,
          api: JsonLdApi,
          clock: Clock[IO],
          timer: Timer[IO],
          uuidF: UUIDF,
          as: ActorSystem[Nothing],
          cs: ContextShift[IO]
      ) =>
        implicit val classicAs: actor.ActorSystem         = as.classicSystem
        implicit val storageTypeConfig: StorageTypeConfig = cfg.storages.storageTypeConfig
        implicit val contextShift: ContextShift[IO]       = cs
        Storages(
          fetchContext.mapRejection(StorageRejection.ProjectContextRejection),
          contextResolution,
          permissions.fetchPermissionSet,
          StorageAccess.apply(_, _, remoteDiskStorageClient, storageTypeConfig),
          xas,
          cfg.storages,
          serviceAccount
        )(
          api,
          clock,
          timer,
          cs,
          uuidF
        )
    }

  make[StoragePermissionProvider].from { (storages: Storages) =>
    new StoragePermissionProviderImpl(storages)
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
        indexingAction: AggregateIndexingAction,
        shift: Storage.Shift,
        baseUri: BaseUri,
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
          indexingAction(_, _, _)(shift)
        )(
          baseUri,
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

  make[Files]
    .fromEffect {
      (
          cfg: StoragePluginConfig,
          storageTypeConfig: StorageTypeConfig,
          aclCheck: AclCheck,
          fetchContext: FetchContext[ContextRejection],
          storages: Storages,
          supervisor: Supervisor,
          storagesStatistics: StoragesStatistics,
          xas: Transactors,
          clock: Clock[IO],
          uuidF: UUIDF,
          as: ActorSystem[Nothing],
          remoteDiskStorageClient: RemoteDiskStorageClient,
          timer: Timer[IO],
          cs: ContextShift[IO]
      ) =>
        IO
          .delay(
            Files(
              fetchContext.mapRejection(FileRejection.ProjectContextRejection),
              aclCheck,
              storages,
              storagesStatistics,
              xas,
              storageTypeConfig,
              cfg.files,
              remoteDiskStorageClient
            )(
              clock,
              uuidF,
              timer,
              cs,
              as
            )
          )
          .flatTap { files =>
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
        indexingAction: AggregateIndexingAction,
        shift: File.Shift,
        baseUri: BaseUri,
        cr: RemoteContextResolution @Id("aggregate"),
        ordering: JsonKeyOrdering,
        fusionConfig: FusionConfig
    ) =>
      val storageConfig = cfg.storages.storageTypeConfig
      new FilesRoutes(identities, aclCheck, files, schemeDirectives, indexingAction(_, _, _)(shift))(
        baseUri,
        storageConfig,
        cr,
        ordering,
        fusionConfig
      )
  }

  make[File.Shift].from { (files: Files, base: BaseUri, storageTypeConfig: StorageTypeConfig) =>
    File.shift(files)(base, storageTypeConfig)
  }

  many[ResourceShift[_, _, _]].ref[File.Shift]

  make[RemoteDiskStorageClient].from {
    (
        client: HttpClient @Id("storage"),
        as: ActorSystem[Nothing],
        authTokenProvider: AuthTokenProvider,
        cfg: StorageTypeConfig,
        cs: ContextShift[IO],
        timer: Timer[IO]
    ) =>
      new RemoteDiskStorageClient(
        client,
        authTokenProvider,
        cfg.remoteDisk.map(_.credentials).getOrElse(Credentials.Anonymous)
      )(as.classicSystem, cs, timer)
  }

  many[ServiceDependency].addSet {
    (
        cfg: StorageTypeConfig,
        remoteStorageClient: RemoteDiskStorageClient
    ) =>
      cfg.remoteDisk
        .map(_.defaultEndpoint)
        .map(endpoint => Set(new RemoteStorageServiceDependency(remoteStorageClient, endpoint)))
        .getOrElse(Set.empty[RemoteStorageServiceDependency])
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
