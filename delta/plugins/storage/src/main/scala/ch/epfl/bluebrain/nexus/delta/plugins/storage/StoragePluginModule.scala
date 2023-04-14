package ch.epfl.bluebrain.nexus.delta.plugins.storage

import akka.actor
import akka.actor.typed.ActorSystem
import cats.effect.Clock
import ch.epfl.bluebrain.nexus.delta.kernel.RetryStrategyConfig
import ch.epfl.bluebrain.nexus.delta.kernel.database.Transactors
import ch.epfl.bluebrain.nexus.delta.kernel.utils.UUIDF
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.client.ElasticSearchClient
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.config.ElasticSearchViewsConfig
import ch.epfl.bluebrain.nexus.delta.plugins.storage.StoragePluginModule.{enrichJsonFileEvent, injectFileStorageInfo, injectStorageDefaults}
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.Files
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.contexts.{files => fileCtxId}
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model.FileEvent._
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model._
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.routes.FilesRoutes
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.schemas.{files => filesSchemaId}
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.StoragesConfig.StorageTypeConfig
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.contexts.{storages => storageCtxId, storagesMetadata => storageMetaCtxId}
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.StorageEvent.{StorageCreated, StorageUpdated}
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.StorageValue.{DiskStorageValue, RemoteDiskStorageValue, S3StorageValue}
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model._
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.StorageAccess
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.remote.client.RemoteDiskStorageClient
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.routes.StoragesRoutes
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.schemas.{storage => storagesSchemaId}
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.{StorageDeletionTask, Storages, StoragesStatistics}
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
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
import ch.epfl.bluebrain.nexus.delta.sdk.migration.{MigrationLog, MigrationState}
import ch.epfl.bluebrain.nexus.delta.sdk.model._
import ch.epfl.bluebrain.nexus.delta.sdk.model.metrics.ScopedEventMetricEncoder
import ch.epfl.bluebrain.nexus.delta.sdk.permissions.Permissions
import ch.epfl.bluebrain.nexus.delta.sdk.projects.FetchContext
import ch.epfl.bluebrain.nexus.delta.sdk.projects.FetchContext.ContextRejection
import ch.epfl.bluebrain.nexus.delta.sdk.projects.model.ApiMappings
import ch.epfl.bluebrain.nexus.delta.sdk.resolvers.ResolverContextResolution
import ch.epfl.bluebrain.nexus.delta.sdk.sse.SseEncoder
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Label
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.Supervisor
import com.typesafe.config.Config
import io.circe.syntax.EncoderOps
import io.circe.{Json, JsonObject}
import izumi.distage.model.definition.{Id, ModuleDef}
import monix.bio.{IO, Task, UIO}
import monix.execution.Scheduler

/**
  * Storages and Files wiring
  */
class StoragePluginModule(priority: Int) extends ModuleDef {

  implicit private val classLoader: ClassLoader = getClass.getClassLoader

  make[StoragePluginConfig].fromEffect { cfg: Config => StoragePluginConfig.load(cfg) }

  make[StorageTypeConfig].from { cfg: StoragePluginConfig => cfg.storages.storageTypeConfig }

