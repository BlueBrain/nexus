package ch.epfl.bluebrain.nexus.kg.config

import java.nio.file.Path

import akka.http.scaladsl.model.Uri
import akka.http.scaladsl.model.headers.BasicHttpCredentials
import ch.epfl.bluebrain.nexus.commons.cache.KeyValueStoreConfig
import ch.epfl.bluebrain.nexus.iam.auth.AccessToken
import ch.epfl.bluebrain.nexus.iam.types.Permission
import ch.epfl.bluebrain.nexus.kg.config.Contexts._
import ch.epfl.bluebrain.nexus.kg.config.KgConfig._
import ch.epfl.bluebrain.nexus.kg.config.Schemas._
import ch.epfl.bluebrain.nexus.kg.storage.Crypto
import ch.epfl.bluebrain.nexus.rdf.Iri.AbsoluteIri
import ch.epfl.bluebrain.nexus.sourcing.RetryStrategyConfig
import ch.epfl.bluebrain.nexus.sourcing.akka.aggregate.AggregateConfig
import ch.epfl.bluebrain.nexus.sourcing.akka.statemachine.StateMachineConfig
import ch.epfl.bluebrain.nexus.sourcing.projections.IndexingConfig
import io.circe.Json
import javax.crypto.SecretKey

import scala.concurrent.duration.FiniteDuration

/**
  * Application configuration
  *
  * @param storage           storages configuration
  * @param sparql            Sparql endpoint configuration
  * @param elasticSearch     ElasticSearch endpoint configuration
  * @param composite         Composite view configuration
  * @param keyValueStore     Distributed data configuration
  * @param aggregate         Aggregate configuration
  * @param archives          Archive collection cache configuration
  * @param defaultAskTimeout Default ask timeout for interaction with an Actor
  */
final case class KgConfig(
    storage: StorageConfig,
    sparql: SparqlConfig,
    elasticSearch: ElasticSearchConfig,
    composite: CompositeViewConfig,
    keyValueStore: StoreConfig,
    aggregate: AggregateConfig,
    archives: ArchivesConfig,
    defaultAskTimeout: FiniteDuration
)

object KgConfig {

  /**
    * The archives configuration
    *
    * @param cache                the underlying cache configuration
    * @param cacheInvalidateAfter the time resource is kept in the archive cache before being invalidated
    * @param maxResources         the maximum number of resources that can be contain in the archive
    */
  final case class ArchivesConfig(cache: StateMachineConfig, cacheInvalidateAfter: FiniteDuration, maxResources: Int)

  /**
    * KeyValueStore configuration.
    *
    * @param askTimeout         the maximum duration to wait for the replicator to reply
    * @param consistencyTimeout the maximum duration to wait for a consistent read or write across the cluster
    * @param retry              the retry strategy configuration
    * @param indexing           the indexing configuration
    */
  final case class StoreConfig(
      askTimeout: FiniteDuration,
      consistencyTimeout: FiniteDuration,
      retry: RetryStrategyConfig,
      indexing: IndexingConfig
  ) {
    val keyValueStoreConfig: KeyValueStoreConfig = KeyValueStoreConfig(askTimeout, consistencyTimeout, retry)
  }

  /**
    * Storage configuration for the allowed storages
    *
    * @param disk          the disk storage configuration
    * @param remoteDisk    the remote disk storage configuration
    * @param amazon        the amazon S3 storage configuration
    * @param password      the password used to encrypt credentials at rest
    * @param salt          the associated salt
    * @param fileAttrRetry the file attributes retry configuration
    * @param indexing      the indexing process dealing with attributes computation
    * @param askTimeout    the ask timeout to interact with the actor dealing with attributes computation
    */
  final case class StorageConfig(
      disk: DiskStorageConfig,
      remoteDisk: RemoteDiskStorageConfig,
      amazon: S3StorageConfig,
      password: String,
      salt: String,
      fileAttrRetry: RetryStrategyConfig,
      indexing: IndexingConfig,
      askTimeout: FiniteDuration
  ) {
    val derivedKey: SecretKey = Crypto.deriveKey(password, salt)
  }

  /**
    * Amazon S3 storage configuration
    *
    * @param digestAlgorithm algorithm for checksum calculation
    * @param readPermission  the default permission required in order to download a file from a s3 storage
    * @param writePermission the default permission required in order to upload a file to a s3 storage
    * @param showLocation    flag to decide whether or not to show the absolute location of the files in the metadata response
    * @param maxFileSize     the default maximum allowed file size (in bytes) for uploaded files
    */
  final case class S3StorageConfig(
      digestAlgorithm: String,
      readPermission: Permission,
      writePermission: Permission,
      showLocation: Boolean,
      maxFileSize: Long
  )

  /**
    * Disk storage configuration
    *
    * @param volume          the base [[Path]] where the files are stored
    * @param digestAlgorithm algorithm for checksum calculation
    * @param readPermission  the default permission required in order to download a file from a disk storage
    * @param writePermission the default permission required in order to upload a file to a disk storage
    * @param showLocation    flag to decide whether or not to show the absolute location of the files in the metadata response
    * @param maxFileSize     the default maximum allowed file size (in bytes) for uploaded files
    */
  final case class DiskStorageConfig(
      volume: Path,
      digestAlgorithm: String,
      readPermission: Permission,
      writePermission: Permission,
      showLocation: Boolean,
      maxFileSize: Long
  )

