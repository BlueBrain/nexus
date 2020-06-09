package ch.epfl.bluebrain.nexus.service.config

import akka.http.scaladsl.model.Uri
import ch.epfl.bluebrain.nexus.admin.config.AdminConfig
import ch.epfl.bluebrain.nexus.commons.http.JsonLdCirceSupport.OrderedKeys
import ch.epfl.bluebrain.nexus.iam.config.IamConfig
import ch.epfl.bluebrain.nexus.rdf.Iri.AbsoluteIri
import ch.epfl.bluebrain.nexus.rdf.implicits._
import ch.epfl.bluebrain.nexus.service.config.ServiceConfig.{ClusterConfig, Description, HttpConfig, PersistenceConfig}
import ch.epfl.bluebrain.nexus.service.config.Vocabulary.nxv

import scala.concurrent.duration.FiniteDuration

case class ServiceConfig(
    description: Description,
    cluster: ClusterConfig,
    persistence: PersistenceConfig,
    http: HttpConfig,
    admin: AdminConfig,
    iam: IamConfig
)

object ServiceConfig {

  /**
    * Service description
    *
    * @param name service name
    */
  final case class Description(name: String) {

    /**
      * @return the version of the service
      */
    //    val version: String = BuildInfo.version
    val version: String = "SNAPSHOT"

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

  val orderedKeys: OrderedKeys = OrderedKeys(
    List(
      "@context",
      "@id",
      "@type",
      "code",
      "message",
      "details",
      nxv.reason.prefix,
      nxv.total.prefix,
      nxv.maxScore.prefix,
      nxv.results.prefix,
      nxv.score.prefix,
      nxv.description.name,
      nxv.`@base`.name,
      nxv.`@vocab`.name,
      nxv.apiMappings.name,
      nxv.prefix.name,
      nxv.namespace.name,
      "",
      nxv.label.prefix,
      nxv.organizationUuid.prefix,
      nxv.organizationLabel.prefix,
      nxv.path.prefix,
      nxv.grantTypes.prefix,
      nxv.issuer.prefix,
      nxv.keys.prefix,
      nxv.authorizationEndpoint.prefix,
      nxv.tokenEndpoint.prefix,
      nxv.userInfoEndpoint.prefix,
      nxv.revocationEndpoint.prefix,
      nxv.endSessionEndpoint.prefix,
      nxv.self.prefix,
      nxv.constrainedBy.prefix,
      nxv.project.prefix,
      nxv.rev.prefix,
      nxv.deprecated.prefix,
      nxv.createdAt.prefix,
      nxv.createdBy.prefix,
      nxv.updatedAt.prefix,
      nxv.updatedBy.prefix,
      nxv.instant.prefix,
      nxv.subject.prefix
    )
  )
}
