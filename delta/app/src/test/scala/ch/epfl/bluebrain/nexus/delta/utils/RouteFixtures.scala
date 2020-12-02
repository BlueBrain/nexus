package ch.epfl.bluebrain.nexus.delta.utils

import java.util.UUID
import akka.http.scaladsl.server.{ExceptionHandler, RejectionHandler}
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.{contexts, nxv, schemas}
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.RemoteContextResolution
import ch.epfl.bluebrain.nexus.delta.rdf.utils.JsonKeyOrdering
import ch.epfl.bluebrain.nexus.delta.routes.marshalling.{RdfExceptionHandler, RdfRejectionHandler}
import ch.epfl.bluebrain.nexus.delta.sdk.model.acls.AclAddress
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Identity.{Anonymous, Subject, User}
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.{ApiMappings, ProjectBase, ProjectRef}
import ch.epfl.bluebrain.nexus.delta.sdk.model.resolvers.ResolverType
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.PaginationConfig
import ch.epfl.bluebrain.nexus.delta.sdk.model.{BaseUri, Label, ResourceRef, ResourceUris}
import ch.epfl.bluebrain.nexus.delta.sdk.implicits._
import ch.epfl.bluebrain.nexus.testkit.TestHelpers
import io.circe.Json
import io.circe.syntax.EncoderOps
import monix.execution.Scheduler

trait RouteFixtures extends TestHelpers {

  implicit def rcr: RemoteContextResolution =
    RemoteContextResolution.fixed(
      contexts.acls          -> jsonContentOf("contexts/acls.json"),
      contexts.metadata      -> jsonContentOf("contexts/metadata.json"),
      contexts.error         -> jsonContentOf("contexts/error.json"),
      contexts.organizations -> jsonContentOf("contexts/organizations.json"),
      contexts.identities    -> jsonContentOf("contexts/identities.json"),
      contexts.permissions   -> jsonContentOf("contexts/permissions.json"),
      contexts.projects      -> jsonContentOf("contexts/projects.json"),
      contexts.realms        -> jsonContentOf("contexts/realms.json"),
      contexts.resolvers     -> jsonContentOf("contexts/resolvers.json"),
      contexts.search        -> jsonContentOf("contexts/search.json"),
      contexts.shacl         -> jsonContentOf("contexts/shacl.json"),
      contexts.tags          -> jsonContentOf("contexts/tags.json")
    )

  implicit val ordering: JsonKeyOrdering = JsonKeyOrdering.alphabetical

  implicit val baseUri: BaseUri                   = BaseUri("http://localhost", Label.unsafe("v1"))
  implicit val paginationConfig: PaginationConfig = PaginationConfig(5, 10, 5)
  implicit val s: Scheduler                       = Scheduler.global
  implicit val rejectionHandler: RejectionHandler = RdfRejectionHandler.apply
  implicit val exceptionHandler: ExceptionHandler = RdfExceptionHandler.apply

  val realm: Label = Label.unsafe("wonderland")
  val alice: User  = User("alice", realm)
  val bob: User    = User("bob", realm)

  def schemaResourceUnit(
      ref: ProjectRef,
      id: Iri,
      rev: Long = 1L,
      deprecated: Boolean = false,
      createdBy: Subject = Anonymous,
      updatedBy: Subject = Anonymous,
      am: ApiMappings = ApiMappings.empty,
      base: Iri = nxv.base
  ): Json = {
    val resourceUris = ResourceUris.schema(ref, id)(am, ProjectBase.unsafe(base))
    resourceUnit(
      id,
      resourceUris,
      """"Schema"""",
      schemas.shacl,
      rev,
      deprecated,
      createdBy,
      updatedBy
    )
  }

  def dataResourceUnit(
      ref: ProjectRef,
      id: Iri,
      schema: Iri,
      tpe: String,
      rev: Long = 1L,
      deprecated: Boolean = false,
      createdBy: Subject = Anonymous,
      updatedBy: Subject = Anonymous,
      am: ApiMappings = ApiMappings.empty,
      base: Iri = nxv.base
  ): Json = {
    val resourceUris = ResourceUris.resource(ref, id, ResourceRef(schema))(am, ProjectBase.unsafe(base))
    resourceUnit(
      id,
      resourceUris,
      s""""$tpe"""",
      schema,
      rev,
      deprecated,
      createdBy,
      updatedBy
    )
  }