  make[HttpClient].named("storage").from { (as: ActorSystem[Nothing], sc: Scheduler) =>
    val clientConfig = HttpClientConfig(RetryStrategyConfig.AlwaysGiveUp, HttpClientWorthRetry.never, true)
    HttpClient()(
      clientConfig,
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
        crypto: Crypto,
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
          crypto,
          s,
          cr,
          ordering,
          fusionConfig
        )
      }
  }

  make[Storage.Shift].from { (storages: Storages, base: BaseUri, crypto: Crypto) =>
    Storage.shift(storages)(base, crypto)
  }

  many[ResourceShift[_, _, _]].ref[Storage.Shift]

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
              as
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
    (cfg: StorageTypeConfig, client: HttpClient @Id("storage"), as: ActorSystem[Nothing]) =>
      val remoteStorageClient = cfg.remoteDisk.map { r =>
        new RemoteDiskStorageClient(r.defaultEndpoint)(client, as.classicSystem)
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

  many[SseEncoder[_]].add { (crypto: Crypto, base: BaseUri) => StorageEvent.sseEncoder(crypto)(base) }
  many[SseEncoder[_]].add { (base: BaseUri, config: StorageTypeConfig) => FileEvent.sseEncoder(base, config) }

  many[ScopedEventMetricEncoder[_]].add { FileEvent.fileEventMetricEncoder }
  many[ScopedEventMetricEncoder[_]].add { (crypto: Crypto) => StorageEvent.storageEventMetricEncoder(crypto) }

  many[PriorityRoute].add { (storagesRoutes: StoragesRoutes) =>
    PriorityRoute(priority, storagesRoutes.routes, requiresStrictEntity = true)
  }
  many[PriorityRoute].add { (fileRoutes: FilesRoutes) =>
    PriorityRoute(priority, fileRoutes.routes, requiresStrictEntity = false)
  }

  if (MigrationState.isRunning) {
    // Storages
    many[MigrationLog].add { (cfg: StoragePluginConfig, xas: Transactors, clock: Clock[UIO], crypto: Crypto) =>
      MigrationLog.scoped[Iri, StorageState, StorageCommand, StorageEvent, StorageRejection](
        Storages.definition(
          cfg.storages.storageTypeConfig,
          (_, _) => IO.terminate(new IllegalStateException("Storage command evaluation should not happen")),
          UIO.terminate(new IllegalStateException("Storage command evaluation should not happen")),
          crypto
        )(clock),
        e => e.id,
        identity,
        (e, _) => injectStorageDefaults(cfg.defaults)(e),
        cfg.storages.eventLog,
        xas
      )
    }

    // Files
    many[MigrationLog].add { (cfg: StoragePluginConfig, xas: Transactors, clock: Clock[UIO]) =>
      MigrationLog.scoped[Iri, FileState, FileCommand, FileEvent, FileRejection](
        Files.definition(clock),
        e => e.id,
        enrichJsonFileEvent,
        injectFileStorageInfo,
        cfg.files.eventLog,
        xas
      )
    }

  }
}

// TODO: This object contains migration helpers, and should be deleted when the migration module is removed
object StoragePluginModule {

  private def setStorageDefaults(name: Option[String], description: Option[String]): StorageValue => StorageValue = {
    case disk: DiskStorageValue         => disk.copy(name = name, description = description)
    case s3: S3StorageValue             => s3.copy(name = name, description = description)
    case remote: RemoteDiskStorageValue => remote.copy(name = name, description = description)
  }

  def injectStorageDefaults(defaults: Defaults): StorageEvent => StorageEvent = {
    case s @ StorageCreated(id, _, value, _, _, _, _) if id == storages.defaultStorageId =>
      s.copy(value = setStorageDefaults(Some(defaults.name), Some(defaults.description))(value))
    case s @ StorageUpdated(id, _, value, _, _, _, _) if id == storages.defaultStorageId =>
      s.copy(value = setStorageDefaults(Some(defaults.name), Some(defaults.description))(value))
    case event                                                                           => event
  }

  /**
    * Enriches a json with the storage and storage type, only if both fields are not present. This is to ensure that the
    * json can be decoded into a FileEvent later.
    */
  def enrichJsonFileEvent: Json => Json = { input =>
    val migrationFields = JsonObject(
      "storage"     -> Json.fromString("https://bluebrain.github.io/nexus/vocabulary/migration-storage?rev=1"),
      "storageType" -> Json.fromString("DiskStorage")
    )

    input.asObject match {
      case Some(eventObject) =>
        if (eventObject.contains("storage") && eventObject.contains("storageType")) input
        else migrationFields.asJson.deepMerge(input)
      case None              => input
    }
  }

  def injectFileStorageInfo: (FileEvent, Option[FileState]) => FileEvent = (e, s) =>
    s match {
      case Some(state) =>
        e match {
          case f: FileCreated           => f
          case f: FileUpdated           => f
          case f: FileAttributesUpdated => f.copy(storage = state.storage, storageType = state.storageType)
          case f: FileTagAdded          => f.copy(storage = state.storage, storageType = state.storageType)
          case f: FileTagDeleted        => f.copy(storage = state.storage, storageType = state.storageType)
          case f: FileDeprecated        => f.copy(storage = state.storage, storageType = state.storageType)
        }
      case None        => e
    }

}
