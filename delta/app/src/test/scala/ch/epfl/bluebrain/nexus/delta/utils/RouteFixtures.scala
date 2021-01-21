package ch.epfl.bluebrain.nexus.delta.utils

import akka.http.scaladsl.server.{ExceptionHandler, RejectionHandler}
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.{contexts, schemas}
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.RemoteContextResolution
import ch.epfl.bluebrain.nexus.delta.rdf.utils.JsonKeyOrdering
import ch.epfl.bluebrain.nexus.delta.sdk.marshalling.{RdfExceptionHandler, RdfRejectionHandler}
import ch.epfl.bluebrain.nexus.delta.sdk.model.acls.AclAddress
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Identity.{Anonymous, Subject, User}
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.ProjectRef
import ch.epfl.bluebrain.nexus.delta.sdk.model.resolvers.ResolverType
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.PaginationConfig
import ch.epfl.bluebrain.nexus.delta.sdk.model.{BaseUri, Label}
import ch.epfl.bluebrain.nexus.testkit.TestHelpers
import io.circe.Json
import monix.execution.Scheduler

import java.util.UUID

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
      contexts.tags          -> jsonContentOf("contexts/tags.json"),
      contexts.pluginsInfo   -> jsonContentOf("/contexts/plugins-info.json")
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

  def schemaMetadata(
      ref: ProjectRef,
      id: Iri,
      rev: Long = 1L,
      deprecated: Boolean = false,
      createdBy: Subject = Anonymous,
      updatedBy: Subject = Anonymous
  ): Json =
    jsonContentOf(
      "schemas/schema-route-metadata-response.json",
      "project"    -> ref,
      "id"         -> id,
      "rev"        -> rev,
      "deprecated" -> deprecated,
      "createdBy"  -> createdBy.id,
      "updatedBy"  -> updatedBy.id,
      "label"      -> lastSegment(id)
    )

  def resourceMetadata(
      ref: ProjectRef,
      id: Iri,
      schema: Iri,
      tpe: String,
      rev: Long = 1L,
      deprecated: Boolean = false,
      createdBy: Subject = Anonymous,
      updatedBy: Subject = Anonymous
  ): Json =
    jsonContentOf(
      "resources/resource-route-metadata-response.json",
      "project"     -> ref,
      "id"          -> id,
      "rev"         -> rev,
      "type"        -> tpe,
      "deprecated"  -> deprecated,
      "createdBy"   -> createdBy.id,
      "updatedBy"   -> updatedBy.id,
      "schema"      -> schema,
      "label"       -> lastSegment(id),
      "schemaLabel" -> (if (schema == schemas.resources) "_" else lastSegment(schema))
    )

  def projectMetadata(
      ref: ProjectRef,
      label: String,
      uuid: UUID,
      organizationLabel: String,
      organizationUuid: UUID,
      rev: Long = 1L,
      deprecated: Boolean = false,
      createdBy: Subject = Anonymous,
      updatedBy: Subject = Anonymous
  ): Json =
    jsonContentOf(
      "projects/project-route-metadata-response.json",
      "project"          -> ref,
      "rev"              -> rev,
      "deprecated"       -> deprecated,
      "createdBy"        -> createdBy.id,
      "updatedBy"        -> updatedBy.id,
      "label"            -> label,
      "uuid"             -> uuid,
      "organization"     -> organizationLabel,
      "organizationUuid" -> organizationUuid
    )

  def orgMetadata(
      label: Label,
      uuid: UUID,
      rev: Long = 1L,
      deprecated: Boolean = false,
      createdBy: Subject = Anonymous,
      updatedBy: Subject = Anonymous
  ): Json =
    jsonContentOf(
      "organizations/org-route-metadata-response.json",
      "rev"        -> rev,
      "deprecated" -> deprecated,
      "createdBy"  -> createdBy.id,
      "updatedBy"  -> updatedBy.id,
      "label"      -> label,
      "uuid"       -> uuid
    )

  def permissionsMetadata(
      rev: Long = 1L,
      deprecated: Boolean = false,
      createdBy: Subject = Anonymous,
      updatedBy: Subject = Anonymous
  ): Json =
    jsonContentOf(
      "permissions/permissions-route-metadata-response.json",
      "rev"        -> rev,
      "deprecated" -> deprecated,
      "createdBy"  -> createdBy.id,
      "updatedBy"  -> updatedBy.id
    )

  def aclMetadata(
      address: AclAddress,
      rev: Long = 1L,
      deprecated: Boolean = false,
      createdBy: Subject = Anonymous,
      updatedBy: Subject = Anonymous
  ): Json =
    jsonContentOf(
      "acls/acl-route-metadata-response.json",
      "rev"        -> rev,
      "deprecated" -> deprecated,
      "createdBy"  -> createdBy.id,
      "updatedBy"  -> updatedBy.id,
      "path"       -> address,
      "project"    -> (if (address == AclAddress.Root) "" else address)
    )

  def realmMetadata(
      label: Label,
      rev: Long = 1L,
      deprecated: Boolean = false,
      createdBy: Subject = Anonymous,
      updatedBy: Subject = Anonymous
  ): Json =
    jsonContentOf(
      "realms/realm-route-metadata-response.json",
      "rev"        -> rev,
      "deprecated" -> deprecated,
      "createdBy"  -> createdBy.id,
      "updatedBy"  -> updatedBy.id,
      "label"      -> label
    )

  def resolverMetadata(
      id: Iri,
      resolverType: ResolverType,
      projectRef: ProjectRef,
      rev: Long = 1L,
      deprecated: Boolean = false,
      createdBy: Subject = Anonymous,
      updatedBy: Subject = Anonymous
  ) =
    jsonContentOf(
      "resolvers/resolver-route-metadata-response.json",
      "project"    -> projectRef,
      "id"         -> id,
      "rev"        -> rev,
      "deprecated" -> deprecated,
      "createdBy"  -> createdBy.id,
      "updatedBy"  -> updatedBy.id,
      "type"       -> resolverType,
      "label"      -> lastSegment(id)
    )

  private def lastSegment(iri: Iri) =
    iri.toString.substring(iri.toString.lastIndexOf("/") + 1)
}