  /**
    * Remote Disk storage configuration
    *
    * @param defaultEndpoint       the default endpoint of the current storage
    * @param defaultEndpointPrefix the default endpoint prefix
    * @param defaultCredentials    the default credentials for the defaultEnpoint of the current storage
    * @param readPermission        the default permission required in order to download a file from a disk storage
    * @param writePermission       the default permission required in order to upload a file to a disk storage
    * @param showLocation          flag to decide whether or not to show the absolute location of the files in the metadata response
    * @param maxFileSize           the default maximum allowed file size (in bytes) for uploaded files
    */
  final case class RemoteDiskStorageConfig(
      defaultEndpoint: Uri,
      defaultEndpointPrefix: String,
      defaultCredentials: Option[AccessToken],
      digestAlgorithm: String,
      readPermission: Permission,
      writePermission: Permission,
      showLocation: Boolean,
      maxFileSize: Long
  ) {
    val endpoint: Uri =
      if (defaultEndpointPrefix.trim.isEmpty) defaultEndpoint
      else s"$defaultEndpoint/$defaultEndpointPrefix"
  }

  /**
    * Collection of configurable settings specific to the Sparql indexer.
    *
    * @param base         the base uri
    * @param indexPrefix  the prefix of the index
    * @param username     the SPARQL endpoint username
    * @param password     the SPARQL endpoint password
    * @param defaultIndex the SPARQL default index
    * @param indexing     the indexing configuration
    * @param query        the query retry strategy configuration
    * @param askTimeout   the ask timeout to interact with the index actor
    */
  final case class SparqlConfig(
      base: Uri,
      indexPrefix: String,
      username: Option[String],
      password: Option[String],
      defaultIndex: String,
      indexing: IndexingConfig,
      query: RetryStrategyConfig,
      askTimeout: FiniteDuration
  ) {

    val akkaCredentials: Option[BasicHttpCredentials] =
      for {
        user <- username
        pass <- password
      } yield BasicHttpCredentials(user, pass)
  }

  /**
    * Collection of configurable settings specific to the ElasticSearch indexer.
    *
    * @param base         the application base uri for operating on resources
    * @param indexPrefix  the prefix of the index
    * @param defaultIndex the default index
    * @param indexing     the indexing configuration
    * @param query        the query retry strategy configuration
    * @param askTimeout   the ask timeout to interact with the index actor
    */
  final case class ElasticSearchConfig(
      base: Uri,
      indexPrefix: String,
      defaultIndex: String,
      indexing: IndexingConfig,
      query: RetryStrategyConfig,
      askTimeout: FiniteDuration
  )

  /**
    * Composite view configuration
    *
    * @param maxSources         the maximum number of sources allowed
    * @param maxProjections     the maximum number of projections allowed
    * @param minIntervalRebuild the minimum allowed value for interval rebuild
    * @param password           the password used to encrypt token
    * @param salt               the associated salt
    */
  final case class CompositeViewConfig(
      maxSources: Int,
      maxProjections: Int,
      minIntervalRebuild: FiniteDuration,
      password: String,
      salt: String
  ) {
    val derivedKey: SecretKey = Crypto.deriveKey(password, salt)
  }

  val iriResolution: Map[AbsoluteIri, Json] = Map(
    archiveCtxUri     -> archiveCtx,
    tagCtxUri         -> tagCtx,
    fileAttrCtxUri    -> fileAttrCtx,
    statisticsCtxUri  -> statisticsCtx,
    offsetCtxUri      -> offsetCtx,
    resourceCtxUri    -> resourceCtx,
    shaclCtxUri       -> shaclCtx,
    resolverCtxUri    -> resolverCtx,
    viewCtxUri        -> viewCtx,
    storageCtxUri     -> storageCtx,
    resolverSchemaUri -> resolverSchema,
    viewSchemaUri     -> viewSchema,
    storageSchemaUri  -> storageSchema
  )

  implicit def toSparql(implicit appConfig: KgConfig): SparqlConfig                           = appConfig.sparql
  implicit def toElasticSearch(implicit appConfig: KgConfig): ElasticSearchConfig             = appConfig.elasticSearch
  implicit def toAggregateConfig(implicit appConfig: KgConfig): AggregateConfig               = appConfig.aggregate
  implicit def toStore(implicit appConfig: KgConfig): StoreConfig                             = appConfig.keyValueStore
  implicit def toKVS(implicit appConfig: KgConfig): KeyValueStoreConfig                       = appConfig.keyValueStore.keyValueStoreConfig
  implicit def toStorage(implicit appConfig: KgConfig): StorageConfig                         = appConfig.storage
  implicit def toSecretKeyStorage(implicit storageConfig: StorageConfig): SecretKey           = storageConfig.derivedKey
  implicit def toSecretKeyComposite(implicit compositeConfig: CompositeViewConfig): SecretKey =
    compositeConfig.derivedKey
  implicit def toCompositeConfig(implicit appConfig: KgConfig): CompositeViewConfig           = appConfig.composite
  implicit def toArchivesConfig(implicit appConfig: KgConfig): ArchivesConfig                 =
    appConfig.archives

}
