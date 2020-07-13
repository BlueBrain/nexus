package ch.epfl.bluebrain.nexus.delta.config

import java.nio.file.Path

import akka.http.scaladsl.model.Uri
import akka.http.scaladsl.model.headers.BasicHttpCredentials
import ch.epfl.bluebrain.nexus.commons.cache.KeyValueStoreConfig
import ch.epfl.bluebrain.nexus.commons.http.JsonLdCirceSupport.OrderedKeys
import ch.epfl.bluebrain.nexus.iam.auth.AccessToken
import ch.epfl.bluebrain.nexus.iam.types.Permission
import ch.epfl.bluebrain.nexus.kg.config.Contexts._
import ch.epfl.bluebrain.nexus.kg.config.Schemas._
import ch.epfl.bluebrain.nexus.kg.storage.Crypto
import ch.epfl.bluebrain.nexus.rdf.Iri.AbsoluteIri
import ch.epfl.bluebrain.nexus.rdf.implicits._
import ch.epfl.bluebrain.nexus.delta.config.AppConfig._
import ch.epfl.bluebrain.nexus.delta.config.Vocabulary.nxv
import ch.epfl.bluebrain.nexus.sourcing.RetryStrategyConfig
import ch.epfl.bluebrain.nexus.sourcing.akka.aggregate.AggregateConfig
import ch.epfl.bluebrain.nexus.sourcing.akka.statemachine.StateMachineConfig
import ch.epfl.bluebrain.nexus.sourcing.projections.IndexingConfig
import io.circe.Json
import javax.crypto.SecretKey

import scala.concurrent.duration.FiniteDuration

final case class AppConfig(
    description: Description,
    cluster: ClusterConfig,
    persistence: PersistenceConfig,
    http: HttpConfig,
    pagination: PaginationConfig,
    indexing: IndexingConfig,
    keyValueStore: StoreConfig,
    aggregate: AggregateConfig,
    acls: AclsConfig,
    permissions: PermissionsConfig,
    realms: RealmsConfig,
    groups: StateMachineConfig,
    organizations: OrgsConfig,
    projects: ProjectsConfig,
    storage: StorageConfig,
    sparql: SparqlConfig,
    elasticSearch: ElasticSearchConfig,
    composite: CompositeViewConfig,
    archives: ArchivesConfig,
    defaultAskTimeout: FiniteDuration,
    serviceAccount: ServiceAccountConfig
)

object AppConfig {

  /**
    * Service description
    *
    * @param name service name
    */
  final case class Description(name: String) {

    /**
      * @return the version of the service
      */
    val version: String = BuildInfo.version

    /**
      * @return the full name of the service (name + version)
      */
    val fullName: String = s"$name-${version.replaceAll("\\W", "-")}"

  }

  /**
    * HTTP configuration
    *
    * @param interface  interface to bind to
    * @param port       port to bind to
    * @param prefix     prefix to add to HTTP routes
    * @param publicUri  public URI of the service
    */
  final case class HttpConfig(interface: String, port: Int, prefix: String, publicUri: Uri) {
    lazy val publicIri: AbsoluteIri      = url"$publicUri"
    lazy val prefixIri: AbsoluteIri      = url"$publicUri/$prefix"
    lazy val aclsIri: AbsoluteIri        = url"$publicUri/$prefix/acls"
    lazy val permissionsIri: AbsoluteIri = url"$publicUri/$prefix/permissions"
    lazy val realmsIri: AbsoluteIri      = url"$publicUri/$prefix/realms"
    lazy val projectsIri: AbsoluteIri    = prefixIri + "projects"
    lazy val orgsBaseIri: AbsoluteIri    = prefixIri + "orgs"

  }

  /**
    * Cluster configuration
    *
    * @param passivationTimeout actor passivation timeout
    * @param replicationTimeout replication / distributed data timeout
    * @param shards             number of shards in the cluster
    * @param seeds              seed nodes in the cluster
    */
  final case class ClusterConfig(
      passivationTimeout: FiniteDuration,
      replicationTimeout: FiniteDuration,
      shards: Int,
      seeds: Option[String]
  )

  /**
    * Persistence configuration
    *
    * @param journalPlugin        plugin for storing events
    * @param snapshotStorePlugin  plugin for storing snapshots
    * @param queryJournalPlugin   plugin for querying journal events
    */
  final case class PersistenceConfig(journalPlugin: String, snapshotStorePlugin: String, queryJournalPlugin: String)

