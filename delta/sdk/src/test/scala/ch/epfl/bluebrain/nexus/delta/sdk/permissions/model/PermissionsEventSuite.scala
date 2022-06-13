package ch.epfl.bluebrain.nexus.delta.sdk.permissions.model

import ch.epfl.bluebrain.nexus.delta.sdk.permissions.model.PermissionsEvent.{PermissionsAppended, PermissionsDeleted, PermissionsReplaced, PermissionsSubtracted}
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.{Anonymous, Subject, User}
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Label
import ch.epfl.bluebrain.nexus.testkit.TestHelpers
import io.circe.Json
import munit.{Assertions, FunSuite}

import java.time.Instant

class PermissionsEventSuite extends FunSuite with Assertions with TestHelpers {

  val instant: Instant         = Instant.EPOCH
  val rev: Int                 = 1
  val permSet: Set[Permission] = Set(Permission.unsafe("my/perm"))
  val realm: Label             = Label.unsafe("myrealm")
  val subject: Subject         = User("username", realm)
  val anonymous: Subject       = Anonymous

  val permissionsMapping: Map[PermissionsEvent, Json] = Map(
    PermissionsAppended(rev, permSet, instant, subject)   -> jsonContentOf("/permissions/permissions-appended.json"),
    PermissionsSubtracted(rev, permSet, instant, subject) -> jsonContentOf("/permissions/permissions-subtracted.json"),
    PermissionsReplaced(rev, permSet, instant, subject)   -> jsonContentOf("/permissions/permissions-replaced.json"),
    PermissionsDeleted(rev, instant, anonymous)           -> jsonContentOf("/permissions/permissions-deleted.json")
  )

  permissionsMapping.foreach { case (event, json) =>
    test(s"Correctly serialize ${event.getClass.getName}") {
      assertEquals(PermissionsEvent.serializer.codec(event), json)
    }
  }

  permissionsMapping.foreach { case (event, json) =>
    test(s"Correctly deserialize ${event.getClass.getName}") {
      assertEquals(PermissionsEvent.serializer.codec.decodeJson(json), Right(event))
    }
  }

}
