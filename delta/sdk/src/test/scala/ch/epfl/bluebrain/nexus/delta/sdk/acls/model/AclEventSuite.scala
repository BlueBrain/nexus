package ch.epfl.bluebrain.nexus.delta.sdk.acls.model

import ch.epfl.bluebrain.nexus.delta.sdk.acls.model.AclEvent.{AclAppended, AclDeleted, AclReplaced, AclSubtracted}
import ch.epfl.bluebrain.nexus.delta.sdk.permissions.model.Permission
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.{Anonymous, Authenticated, Group, Subject, User}
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{Identity, Label}
import ch.epfl.bluebrain.nexus.testkit.TestHelpers
import io.circe.Json
import munit.{Assertions, FunSuite}

import java.time.Instant

class AclEventSuite extends FunSuite with Assertions with TestHelpers {

  val instant: Instant = Instant.EPOCH
  val rev: Int         = 1

  val realm: Label             = Label.unsafe("myrealm")
  val subject: Subject         = User("username", realm)
  val anonymous: Subject       = Anonymous
  val group: Identity          = Group("group", realm)
  val authenticated: Identity  = Authenticated(realm)
  val permSet: Set[Permission] = Set(Permission.unsafe("my/perm"))

  val root: AclAddress                    = AclAddress.Root
  val orgAddress: AclAddress.Organization = AclAddress.Organization(Label.unsafe("myorg"))
  val projAddress: AclAddress.Project     = AclAddress.Project(Label.unsafe("myorg"), Label.unsafe("myproj"))

  def acl(address: AclAddress): Acl    =
    Acl(address, Anonymous -> permSet, authenticated -> permSet, group -> permSet, subject -> permSet)

  val aclsMapping: Map[AclEvent, Json] = Map(
    AclAppended(acl(root), rev, instant, subject)         -> jsonContentOf("/acls/acl-appended.json"),
    AclSubtracted(acl(orgAddress), rev, instant, subject) -> jsonContentOf("/acls/acl-subtracted.json"),
    AclReplaced(acl(projAddress), rev, instant, subject)  -> jsonContentOf("/acls/acl-replaced.json"),
    AclDeleted(projAddress, rev, instant, anonymous)      -> jsonContentOf("/acls/acl-deleted.json")
  )

  aclsMapping.foreach { case (event, json) =>
    test(s"Correctly serialize ${event.getClass.getName}") {
      assertEquals(AclEvent.serializer.codec(event), json)
    }
  }

  aclsMapping.foreach { case (event, json) =>
    test(s"Correctly deserialize ${event.getClass.getName}") {
      assertEquals(AclEvent.serializer.codec.decodeJson(json), Right(event))
    }
  }

}
