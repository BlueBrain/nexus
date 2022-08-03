package ch.epfl.bluebrain.nexus.delta.sdk.permissions.model

import ch.epfl.bluebrain.nexus.delta.kernel.utils.ClassUtils
import ch.epfl.bluebrain.nexus.delta.sdk.SerializationSuite
import ch.epfl.bluebrain.nexus.delta.sdk.permissions.model.PermissionsEvent.{PermissionsAppended, PermissionsDeleted, PermissionsReplaced, PermissionsSubtracted}
import ch.epfl.bluebrain.nexus.delta.sdk.sse.SseEncoder.SseData
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.{Anonymous, Subject, User}
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Label

import java.time.Instant

class PermissionsSerializationSuite extends SerializationSuite {

  private val sseEncoder = PermissionsEvent.sseEncoder

  val instant: Instant         = Instant.EPOCH
  val rev: Int                 = 1
  val permSet: Set[Permission] = Set(Permission.unsafe("my/perm"))
  val realm: Label             = Label.unsafe("myrealm")
  val subject: Subject         = User("username", realm)
  val anonymous: Subject       = Anonymous

  private val permissionsMapping = Map(
    PermissionsAppended(rev, permSet, instant, subject)   -> loadEvents("permissions", "permissions-appended.json"),
    PermissionsSubtracted(rev, permSet, instant, subject) -> loadEvents("permissions", "permissions-subtracted.json"),
    PermissionsReplaced(rev, permSet, instant, subject)   -> loadEvents("permissions", "permissions-replaced.json"),
    PermissionsDeleted(rev, instant, anonymous)           -> loadEvents("permissions", "permissions-deleted.json")
  )

  permissionsMapping.foreach { case (event, (database, sse)) =>
    test(s"Correctly serialize ${event.getClass.getName}") {
      assertEquals(PermissionsEvent.serializer.codec(event), database)
    }

    test(s"Correctly deserialize ${event.getClass.getName}") {
      assertEquals(PermissionsEvent.serializer.codec.decodeJson(database), Right(event))
    }

    test(s"Correctly serialize ${event.getClass.getName} as an SSE") {
      sseEncoder.toSse.decodeJson(database).assertRight(SseData(ClassUtils.simpleName(event), None, sse))
    }
  }

  private val state = PermissionsState(
    rev = rev,
    permSet,
    createdAt = instant,
    createdBy = subject,
    updatedAt = instant,
    updatedBy = subject
  )

  private val jsonState = jsonContentOf("/permissions/permissions-state.json")

  test(s"Correctly serialize a PermissionsState") {
    assertEquals(PermissionsState.serializer.codec(state), jsonState)
  }

  test(s"Correctly deserialize a PermissionsState") {
    assertEquals(PermissionsState.serializer.codec.decodeJson(jsonState), Right(state))
  }

}
