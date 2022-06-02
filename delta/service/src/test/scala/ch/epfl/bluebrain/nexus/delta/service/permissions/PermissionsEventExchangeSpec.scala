package ch.epfl.bluebrain.nexus.delta.service.permissions

import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.contexts
import ch.epfl.bluebrain.nexus.delta.sdk.generators.PermissionsGen
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.Subject
import ch.epfl.bluebrain.nexus.delta.sdk.model.permissions.Permission
import ch.epfl.bluebrain.nexus.delta.sdk.model.permissions.PermissionsEvent.PermissionsAppended
import ch.epfl.bluebrain.nexus.delta.sdk.model.BaseUri
import ch.epfl.bluebrain.nexus.delta.sdk.testkit.PermissionsDummy
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Tag.UserTag
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Label
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity
import ch.epfl.bluebrain.nexus.testkit.{IOFixedClock, IOValues, TestHelpers}
import io.circe.literal.JsonStringContext
import monix.execution.Scheduler
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike
import org.scalatest.{Inspectors, OptionValues}

import java.time.Instant

class PermissionsEventExchangeSpec
    extends AnyWordSpecLike
    with Matchers
    with OptionValues
    with IOValues
    with IOFixedClock
    with Inspectors
    with TestHelpers {

  implicit private val scheduler: Scheduler = Scheduler.global

  implicit private val subject: Subject = Identity.User("user", Label.unsafe("realm"))
  implicit private val baseUri: BaseUri = BaseUri("http://localhost", Label.unsafe("v1"))

  val permissions = PermissionsDummy(Set(Permission.unsafe("resource/read"))).accepted
  permissions.append(Set(Permission.unsafe("resource/write")), 0L).accepted

  "A PermissionsEventExchange" should {
    val tag = UserTag.unsafe("tag")

    val event = PermissionsAppended(1L, Set(Permission.unsafe("resource/write")), Instant.EPOCH, subject)

    val exchange = new PermissionsEventExchange(permissions)

    "return the latest state from the event" in {
      val result = exchange.toResource(event, None).accepted.value
      result.value.source shouldEqual json"""{"permissions":["resource/read","resource/write"]}"""
      result.value.resource shouldEqual PermissionsGen.resourceFor(
        Set(Permission.unsafe("resource/write"), Permission.unsafe("resource/read")),
        1L,
        subject,
        subject
      )
      result.metadata.value shouldEqual ()
    }

    "return None at a particular tag" in {
      exchange.toResource(event, Some(tag)).accepted shouldEqual None
    }

    "return the encoded event" in {
      val result = exchange.toJsonEvent(event).value
      result.value shouldEqual event

      result.encoder(result.value) shouldEqual
        json"""{
          "@context" : [${contexts.metadata}, ${contexts.permissions}],
          "@type" : "PermissionsAppended",
          "permissions" : ["resource/write"],
          "_permissionsId" : "http://localhost/v1/permissions",
          "_rev" : 1,
          "_instant" : "1970-01-01T00:00:00Z",
          "_subject" : "http://localhost/v1/realms/realm/users/user"
        }"""
    }
  }
}
