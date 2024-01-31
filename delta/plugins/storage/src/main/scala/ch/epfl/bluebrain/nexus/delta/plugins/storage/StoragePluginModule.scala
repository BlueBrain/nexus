package ch.epfl.bluebrain.nexus.delta.plugins.storage

import akka.actor
import akka.actor.typed.ActorSystem
import cats.effect.{Clock, IO}
import ch.epfl.bluebrain.nexus.delta.kernel.utils.{ClasspathResourceLoader, TransactionalFileCopier, UUIDF}
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.client.ElasticSearchClient
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.config.ElasticSearchViewsConfig
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.Files
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.Files.FilesLog
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.batch.{BatchCopy, BatchFiles}
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.contexts.{files => fileCtxId}
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model._
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.routes.{BatchFilesRoutes, FilesRoutes}
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.schemas.{files => filesSchemaId}
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.StoragesConfig.{ShowFileLocation, StorageTypeConfig}
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.contexts.{storages => storageCtxId, storagesMetadata => storageMetaCtxId}
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model._
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.StorageAccess
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.disk.DiskStorageCopyFiles
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.remote.RemoteDiskStorageCopyFiles
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
import ch.epfl.bluebrain.nexus.delta.sdk.projects.model.ApiMappings
import ch.epfl.bluebrain.nexus.delta.sdk.resolvers.ResolverContextResolution
import ch.epfl.bluebrain.nexus.delta.sdk.sse.SseEncoder
import ch.epfl.bluebrain.nexus.delta.sourcing.{ScopedEventLog, Transactors}
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Label
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.Supervisor
import com.typesafe.config.Config
import izumi.distage.model.definition.{Id, ModuleDef}

/**
  * Storages and Files wiring
  */
class StoragePluginModule(priority: Int) extends ModuleDef {

  implicit private val loader: ClasspathResourceLoader = ClasspathResourceLoader.withContext(getClass)

  make[StoragePluginConfig].fromEffect { cfg: Config => StoragePluginConfig.load(cfg) }

  make[StorageTypeConfig].from { cfg: StoragePluginConfig => cfg.storages.storageTypeConfig }

  make[ShowFileLocation].from { cfg: StorageTypeConfig => cfg.showFileLocation }

  make[HttpClient].named("storage").from { (as: ActorSystem[Nothing]) =>
    HttpClient.noRetry(compression = false)(as.classicSystem)
  }