  /**
    * Pagination configuration
    *
   * @param defaultSize the default number of results per page
    * @param sizeLimit   the maximum number of results per page
    * @param fromLimit   the maximum value of `from` parameter
    */
  final case class PaginationConfig(defaultSize: Int, sizeLimit: Int, fromLimit: Int)

  /**
    * ACLs configuration
    *
   * @param aggregate the acls aggregate configuration
    * @param indexing  the indexing configuration
    */
  final case class AclsConfig(aggregate: AggregateConfig, indexing: IndexingConfig)

  /**
    * Permissions configuration.
    *
   * @param aggregate the permissions aggregate configuration
    * @param minimum   the minimum set of permissions
    * @param owner     permissions applied to the creator of the project
    */
  final case class PermissionsConfig(aggregate: AggregateConfig, minimum: Set[Permission], owner: Set[String]) {
    def ownerPermissions: Set[Permission] = owner.map(Permission.unsafe)
  }

  /**
    * Realms configuration.
    *
   * @param aggregate     the realms aggregate configuration
    * @param keyValueStore the key value store configuration
    * @param indexing      the indexing configuration
    */
  final case class RealmsConfig(
      aggregate: AggregateConfig,
      keyValueStore: KeyValueStoreConfig,
      indexing: IndexingConfig
  )

  /**
    * Organizations configuration
    *
   * @param retry the retry strategy configuration
    */
  final case class OrgsConfig(retry: RetryStrategyConfig)

  /**
    * Projects configuration
    *
   * @param retry the retry strategy configuration
    */
  final case class ProjectsConfig(retry: RetryStrategyConfig)

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
    * @param digestAlgorithm       the default digest algorithm of the current storage
    * @param readPermission        the default permission required in order to download a file from a remote disk storage
    * @param writePermission       the default permission required in order to upload a file to a remote disk storage
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

  /**
    * Service account configuration
    *
   * @param token the service account token
    */
  final case class ServiceAccountConfig(token: Option[String]) {
    def credentials: Option[AccessToken] = token.map(AccessToken)
  }

  val orderedKeys: OrderedKeys = OrderedKeys(
    List(
      "@context",
      "@id",
      "@type",
      "code",
      "message",
      "details",
      nxv.reason.prefix,
      nxv.description.name,
      nxv.`@base`.name,
      nxv.`@vocab`.name,
      nxv.apiMappings.name,
      nxv.prefix.name,
      nxv.namespace.name,
      nxv.total.prefix,
      nxv.maxScore.prefix,
      nxv.results.prefix,
      nxv.score.prefix,
      nxv.resourceId.prefix,
      nxv.organization.prefix,
      "sourceId",
      "projectionId",
      "totalEvents",
      "processedEvents",
      "evaluatedEvents",
      "remainingEvents",
      "discardedEvents",
      "failedEvents",
      "sources",
      "projections",
      "rebuildStrategy",
      nxv.project.prefix,
      "",
      nxv.label.prefix,
      nxv.organizationUuid.prefix,
      nxv.organizationLabel.prefix,
      "_path",
      nxv.grantTypes.prefix,
      nxv.issuer.prefix,
      nxv.keys.prefix,
      nxv.authorizationEndpoint.prefix,
      nxv.tokenEndpoint.prefix,
      nxv.userInfoEndpoint.prefix,
      nxv.revocationEndpoint.prefix,
      nxv.endSessionEndpoint.prefix,
      "readPermission",
      "writePermission",
      nxv.algorithm.prefix,
      nxv.self.prefix,
      nxv.constrainedBy.prefix,
      nxv.project.prefix,
      nxv.projectUuid.prefix,
      nxv.organizationUuid.prefix,
      nxv.rev.prefix,
      nxv.deprecated.prefix,
      nxv.createdAt.prefix,
      nxv.createdBy.prefix,
      nxv.updatedAt.prefix,
      nxv.updatedBy.prefix,
      nxv.incoming.prefix,
      nxv.outgoing.prefix,
      nxv.instant.prefix,
      nxv.expiresInSeconds.prefix,
      nxv.eventSubject.prefix
    )
  )

}
