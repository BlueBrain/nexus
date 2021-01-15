package ch.epfl.bluebrain.nexus.delta.service.serialization

import akka.actor.ActorSystem
import akka.http.scaladsl.model.Uri
import akka.testkit.TestKit
import cats.data.NonEmptyList
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.{contexts, nxv, schema, schemas}
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.RemoteContextResolution
import ch.epfl.bluebrain.nexus.delta.sdk.generators.{ResourceGen, SchemaGen}
import ch.epfl.bluebrain.nexus.delta.sdk.model.ResourceRef.Revision
import ch.epfl.bluebrain.nexus.delta.sdk.model.acls.AclEvent._
import ch.epfl.bluebrain.nexus.delta.sdk.model.acls.{Acl, AclAddress, AclEvent}
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Identity
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Identity._
import ch.epfl.bluebrain.nexus.delta.sdk.model.organizations.OrganizationEvent
import ch.epfl.bluebrain.nexus.delta.sdk.model.organizations.OrganizationEvent._
import ch.epfl.bluebrain.nexus.delta.sdk.model.permissions.PermissionsEvent._
import ch.epfl.bluebrain.nexus.delta.sdk.model.permissions.{Permission, PermissionsEvent}
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.ProjectEvent.{ProjectCreated, ProjectDeprecated, ProjectUpdated}
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.{ApiMappings, PrefixIri, ProjectEvent, ProjectRef}
import ch.epfl.bluebrain.nexus.delta.sdk.model.realms.GrantType._
import ch.epfl.bluebrain.nexus.delta.sdk.model.realms.RealmEvent._
import ch.epfl.bluebrain.nexus.delta.sdk.model.realms.{GrantType, RealmEvent}
import ch.epfl.bluebrain.nexus.delta.sdk.model.resolvers.IdentityResolution.{ProvidedIdentities, UseCurrentCaller}
import ch.epfl.bluebrain.nexus.delta.sdk.model.resolvers.ResolverEvent.{ResolverCreated, ResolverDeprecated, ResolverTagAdded, ResolverUpdated}
import ch.epfl.bluebrain.nexus.delta.sdk.model.resolvers.ResolverValue.{CrossProjectValue, InProjectValue}
import ch.epfl.bluebrain.nexus.delta.sdk.model.resolvers.{Priority, ResolverEvent}
import ch.epfl.bluebrain.nexus.delta.sdk.model.resources.ResourceEvent
import ch.epfl.bluebrain.nexus.delta.sdk.model.resources.ResourceEvent.{ResourceCreated, ResourceDeprecated, ResourceTagAdded, ResourceUpdated}
import ch.epfl.bluebrain.nexus.delta.sdk.model.schemas.SchemaEvent
import ch.epfl.bluebrain.nexus.delta.sdk.model.schemas.SchemaEvent.{SchemaCreated, SchemaDeprecated, SchemaTagAdded, SchemaUpdated}
import ch.epfl.bluebrain.nexus.delta.sdk.model.{Label, Name, TagLabel}
import ch.epfl.bluebrain.nexus.delta.sdk.testkit.EventSerializerBehaviours
import ch.epfl.bluebrain.nexus.testkit.{CirceLiteral, TestHelpers}
import io.circe.Json
import org.scalatest.CancelAfterFailure
import org.scalatest.flatspec.AnyFlatSpecLike

import java.time.Instant
import java.util.UUID

/*
scalafmt: {
  style = defaultWithAlign
  maxColumn = 150
}
 */
