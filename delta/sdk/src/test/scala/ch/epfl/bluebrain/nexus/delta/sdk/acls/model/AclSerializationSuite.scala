package ch.epfl.bluebrain.nexus.delta.sdk.acls.model

import ch.epfl.bluebrain.nexus.delta.kernel.utils.ClassUtils
import ch.epfl.bluebrain.nexus.delta.sdk.SerializationSuite
import ch.epfl.bluebrain.nexus.delta.sdk.acls.model.AclEvent.{AclAppended, AclDeleted, AclReplaced, AclSubtracted}
import ch.epfl.bluebrain.nexus.delta.sdk.permissions.model.Permission
import ch.epfl.bluebrain.nexus.delta.sdk.sse.SseEncoder.SseData
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.{Anonymous, Authenticated, Group, Subject, User}
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{Identity, Label}

import java.time.Instant

class AclSerializationSuite extends SerializationSuite {

  private val sseEncoder = AclEvent.sseEncoder

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

  def acl(address: AclAddress): Acl =
    Acl(address, Anonymous -> permSet, authenticated -> permSet, group -> permSet, subject -> permSet)

  private val aclsMapping           = Map(
    AclAppended(acl(root), rev, instant, subject)         -> loadEvents("acls", "acl-appended.json"),
    AclSubtracted(acl(orgAddress), rev, instant, subject) -> loadEvents("acls", "acl-subtracted.json"),
    AclReplaced(acl(projAddress), rev, instant, subject)  -> loadEvents("acls", "acl-replaced.json"),
    AclDeleted(projAddress, rev, instant, anonymous)      -> loadEvents("acls", "acl-deleted.json")
  )

  aclsMapping.foreach { case (event, (database, sse)) =>
    test(s"Correctly serialize ${event.getClass.getName}") {
      assertEquals(AclEvent.serializer.codec(event), database)
    }

    test(s"Correctly deserialize ${event.getClass.getName}") {
      assertEquals(AclEvent.serializer.codec.decodeJson(database), Right(event))
    }

    test(s"Correctly serialize ${event.getClass.getName} as an SSE") {
      sseEncoder.toSse.decodeJson(database).assertRight(SseData(ClassUtils.simpleName(event), None, sse))
    }
  }

  private val state = AclState(
    Acl(projAddress, subject -> permSet, anonymous -> permSet, group -> permSet, authenticated -> permSet),
    rev = rev,
    createdAt = instant,
    createdBy = subject,
    updatedAt = instant,
    updatedBy = subject
  )

  private val jsonState = jsonContentOf("/acls/acl-state.json")

  test(s"Correctly serialize an AclState") {
    assertEquals(AclState.serializer.codec(state), jsonState)
  }

  test(s"Correctly deserialize an AclState") {
    assertEquals(AclState.serializer.codec.decodeJson(jsonState), Right(state))
  }

}