  make[Storages]
    .fromEffect {
      (
          fetchContext: FetchContext,
          contextResolution: ResolverContextResolution,
          remoteDiskStorageClient: RemoteDiskStorageClient,
          permissions: Permissions,
          xas: Transactors,
          cfg: StoragePluginConfig,
          serviceAccount: ServiceAccount,
          api: JsonLdApi,
          clock: Clock[IO],
          uuidF: UUIDF,
          as: ActorSystem[Nothing]
      ) =>
        implicit val classicAs: actor.ActorSystem         = as.classicSystem
        implicit val storageTypeConfig: StorageTypeConfig = cfg.storages.storageTypeConfig
        Storages(
          fetchContext,
          contextResolution,
          permissions.fetchPermissionSet,
          StorageAccess.apply(_, _, remoteDiskStorageClient, storageTypeConfig),
          xas,
          cfg.storages,
          serviceAccount,
          clock
        )(
          api,
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

  make[FilesLog].from { (cfg: StoragePluginConfig, xas: Transactors, clock: Clock[IO]) =>
    ScopedEventLog(Files.definition(clock), cfg.files.eventLog, xas)
  }

  make[Files]
    .fromEffect {
      (
          cfg: StoragePluginConfig,
          storageTypeConfig: StorageTypeConfig,
          aclCheck: AclCheck,
          fetchContext: FetchContext,
          storages: Storages,
          supervisor: Supervisor,
          storagesStatistics: StoragesStatistics,
          xas: Transactors,
          clock: Clock[IO],
          uuidF: UUIDF,
          as: ActorSystem[Nothing],
          remoteDiskStorageClient: RemoteDiskStorageClient
      ) =>
        IO
          .delay(
            Files(
              fetchContext,
              aclCheck,
              storages,
              storagesStatistics,
              xas,
              storageTypeConfig,
              cfg.files,
              remoteDiskStorageClient,
              clock
            )(
              uuidF,
              as
            )
          )
          .flatTap { files =>
            Files.startDigestStream(files, supervisor, storageTypeConfig)
          }
    }

  make[TransactionalFileCopier].fromValue(TransactionalFileCopier.mk())

  make[DiskStorageCopyFiles].from { copier: TransactionalFileCopier => DiskStorageCopyFiles.mk(copier) }

  make[RemoteDiskStorageCopyFiles].from { client: RemoteDiskStorageClient => RemoteDiskStorageCopyFiles.mk(client) }

  make[BatchCopy].from {
    (
        files: Files,
        storages: Storages,
        aclCheck: AclCheck,
        storagesStatistics: StoragesStatistics,
        diskCopy: DiskStorageCopyFiles,
        remoteDiskCopy: RemoteDiskStorageCopyFiles,
        uuidF: UUIDF
    ) =>
      BatchCopy.mk(files, storages, aclCheck, storagesStatistics, diskCopy, remoteDiskCopy)(uuidF)
  }

  make[BatchFiles].from {
    (
        fetchContext: FetchContext,
        files: Files,
        filesLog: FilesLog,
        batchCopy: BatchCopy,
        uuidF: UUIDF
    ) =>
      BatchFiles.mk(
        files,
        fetchContext,
        FilesLog.eval(filesLog),
        batchCopy
      )(uuidF)
  }

  make[FilesRoutes].from {
    (
        showLocation: ShowFileLocation,
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
      new FilesRoutes(identities, aclCheck, files, schemeDirectives, indexingAction(_, _, _)(shift))(
        baseUri,
        showLocation,
        cr,
        ordering,
        fusionConfig
      )
  }

  make[BatchFilesRoutes].from {
    (
        showLocation: ShowFileLocation,
        identities: Identities,
        aclCheck: AclCheck,
        batchFiles: BatchFiles,
        indexingAction: AggregateIndexingAction,
        shift: File.Shift,
        baseUri: BaseUri,
        cr: RemoteContextResolution @Id("aggregate"),
        ordering: JsonKeyOrdering
    ) =>
      new BatchFilesRoutes(identities, aclCheck, batchFiles, indexingAction(_, _, _)(shift))(
        baseUri,
        showLocation,
        cr,
        ordering
      )
  }

  make[File.Shift].from { (files: Files, base: BaseUri, showLocation: ShowFileLocation) =>
    File.shift(files)(base, showLocation)
  }

  many[ResourceShift[_, _, _]].ref[File.Shift]

  make[RemoteDiskStorageClient].from {
    (
        client: HttpClient @Id("storage"),
        as: ActorSystem[Nothing],
        authTokenProvider: AuthTokenProvider,
        cfg: StorageTypeConfig
    ) =>
      new RemoteDiskStorageClient(
        client,
        authTokenProvider,
        cfg.remoteDisk.map(_.credentials).getOrElse(Credentials.Anonymous)
      )(as.classicSystem)
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
  many[SseEncoder[_]].add { (base: BaseUri, showLocation: ShowFileLocation) =>
    FileEvent.sseEncoder(base, showLocation)
  }

  many[ScopedEventMetricEncoder[_]].add { FileEvent.fileEventMetricEncoder }
  many[ScopedEventMetricEncoder[_]].add { () => StorageEvent.storageEventMetricEncoder }

  many[PriorityRoute].add { (storagesRoutes: StoragesRoutes) =>
    PriorityRoute(priority, storagesRoutes.routes, requiresStrictEntity = true)
  }
  many[PriorityRoute].add { (fileRoutes: FilesRoutes) =>
    PriorityRoute(priority, fileRoutes.routes, requiresStrictEntity = false)
  }

  many[PriorityRoute].add { (batchFileRoutes: BatchFilesRoutes) =>
    PriorityRoute(priority, batchFileRoutes.routes, requiresStrictEntity = false)
  }
}