class EventSerializerSpec
    extends TestKit(ActorSystem("EventSerializerSpec"))
    with EventSerializerBehaviours
    with AnyFlatSpecLike
    with TestHelpers
    with CirceLiteral
    with CancelAfterFailure {

  override val serializer = new EventSerializer
  val instant: Instant    = Instant.EPOCH
  val rev: Long           = 1L

  val realm: Label               = Label.unsafe("myrealm")
  val name: Name                 = Name.unsafe("name")
  val openIdConfig: Uri          = Uri("http://localhost:8080/.wellknown")
  val issuer: String             = "http://localhost:8080/issuer"
  val keys: Set[Json]            = Set(Json.obj("k" -> Json.fromString(issuer)))
  val grantTypes: Set[GrantType] = Set(AuthorizationCode, Implicit, Password, ClientCredentials, DeviceCode, RefreshToken)
  val logo: Uri                  = Uri("http://localhost:8080/logo.png")
  val authorizationEndpoint: Uri = Uri("http://localhost:8080/authorize")
  val tokenEndpoint: Uri         = Uri("http://localhost:8080/token")
  val userInfoEndpoint: Uri      = Uri("http://localhost:8080/userinfo")
  val revocationEndpoint: Uri    = Uri("http://localhost:8080/revocation")
  val endSessionEndpoint: Uri    = Uri("http://localhost:8080/logout")

  val subject: Subject         = User("username", realm)
  val anonymous: Subject       = Anonymous
  val group: Identity          = Group("group", realm)
  val authenticated: Identity  = Authenticated(realm)
  val permSet: Set[Permission] = Set(Permission.unsafe("my/perm"))

  val root: AclAddress                    = AclAddress.Root
  val orgAddress: AclAddress.Organization = AclAddress.Organization(Label.unsafe("myorg"))
  val projAddress: AclAddress.Project     = AclAddress.Project(Label.unsafe("myorg"), Label.unsafe("myproj"))
  def acl(address: AclAddress): Acl       = Acl(address, Anonymous -> permSet, authenticated -> permSet, group -> permSet, subject -> permSet)

  val org: Label          = Label.unsafe("myorg")
  val orgUuid: UUID       = UUID.fromString("b6bde92f-7836-4da6-8ead-2e0fd516ebe7")
  val description: String = "some description"

  val proj: Label                           = Label.unsafe("myproj")
  val projUuid: UUID                        = UUID.fromString("fe1301a6-a105-4966-84af-32723fd003d2")
  val apiMappings: ApiMappings              = ApiMappings(Map("nxv" -> nxv.base))
  val base: PrefixIri                       = PrefixIri.unsafe(schemas.base)
  val vocab: PrefixIri                      = PrefixIri.unsafe(nxv.base)
  val projectRef                            = ProjectRef(org, proj)
  val myId                                  = nxv + "myId"
  val shaclResolvedCtx                      = jsonContentOf("contexts/shacl.json")
  implicit val rcr: RemoteContextResolution = RemoteContextResolution.fixed(contexts.shacl -> shaclResolvedCtx)
  val resource                              = ResourceGen.resource(myId, projectRef, jsonContentOf("resources/resource.json", "id" -> myId))
  val scheme                                = SchemaGen.schema(myId, projectRef, jsonContentOf("resources/schema.json") deepMerge json"""{"@id": "$myId"}""")

  val inProjectValue: InProjectValue = InProjectValue(Priority.unsafe(42))
  val crossProjectValue1: CrossProjectValue = CrossProjectValue(
    Priority.unsafe(42),
    Set(schemas.projects, schemas.resources),
    NonEmptyList.of(projectRef, ProjectRef.unsafe("org2", "proj2")),
    ProvidedIdentities(Set(subject, Anonymous, group, authenticated))
  )
  val crossProjectValue2: CrossProjectValue = CrossProjectValue(
    Priority.unsafe(42),
    Set(schemas.projects, schemas.resources),
    NonEmptyList.of(projectRef, ProjectRef.unsafe("org2", "proj2")),
    UseCurrentCaller
  )

  val permissionsMapping: Map[PermissionsEvent, Json] = Map(
    PermissionsAppended(rev, permSet, instant, subject)   -> jsonContentOf("/serialization/permissions-appended.json"),
    PermissionsSubtracted(rev, permSet, instant, subject) -> jsonContentOf("/serialization/permissions-subtracted.json"),
    PermissionsReplaced(rev, permSet, instant, subject)   -> jsonContentOf("/serialization/permissions-replaced.json"),
    PermissionsDeleted(rev, instant, anonymous)           -> jsonContentOf("/serialization/permissions-deleted.json")
  )

  val aclsMapping: Map[AclEvent, Json] = Map(
    AclAppended(acl(root), rev, instant, subject)         -> jsonContentOf("/serialization/acl-appended.json"),
    AclSubtracted(acl(orgAddress), rev, instant, subject) -> jsonContentOf("/serialization/acl-subtracted.json"),
    AclReplaced(acl(projAddress), rev, instant, subject)  -> jsonContentOf("/serialization/acl-replaced.json"),
    AclDeleted(projAddress, rev, instant, anonymous)      -> jsonContentOf("/serialization/acl-deleted.json")
  )

  val realmsMapping: Map[RealmEvent, Json] = Map(
    RealmCreated(
      label = realm,
      rev = rev,
      name = name,
      openIdConfig = openIdConfig,
      issuer = issuer,
      keys = keys,
      grantTypes = grantTypes,
      logo = Some(logo),
      authorizationEndpoint = authorizationEndpoint,
      tokenEndpoint = tokenEndpoint,
      userInfoEndpoint = userInfoEndpoint,
      revocationEndpoint = Some(revocationEndpoint),
      endSessionEndpoint = Some(endSessionEndpoint),
      instant = instant,
      subject = subject
    ) -> jsonContentOf("/serialization/realm-created.json"),
    RealmUpdated(
      label = realm,
      rev = rev,
      name = name,
      openIdConfig = openIdConfig,
      issuer = issuer,
      keys = keys,
      grantTypes = grantTypes,
      logo = Some(logo),
      authorizationEndpoint = authorizationEndpoint,
      tokenEndpoint = tokenEndpoint,
      userInfoEndpoint = userInfoEndpoint,
      revocationEndpoint = Some(revocationEndpoint),
      endSessionEndpoint = Some(endSessionEndpoint),
      instant = instant,
      subject = subject
    ) -> jsonContentOf("/serialization/realm-updated.json"),
    RealmDeprecated(
      label = realm,
      rev = rev,
      instant = instant,
      subject = subject
    ) -> jsonContentOf("/serialization/realm-deprecated.json")
  )

  val orgsMapping: Map[OrganizationEvent, Json] = Map(
    OrganizationCreated(org, orgUuid, rev, Some(description), instant, subject) -> jsonContentOf("/serialization/org-created.json"),
    OrganizationUpdated(org, orgUuid, rev, Some(description), instant, subject) -> jsonContentOf("/serialization/org-updated.json"),
    OrganizationDeprecated(org, orgUuid, rev, instant, subject)                 -> jsonContentOf("/serialization/org-deprecated.json")
  )

  val resolversMapping: Map[ResolverEvent, Json] = Map(
    ResolverCreated(
      myId,
      projectRef,
      inProjectValue,
      Json.obj("resolver" -> Json.fromString("created")),
      1L,
      instant,
      subject
    ) -> jsonContentOf("/serialization/resolver-in-project-created.json"),
    ResolverCreated(
      myId,
      projectRef,
      crossProjectValue1,
      Json.obj("resolver" -> Json.fromString("created")),
      1L,
      instant,
      subject
    ) -> jsonContentOf("/serialization/resolver-cross-project-created-1.json"),
    ResolverCreated(
      myId,
      projectRef,
      crossProjectValue2,
      Json.obj("resolver" -> Json.fromString("created")),
      1L,
      instant,
      subject
    ) -> jsonContentOf("/serialization/resolver-cross-project-created-2.json"),
    ResolverUpdated(
      myId,
      projectRef,
      inProjectValue,
      Json.obj("resolver" -> Json.fromString("updated")),
      2L,
      instant,
      subject
    ) -> jsonContentOf("/serialization/resolver-in-project-updated.json"),
    ResolverUpdated(
      myId,
      projectRef,
      crossProjectValue1,
      Json.obj("resolver" -> Json.fromString("updated")),
      2L,
      instant,
      subject
    ) -> jsonContentOf("/serialization/resolver-cross-project-updated-1.json"),
    ResolverUpdated(
      myId,
      projectRef,
      crossProjectValue2,
      Json.obj("resolver" -> Json.fromString("updated")),
      2L,
      instant,
      subject
    ) -> jsonContentOf("/serialization/resolver-cross-project-updated-2.json"),
    ResolverTagAdded(
      myId,
      projectRef,
      1L,
      TagLabel.unsafe("mytag"),
      3L,
      instant,
      subject
    ) -> jsonContentOf("/serialization/resolver-tagged.json"),
    ResolverDeprecated(
      myId,
      projectRef,
      4L,
      instant,
      subject
    ) -> jsonContentOf("/serialization/resolver-deprecated.json")
  )

  val schemasMapping: Map[SchemaEvent, Json] = Map(
    SchemaCreated(
      myId,
      projectRef,
      scheme.source,
      scheme.compacted,
      scheme.expanded,
      1L,
      instant,
      subject
    ) -> jsonContentOf("/serialization/schema-created.json"),
    SchemaUpdated(
      myId,
      projectRef,
      scheme.source,
      scheme.compacted,
      scheme.expanded,
      2L,
      instant,
      subject
    ) -> jsonContentOf("/serialization/schema-updated.json"),
    SchemaTagAdded(
      myId,
      projectRef,
      1L,
      TagLabel.unsafe("mytag"),
      3L,
      instant,
      subject
    ) -> jsonContentOf("/serialization/schema-tagged.json"),
    SchemaDeprecated(
      myId,
      projectRef,
      4L,
      instant,
      subject
    ) -> jsonContentOf("/serialization/schema-deprecated.json")
  )

  val resourcesMapping: Map[ResourceEvent, Json] = Map(
    ResourceCreated(
      myId,
      projectRef,
      Revision(schemas.resources, 1),
      projectRef,
      Set(schema.Person),
      resource.source,
      resource.compacted,
      resource.expanded,
      1L,
      instant,
      subject
    ) -> jsonContentOf("/serialization/resource-created.json"),
    ResourceUpdated(
      myId,
      projectRef,
      Revision(schemas.resources, 1),
      projectRef,
      Set(schema.Person),
      resource.source,
      resource.compacted,
      resource.expanded,
      2L,
      instant,
      subject
    ) -> jsonContentOf("/serialization/resource-updated.json"),
    ResourceTagAdded(
      myId,
      projectRef,
      Set(schema.Person),
      1L,
      TagLabel.unsafe("mytag"),
      3L,
      instant,
      subject
    ) -> jsonContentOf("/serialization/resource-tagged.json"),
    ResourceDeprecated(
      myId,
      projectRef,
      Set(schema.Person),
      4L,
      instant,
      subject
    ) -> jsonContentOf("/serialization/resource-deprecated.json")
  )

  val projectsMapping: Map[ProjectEvent, Json] = Map(
    ProjectCreated(
      label = proj,
      uuid = projUuid,
      organizationLabel = org,
      organizationUuid = orgUuid,
      rev = rev,
      description = Some(description),
      apiMappings = apiMappings,
      base = base,
      vocab = vocab,
      instant = instant,
      subject = subject
    ) -> jsonContentOf("/serialization/project-created.json"),
    ProjectUpdated(
      label = proj,
      uuid = projUuid,
      organizationLabel = org,
      organizationUuid = orgUuid,
      rev = rev,
      description = Some(description),
      apiMappings = apiMappings,
      base = base,
      vocab = vocab,
      instant = instant,
      subject = subject
    ) -> jsonContentOf("/serialization/project-updated.json"),
    ProjectDeprecated(
      label = proj,
      uuid = projUuid,
      organizationLabel = org,
      organizationUuid = orgUuid,
      rev = rev,
      instant = instant,
      subject = subject
    ) -> jsonContentOf("/serialization/project-deprecated.json")
  )

  "An EventSerializer" should behave like eventToJsonSerializer("permissions", permissionsMapping)
  "An EventSerializer" should behave like jsonToEventDeserializer("permissions", permissionsMapping)
  "An EventSerializer" should behave like eventToJsonSerializer("acl", aclsMapping)
  "An EventSerializer" should behave like jsonToEventDeserializer("acl", aclsMapping)
  "An EventSerializer" should behave like eventToJsonSerializer("realm", realmsMapping)
  "An EventSerializer" should behave like jsonToEventDeserializer("realm", realmsMapping)
  "An EventSerializer" should behave like eventToJsonSerializer("organization", orgsMapping)
  "An EventSerializer" should behave like jsonToEventDeserializer("organization", orgsMapping)
  "An EventSerializer" should behave like eventToJsonSerializer("project", projectsMapping)
  "An EventSerializer" should behave like jsonToEventDeserializer("project", projectsMapping)
  "An EventSerializer" should behave like eventToJsonSerializer("resolver", resolversMapping)
  "An EventSerializer" should behave like jsonToEventDeserializer("resolver", resolversMapping)
  "An EventSerializer" should behave like eventToJsonSerializer("resource", resourcesMapping)
  "An EventSerializer" should behave like jsonToEventDeserializer("resource", resourcesMapping)
  "An EventSerializer" should behave like eventToJsonSerializer("schema", schemasMapping)
  "An EventSerializer" should behave like jsonToEventDeserializer("schema", schemasMapping)

}
