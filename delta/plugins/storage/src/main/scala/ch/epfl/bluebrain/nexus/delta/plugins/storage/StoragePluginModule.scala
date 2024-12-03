package ch.epfl.bluebrain.nexus.delta.plugins.storage

import akka.actor.ActorSystem
import akka.http.scaladsl.model.Uri.Path
import akka.http.scaladsl.server.Directives.concat
import cats.effect.{Clock, IO}
import ch.epfl.bluebrain.nexus.delta.kernel.utils.{ClasspathResourceLoader, UUIDF}
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.client.ElasticSearchClient
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.config.ElasticSearchViewsConfig
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.Files.FilesLog
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.contexts.{files => fileCtxId}
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model._
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.routes.{DelegateFilesRoutes, FilesRoutes, LinkFilesRoutes}
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.schemas.{files => filesSchemaId}
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.{Files, FormDataExtractor, MediaTypeDetector}
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.StoragesConfig.{ShowFileLocation, StorageTypeConfig}
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages._
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.access.{S3StorageAccess, StorageAccess}
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.contexts.{storages => storageCtxId, storagesMetadata => storageMetaCtxId}
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model._
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.disk.DiskFileOperations
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.s3.client.S3StorageClient
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.s3.{S3FileOperations, S3LocationGenerator}
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.{FileOperations, LinkFileAction}
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.routes.StoragesRoutes
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.schemas.{storage => storagesSchemaId}
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.api.JsonLdApi
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.{ContextValue, RemoteContextResolution}
import ch.epfl.bluebrain.nexus.delta.rdf.utils.JsonKeyOrdering
import ch.epfl.bluebrain.nexus.delta.sdk.IndexingAction.AggregateIndexingAction
import ch.epfl.bluebrain.nexus.delta.sdk._
import ch.epfl.bluebrain.nexus.delta.sdk.acls.AclCheck
import ch.epfl.bluebrain.nexus.delta.sdk.deletion.ProjectDeletionTask
import ch.epfl.bluebrain.nexus.delta.sdk.directives.DeltaSchemeDirectives
import ch.epfl.bluebrain.nexus.delta.sdk.fusion.FusionConfig
import ch.epfl.bluebrain.nexus.delta.sdk.identities.Identities
import ch.epfl.bluebrain.nexus.delta.sdk.identities.model.ServiceAccount
import ch.epfl.bluebrain.nexus.delta.sdk.jws.JWSPayloadHelper
import ch.epfl.bluebrain.nexus.delta.sdk.model._
import ch.epfl.bluebrain.nexus.delta.sdk.model.metrics.ScopedEventMetricEncoder
import ch.epfl.bluebrain.nexus.delta.sdk.permissions.{Permissions, StoragePermissionProvider}
import ch.epfl.bluebrain.nexus.delta.sdk.projects.FetchContext
import ch.epfl.bluebrain.nexus.delta.sdk.projects.model.ApiMappings
import ch.epfl.bluebrain.nexus.delta.sdk.resolvers.ResolverContextResolution
import ch.epfl.bluebrain.nexus.delta.sdk.sse.SseEncoder
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Label
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

  make[S3StorageClient].fromResource { (cfg: StoragePluginConfig) =>
    S3StorageClient.resource(cfg.storages.storageTypeConfig.amazon)
  }

  make[S3LocationGenerator].from { (cfg: StoragePluginConfig) =>
    val prefix: Path = cfg.storages.storageTypeConfig.amazon.flatMap(_.prefix).getOrElse(Path.Empty)
    new S3LocationGenerator(prefix)
  }

  make[StorageAccess].from { (s3Client: S3StorageClient) => StorageAccess(S3StorageAccess(s3Client)) }

  make[Storages]
    .fromEffect {
      (
          fetchContext: FetchContext,
          contextResolution: ResolverContextResolution,
          storageAccess: StorageAccess,
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
          storageAccess,
          xas,
          cfg.storages,
          serviceAccount,
          clock
        )(
          api,
          uuidF
        )
    }

  make[FetchStorage].from { (storages: Storages, aclCheck: AclCheck) =>
    FetchStorage(storages, aclCheck)
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

  make[DiskFileOperations].from { (uuidF: UUIDF, as: ActorSystem) =>
    DiskFileOperations.mk(as, uuidF)
  }

  make[S3FileOperations].from {
    (client: S3StorageClient, locationGenerator: S3LocationGenerator, uuidF: UUIDF, as: ActorSystem) =>
      S3FileOperations.mk(client, locationGenerator)(as, uuidF)
  }

  make[FileOperations].from { (disk: DiskFileOperations, s3: S3FileOperations) =>
    FileOperations.apply(disk, s3)
  }

  make[MediaTypeDetector].from { (cfg: StoragePluginConfig) =>
    new MediaTypeDetector(cfg.files.mediaTypeDetector)
  }

  make[LinkFileAction].from {
    (fetchStorage: FetchStorage, mediaTypeDetector: MediaTypeDetector, s3FileOps: S3FileOperations) =>
      LinkFileAction(fetchStorage, mediaTypeDetector, s3FileOps)
  }

  make[Files].from {
    (
        cfg: StoragePluginConfig,
        fetchContext: FetchContext,
        fetchStorage: FetchStorage,
        xas: Transactors,
        clock: Clock[IO],
        uuidF: UUIDF,
        as: ActorSystem,
        fileOps: FileOperations,
        mediaTypeDetector: MediaTypeDetector,
        linkFileAction: LinkFileAction
    ) =>
      Files(
        fetchContext,
        fetchStorage,
        FormDataExtractor(mediaTypeDetector)(as),
        xas,
        cfg.files.eventLog,
        fileOps,
        linkFileAction,
        clock
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

  make[LinkFilesRoutes].from {
    (
        showLocation: ShowFileLocation,
        identities: Identities,
        aclCheck: AclCheck,
        files: Files,
        indexingAction: AggregateIndexingAction,
        shift: File.Shift,
        baseUri: BaseUri,
        cr: RemoteContextResolution @Id("aggregate"),
        ordering: JsonKeyOrdering
    ) =>
      new LinkFilesRoutes(identities, aclCheck, files, indexingAction(_, _, _)(shift))(
        baseUri,
        cr,
        ordering,
        showLocation
      )
  }

  make[DelegateFilesRoutes].from {
    (
        identities: Identities,
        jwsPayloadHelper: JWSPayloadHelper,
        aclCheck: AclCheck,
        files: Files,
        indexingAction: AggregateIndexingAction,
        shift: File.Shift,
        baseUri: BaseUri,
        cr: RemoteContextResolution @Id("aggregate"),
        ordering: JsonKeyOrdering,
        showLocation: ShowFileLocation
    ) =>
      new DelegateFilesRoutes(
        identities,
        aclCheck,
        files,
        jwsPayloadHelper,
        indexingAction(_, _, _)(shift)
      )(baseUri, cr, ordering, showLocation)
  }

  make[File.Shift].from { (files: Files, base: BaseUri, showLocation: ShowFileLocation) =>
    File.shift(files)(base, showLocation)
  }

  many[ResourceShift[_, _, _]].ref[File.Shift]

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
  many[PriorityRoute].add {
    (
        fileRoutes: FilesRoutes,
        linkFileRoutes: LinkFilesRoutes,
        delegationRoutes: DelegateFilesRoutes
    ) =>
      PriorityRoute(
        priority,
        concat(fileRoutes.routes, linkFileRoutes.routes, delegationRoutes.routes),
        requiresStrictEntity = false
      )
  }
}