  def projectResourceUnit(
      ref: ProjectRef,
      label: String,
      uuid: UUID,
      organizationLabel: String,
      organizationUuid: UUID,
      rev: Long = 1L,
      deprecated: Boolean = false,
      createdBy: Subject = Anonymous,
      updatedBy: Subject = Anonymous
  ): Json = {
    val resourceUris = ResourceUris.project(ref)
    resourceUnit(
      resourceUris.accessUri.toIri,
      resourceUris,
      """"Project"""",
      schemas.projects,
      rev,
      deprecated,
      createdBy,
      updatedBy,
      additionalMetadata = Json.obj(
        "_label"             -> label.asJson,
        "_uuid"              -> uuid.asJson,
        "_organizationLabel" -> organizationLabel.asJson,
        "_organizationUuid"  -> organizationUuid.asJson
      )
    )
  }

  def orgResourceUnit(
      label: Label,
      uuid: UUID,
      rev: Long = 1L,
      deprecated: Boolean = false,
      createdBy: Subject = Anonymous,
      updatedBy: Subject = Anonymous
  ): Json = {
    val resourceUris = ResourceUris.organization(label)
    resourceUnit(
      resourceUris.accessUri.toIri,
      resourceUris,
      """"Organization"""",
      schemas.organizations,
      rev,
      deprecated,
      createdBy,
      updatedBy,
      additionalMetadata = Json.obj("_label" -> label.asJson, "_uuid" -> uuid.asJson)
    )
  }

  def permissionsResourceUnit(
      rev: Long = 1L,
      deprecated: Boolean = false,
      createdBy: Subject = Anonymous,
      updatedBy: Subject = Anonymous
  ): Json = {
    val resourceUris = ResourceUris.permissions
    resourceUnit(
      resourceUris.accessUri.toIri,
      resourceUris,
      """"Permissions"""",
      schemas.permissions,
      rev,
      deprecated,
      createdBy,
      updatedBy
    )
  }

  def aclResourceUnit(
      address: AclAddress,
      rev: Long = 1L,
      deprecated: Boolean = false,
      createdBy: Subject = Anonymous,
      updatedBy: Subject = Anonymous
  ): Json = {
    val resourceUris = ResourceUris.acl(address)
    resourceUnit(
      resourceUris.accessUri.toIri,
      resourceUris,
      """"AccessControlList"""",
      schemas.acls,
      rev,
      deprecated,
      createdBy,
      updatedBy,
      additionalMetadata = Json.obj("_path" -> address.asJson)
    )
  }

  def realmsResourceUnit(
      label: Label,
      rev: Long = 1L,
      deprecated: Boolean = false,
      createdBy: Subject = Anonymous,
      updatedBy: Subject = Anonymous
  ): Json = {
    val resourceUris = ResourceUris.realm(label)
    resourceUnit(
      resourceUris.accessUri.toIri,
      resourceUris,
      """"Realm"""",
      schemas.realms,
      rev,
      deprecated,
      createdBy,
      updatedBy,
      additionalMetadata = Json.obj("_label" -> label.asJson)
    )
  }

  def resolverMetadata(
      id: Iri,
      resolverType: ResolverType,
      projectRef: ProjectRef,
      rev: Long = 1L,
      deprecated: Boolean = false,
      createdBy: Subject = Anonymous,
      updatedBy: Subject = Anonymous,
      am: ApiMappings = ApiMappings.empty,
      base: Iri = nxv.base
  ) = {
    val resourceUris = ResourceUris.resolver(projectRef, id)(am, ProjectBase.unsafe(base))
    resourceUnit(
      id,
      resourceUris,
      s"""["Resolver", "$resolverType"]""",
      schemas.resolvers,
      rev,
      deprecated,
      createdBy,
      updatedBy
    )
  }

  private def resourceUnit(
      id: Iri,
      resourceUris: ResourceUris,
      tpe: String,
      schema: Iri,
      rev: Long,
      deprecated: Boolean,
      createdBy: Subject,
      updatedBy: Subject,
      additionalMetadata: Json = Json.obj()
  ): Json = {
    val incoming = resourceUris.incomingShortForm.fold(Json.obj())(in => Json.obj("_incoming" -> in.asJson))
    val outgoing = resourceUris.outgoingShortForm.fold(Json.obj())(out => Json.obj("_outgoing" -> out.asJson))
    jsonContentOf(
      "resource-unit.json",
      "id"         -> id,
      "type"       -> tpe,
      "schema"     -> schema,
      "deprecated" -> deprecated,
      "rev"        -> rev,
      "createdBy"  -> createdBy.id,
      "updatedBy"  -> updatedBy.id,
      "self"       -> resourceUris.accessUriShortForm
    ) deepMerge additionalMetadata deepMerge incoming deepMerge outgoing
  }

}
