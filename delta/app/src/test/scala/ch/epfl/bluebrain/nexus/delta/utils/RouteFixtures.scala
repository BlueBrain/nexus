package ch.epfl.bluebrain.nexus.delta.utils

import java.time.Instant
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
import ch.epfl.bluebrain.nexus.delta.sdk.model.{AccessUrl, BaseUri, Label, ResourceRef}
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
    val accessUrl = AccessUrl.schema(ref, id)
    resourceUnit(
      id,
      accessUrl,
      "Schema",
      schemas.shacl,
      rev,
      deprecated,
      createdBy,
      updatedBy,
      am,
      base,
      additionalMetadata = Json.obj(
        "_incoming" -> s"${accessUrl.shortForm(am, ProjectBase.unsafe(base))}/incoming".asJson,
        "_outgoing" -> s"${accessUrl.shortForm(am, ProjectBase.unsafe(base))}/outgoing".asJson
      )
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
    val accessUrl = AccessUrl.resource(ref, id, ResourceRef(schema))
    resourceUnit(
      id,
      accessUrl,
      tpe,
      schema,
      rev,
      deprecated,
      createdBy,
      updatedBy,
      am,
      base,
      additionalMetadata = Json.obj(
        "_incoming" -> s"${accessUrl.shortForm(am, ProjectBase.unsafe(base))}/incoming".asJson,
        "_outgoing" -> s"${accessUrl.shortForm(am, ProjectBase.unsafe(base))}/outgoing".asJson
      )
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
      additionalMetadata = Json.obj("_label" -> label.asJson, "_uuid" -> uuid.asJson)
    )
  }

  def permissionsResourceUnit(
      rev: Long = 1L,
      deprecated: Boolean = false,
      createdBy: Subject = Anonymous,
      updatedBy: Subject = Anonymous
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
      additionalMetadata = Json.obj("_label" -> label.asJson)
    )
  }

  def resolverMetadata(
      id: String,
      resolverType: ResolverType,
      projectRef: ProjectRef,
      rev: Long = 1L,
      deprecated: Boolean = false,
      createdBy: Subject = Anonymous,
      updatedBy: Subject = Anonymous
  ) =
    Json.obj(
      "@context"       -> "https://bluebrain.github.io/nexus/contexts/metadata.json".asJson,
      "@id"            -> (nxv + id).asJson,
      "@type"          -> List("Resolver", resolverType.toString).asJson,
      "_incoming"      -> s"http://localhost/v1/resolvers/$projectRef/$id/incoming".asJson,
      "_outgoing"      -> s"http://localhost/v1/resolvers/$projectRef/$id/outgoing".asJson,
      "_self"          -> s"http://localhost/v1/resolvers/$projectRef/$id".asJson,
      "_constrainedBy" -> "https://bluebrain.github.io/nexus/schemas/resolvers.json".asJson,
      "_rev"           -> rev.asJson,
      "_deprecated"    -> deprecated.asJson,
      "_createdAt"     -> Instant.EPOCH.asJson,
      "_createdBy"     -> subject(createdBy),
      "_updatedAt"     -> Instant.EPOCH.asJson,
      "_updatedBy"     -> subject(updatedBy)
    )

  private def subject(subject: Subject) =
    subject match {
      case Anonymous            => "http://localhost/v1/anonymous".asJson
      case User(subject, realm) => s"http://localhost/v1/realms/${realm.value}/users/$subject".asJson
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
      am: ApiMappings = ApiMappings.empty,
      base: Iri = nxv.base,
      additionalMetadata: Json = Json.obj()
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
      "self"       -> accessUrl.shortForm(am, ProjectBase.unsafe(base))
    ) deepMerge additionalMetadata

}
