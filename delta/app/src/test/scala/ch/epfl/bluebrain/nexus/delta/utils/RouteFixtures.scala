package ch.epfl.bluebrain.nexus.delta.utils

import akka.http.scaladsl.server.RejectionHandler
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.{contexts, schemas}
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.RemoteContextResolution
import ch.epfl.bluebrain.nexus.delta.rdf.utils.JsonKeyOrdering
import ch.epfl.bluebrain.nexus.delta.routes.marshalling.RdfRejectionHandler
import ch.epfl.bluebrain.nexus.delta.sdk.model.acls.AclAddress
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Identity.{Anonymous, Subject, User}
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.{ApiMappings, ProjectRef}
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.PaginationConfig
import ch.epfl.bluebrain.nexus.delta.sdk.model.{AccessUrl, BaseUri, Label}
import ch.epfl.bluebrain.nexus.testkit.TestHelpers
import io.circe.Json
import monix.execution.Scheduler

trait RouteFixtures extends TestHelpers {

  implicit def rcr: RemoteContextResolution =
    RemoteContextResolution.fixed(
      contexts.resource      -> jsonContentOf("contexts/resource.json"),
      contexts.error         -> jsonContentOf("contexts/error.json"),
      contexts.organizations -> jsonContentOf("contexts/organizations.json"),
      contexts.identities    -> jsonContentOf("contexts/identities.json"),
      contexts.permissions   -> jsonContentOf("contexts/permissions.json"),
      contexts.projects      -> jsonContentOf("contexts/projects.json"),
      contexts.realms        -> jsonContentOf("contexts/realms.json")
    )

  implicit val ordering: JsonKeyOrdering = JsonKeyOrdering.alphabetical

  implicit val baseUri: BaseUri                   = BaseUri("http://localhost", Label.unsafe("v1"))
  implicit val paginationConfig: PaginationConfig = PaginationConfig(5, 10, 5)
  implicit val s: Scheduler                       = Scheduler.global
  implicit val rejectionHandler: RejectionHandler = RdfRejectionHandler.apply

  val realm: Label = Label.unsafe("wonderland")
  val alice: User  = User("alice", realm)

  def projectResourceUnit(
      ref: ProjectRef,
      rev: Long = 1L,
      deprecated: Boolean = false,
      createdBy: Subject = Anonymous,
      updatedBy: Subject = Anonymous,
      mappings: ApiMappings = ApiMappings.empty
  ): Json = {
    val accessUrl = AccessUrl.project(ref)
    resourceUnit(
      accessUrl.iri,
      accessUrl,
      "Project",
      schemas.projects,
      rev,
      deprecated,
      createdBy,
      updatedBy,
      mappings
    )
  }

  def orgResourceUnit(
      label: Label,
      rev: Long = 1L,
      deprecated: Boolean = false,
      createdBy: Subject = Anonymous,
      updatedBy: Subject = Anonymous,
      mappings: ApiMappings = ApiMappings.empty
  ): Json = {
    val accessUrl = AccessUrl.organization(label)
    resourceUnit(
      accessUrl.iri,
      accessUrl,
      "Organization",
      schemas.organizations,
      rev,
      deprecated,
      createdBy,
      updatedBy,
      mappings
    )
  }

  def permissionsResourceUnit(
      rev: Long = 1L,
      deprecated: Boolean = false,
      createdBy: Subject = Anonymous,
      updatedBy: Subject = Anonymous,
      mappings: ApiMappings = ApiMappings.empty
  ): Json = {
    val accessUrl = AccessUrl.permissions
    resourceUnit(
      accessUrl.iri,
      accessUrl,
      "Permissions",
      schemas.permissions,
      rev,
      deprecated,
      createdBy,
      updatedBy,
      mappings
    )
  }

  def aclResourceUnit(
      address: AclAddress,
      rev: Long = 1L,
      deprecated: Boolean = false,
      createdBy: Subject = Anonymous,
      updatedBy: Subject = Anonymous,
      mappings: ApiMappings = ApiMappings.empty
  ): Json = {
    val accessUrl = AccessUrl.acl(address)
    resourceUnit(
      accessUrl.iri,
      accessUrl,
      "AccessControlList",
      schemas.acls,
      rev,
      deprecated,
      createdBy,
      updatedBy,
      mappings
    )
  }

  def realmsResourceUnit(
      label: Label,
      rev: Long = 1L,
      deprecated: Boolean = false,
      createdBy: Subject = Anonymous,
      updatedBy: Subject = Anonymous,
      mappings: ApiMappings = ApiMappings.empty
  ): Json = {
    val accessUrl = AccessUrl.realm(label)
    resourceUnit(
      accessUrl.iri,
      accessUrl,
      "Realm",
      schemas.realms,
      rev,
      deprecated,
      createdBy,
      updatedBy,
      mappings
    )
  }

  private def resourceUnit(
      id: Iri,
      accessUrl: AccessUrl,
      tpe: String,
      schema: Iri,
      rev: Long,
      deprecated: Boolean,
      createdBy: Subject,
      updatedBy: Subject,
      mappings: ApiMappings
  ): Json =
    jsonContentOf(
      "resource-unit.json",
      "id"         -> id,
      "type"       -> tpe,
      "schema"     -> schema,
      "deprecated" -> deprecated,
      "rev"        -> rev,
      "createdBy"  -> createdBy.id,
      "updatedBy"  -> updatedBy.id,
      "self"       -> accessUrl.shortForm(mappings)
    )

}
