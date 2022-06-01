package ch.epfl.bluebrain.nexus.delta.service.organizations

import ch.epfl.bluebrain.nexus.delta.kernel.utils.UUIDF
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.contexts
import ch.epfl.bluebrain.nexus.delta.sdk.generators.OrganizationGen
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Identity
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Identity.Subject
import ch.epfl.bluebrain.nexus.delta.sdk.model.organizations.Organization.Metadata
import ch.epfl.bluebrain.nexus.delta.sdk.model.organizations.OrganizationEvent.OrganizationCreated
import ch.epfl.bluebrain.nexus.delta.sdk.model.BaseUri
import ch.epfl.bluebrain.nexus.delta.sdk.testkit.OrganizationsDummy
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Tag.UserTag
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Label
import ch.epfl.bluebrain.nexus.testkit.{IOFixedClock, IOValues, TestHelpers}
import io.circe.literal._
import io.circe.syntax._
import monix.execution.Scheduler
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike
import org.scalatest.{Inspectors, OptionValues}

import java.time.Instant
import java.util.UUID

class OrganizationEventExchangeSpec
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
  private val uuid                      = UUID.randomUUID()
  implicit private val uuidF: UUIDF     = UUIDF.fixed(uuid)

  private val label        = Label.unsafe("myorg")
  private val organization = OrganizationGen.organization(label.value, uuid, Some("description"))

  val orgs = OrganizationsDummy().accepted
  orgs.create(label, Some("description")).accepted

  "A OrganizationEventExchange" should {
    val tag          = UserTag.unsafe("tag")
    val createdEvent = OrganizationCreated(label, uuid, 1, Some("description"), Instant.EPOCH, subject)

    val exchange = new OrganizationEventExchange(orgs)

    "return the latest resource state from the event" in {
      val result = exchange.toResource(createdEvent, None).accepted.value
      result.value.source shouldEqual organization.asJson
      result.value.resource shouldEqual OrganizationGen.resourceFor(organization, subject = subject)
      result.metadata.value shouldEqual Metadata(label, uuid)
    }

    "return None at a particular tag" in {
      exchange.toResource(createdEvent, Some(tag)).accepted shouldEqual None
    }

    "return the encoded event" in {
      val result = exchange.toJsonEvent(createdEvent).value
      result.value shouldEqual createdEvent
      result.encoder(result.value) shouldEqual
        json"""{
          "@context" : [${contexts.metadata}, ${contexts.organizations}],
          "@type" : "OrganizationCreated",
          "description": "description",
          "_organizationId" : "http://localhost/v1/orgs/myorg",
          "_resourceId" : "http://localhost/v1/orgs/myorg",
          "_label" : "myorg",
          "_rev" : 1,
          "_instant" : "1970-01-01T00:00:00Z",
          "_subject" : "http://localhost/v1/realms/realm/users/user",
          "_uuid" : ${uuid}
        }"""
    }
  }
}
