package ch.epfl.bluebrain.nexus.delta.plugins.storage

import akka.actor.typed.ActorSystem
import cats.effect.{Clock, IO}
import ch.epfl.bluebrain.nexus.delta.kernel.utils.{ClasspathResourceLoader, TransactionalFileCopier, UUIDF}
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.client.ElasticSearchClient
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.config.ElasticSearchViewsConfig
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.Files.FilesLog
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.batch.{BatchCopy, BatchFiles}
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.contexts.{files => fileCtxId}
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model._
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.routes.{BatchFilesRoutes, FilesRoutes}
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.schemas.{files => filesSchemaId}
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.{FileAttributesUpdateStream, Files}
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.StoragesConfig.{ShowFileLocation, StorageTypeConfig}
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.contexts.{storages => storageCtxId, storagesMetadata => storageMetaCtxId}
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model._
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.FileOperations
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.disk.{DiskFileOperations, DiskStorageCopyFiles}
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.remote.client.RemoteDiskStorageClient
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.remote.{RemoteDiskFileOperations, RemoteDiskStorageCopyFiles}
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.s3.S3FileOperations
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.s3.client.S3StorageClient
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.routes.StoragesRoutes
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.schemas.{storage => storagesSchemaId}
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.{StorageDeletionTask, StoragePermissionProviderImpl, Storages, StoragesStatistics}
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.api.JsonLdApi
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.{ContextValue, RemoteContextResolution}
import ch.epfl.bluebrain.nexus.delta.rdf.utils.JsonKeyOrdering
import ch.epfl.bluebrain.nexus.delta.sdk.IndexingAction.AggregateIndexingAction
import ch.epfl.bluebrain.nexus.delta.sdk._
import ch.epfl.bluebrain.nexus.delta.sdk.acls.AclCheck
import ch.epfl.bluebrain.nexus.delta.sdk.auth.AuthTokenProvider
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
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Label
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.Supervisor
import ch.epfl.bluebrain.nexus.delta.sourcing.{ScopedEventLog, Transactors}
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

  make[S3StorageClient].fromResource { (cfg: StoragePluginConfig) =>
    S3StorageClient.resource(cfg.storages.storageTypeConfig.amazon)
  }

  make[Storages]
    .fromEffect {
      (
          fetchContext: FetchContext,
          contextResolution: ResolverContextResolution,
          fileOperations: FileOperations,
          permissions: Permissions,
          xas: Transactors,
          cfg: StoragePluginConfig,
          serviceAccount: ServiceAccount,
          api: JsonLdApi,
          clock: Clock[IO],
          uuidF: UUIDF
      ) =>
        Storages(
          fetchContext,
          contextResolution,
          permissions.fetchPermissionSet,
          fileOperations,
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

  make[DiskFileOperations].from { (uuidF: UUIDF, as: ActorSystem[Nothing]) =>
    DiskFileOperations.mk(as.classicSystem, uuidF)
  }

  make[RemoteDiskFileOperations].from { (client: RemoteDiskStorageClient, uuidF: UUIDF) =>
    RemoteDiskFileOperations.mk(client)(uuidF)
  }

  make[S3FileOperations].from { (client: S3StorageClient, uuidF: UUIDF, as: ActorSystem[Nothing]) =>
    S3FileOperations.mk(client)(as.classicSystem, uuidF)
  }

  make[FileOperations].from { (disk: DiskFileOperations, remoteDisk: RemoteDiskFileOperations, s3: S3FileOperations) =>
    FileOperations.mk(disk, remoteDisk, s3)
  }

  make[Files].from {
    (
        cfg: StoragePluginConfig,
        aclCheck: AclCheck,
        fetchContext: FetchContext,
        storages: Storages,
        storagesStatistics: StoragesStatistics,
        xas: Transactors,
        clock: Clock[IO],
        uuidF: UUIDF,
        as: ActorSystem[Nothing],
        fileOps: FileOperations
    ) =>
      Files(
        fetchContext,
        aclCheck,
        storages,
        storagesStatistics,
        xas,
        cfg.files,
        fileOps,
        clock
      )(
        uuidF,
        as
      )
  }

  make[FileAttributesUpdateStream].fromEffect {
    (files: Files, storages: Storages, storageTypeConfig: StorageTypeConfig, supervisor: Supervisor) =>
      FileAttributesUpdateStream.start(files, storages, storageTypeConfig.remoteDisk, supervisor)
  }

  make[TransactionalFileCopier].fromValue(TransactionalFileCopier.mk())

  make[DiskStorageCopyFiles].from { (copier: TransactionalFileCopier, uuidf: UUIDF) =>
    DiskStorageCopyFiles.mk(copier, uuidf)
  }

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
      RemoteDiskStorageClient(client, authTokenProvider, cfg.remoteDisk)(as.classicSystem)
  }

  many[ServiceDependency].addSet {
    (
        cfg: StorageTypeConfig,
        remoteStorageClient: RemoteDiskStorageClient
    ) =>
      cfg.remoteDisk.fold(Set.empty[ServiceDependency]) { _ =>
        Set(new RemoteStorageServiceDependency(remoteStorageClient))
      }
  }

  many[ScopeInitialization].addSet { (storages: Storages, serviceAccount: ServiceAccount, cfg: StoragePluginConfig) =>
    Option.when(cfg.enableDefaultCreation)(StorageScopeInitialization(storages, serviceAccount, cfg.defaults)).toSet
  }

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
