package ch.epfl.bluebrain.nexus.delta.serialization

import java.time.Instant
import java.util.UUID

import akka.http.scaladsl.model.Uri
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.{nxv, schemas}
import ch.epfl.bluebrain.nexus.delta.sdk.model.acls.AclEvent._
import ch.epfl.bluebrain.nexus.delta.sdk.model.acls.{Acl, AclAddress, AclEvent}
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Identity
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Identity._
import ch.epfl.bluebrain.nexus.delta.sdk.model.organizations.OrganizationEvent
import ch.epfl.bluebrain.nexus.delta.sdk.model.organizations.OrganizationEvent._
import ch.epfl.bluebrain.nexus.delta.sdk.model.permissions.PermissionsEvent._
import ch.epfl.bluebrain.nexus.delta.sdk.model.permissions.{Permission, PermissionsEvent}
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.ProjectEvent.{ProjectCreated, ProjectDeprecated, ProjectUpdated}
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.{PrefixIRI, ProjectEvent}
import ch.epfl.bluebrain.nexus.delta.sdk.model.realms.GrantType._
import ch.epfl.bluebrain.nexus.delta.sdk.model.realms.RealmEvent._
import ch.epfl.bluebrain.nexus.delta.sdk.model.realms.{GrantType, RealmEvent}
import ch.epfl.bluebrain.nexus.delta.sdk.model.{Label, Name}
import ch.epfl.bluebrain.nexus.testkit.TestHelpers
import io.circe.Json
import org.scalatest.flatspec.AnyFlatSpecLike

/*
scalafmt: {
  style = defaultWithAlign
  maxColumn = 150
}
 */
class EventSerializerSpec extends EventSerializerBehaviours with AnyFlatSpecLike with TestHelpers {

  val instant: Instant = Instant.EPOCH
  val rev: Long        = 1L

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
  val acl: Acl                            = Acl(Anonymous -> permSet, authenticated -> permSet, group -> permSet, subject -> permSet)

  val org: Label          = Label.unsafe("myorg")
  val orgUuid: UUID       = UUID.fromString("b6bde92f-7836-4da6-8ead-2e0fd516ebe7")
  val description: String = "some description"

  val proj: Label                   = Label.unsafe("myproj")
  val projUuid: UUID                = UUID.fromString("fe1301a6-a105-4966-84af-32723fd003d2")
  val apiMappings: Map[String, Iri] = Map("nxv" -> nxv.base)
  val base: PrefixIRI               = PrefixIRI.unsafe(schemas.base)
  val vocab: PrefixIRI              = PrefixIRI.unsafe(nxv.base)

  val permissionsMapping: Map[PermissionsEvent, Json] = Map(
    PermissionsAppended(rev, permSet, instant, subject)   -> jsonContentOf("/serialization/permissions-appended.json"),
    PermissionsSubtracted(rev, permSet, instant, subject) -> jsonContentOf("/serialization/permissions-subtracted.json"),
    PermissionsReplaced(rev, permSet, instant, subject)   -> jsonContentOf("/serialization/permissions-replaced.json"),
    PermissionsDeleted(rev, instant, anonymous)           -> jsonContentOf("/serialization/permissions-deleted.json")
  )

  val aclsMapping: Map[AclEvent, Json] = Map(
    AclAppended(root, acl, rev, instant, subject)         -> jsonContentOf("/serialization/acl-appended.json"),
    AclSubtracted(orgAddress, acl, rev, instant, subject) -> jsonContentOf("/serialization/acl-subtracted.json"),
    AclReplaced(projAddress, acl, rev, instant, subject)  -> jsonContentOf("/serialization/acl-replaced.json"),
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

}
