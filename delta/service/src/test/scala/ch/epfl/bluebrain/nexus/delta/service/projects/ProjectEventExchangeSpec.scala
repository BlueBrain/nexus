package ch.epfl.bluebrain.nexus.delta.service.projects

import ch.epfl.bluebrain.nexus.delta.kernel.utils.UUIDF
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.{contexts, nxv}
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.RemoteContextResolution
import ch.epfl.bluebrain.nexus.delta.sdk.generators.ProjectGen
import ch.epfl.bluebrain.nexus.delta.sdk.implicits._
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Identity
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Identity.Subject
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.Project.Metadata
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.ProjectEvent.ProjectDeprecated
import ch.epfl.bluebrain.nexus.delta.sdk.model.{BaseUri, Label, TagLabel}
import ch.epfl.bluebrain.nexus.delta.sdk.testkit.ProjectSetup
import ch.epfl.bluebrain.nexus.testkit.{IOFixedClock, IOValues, TestHelpers}
import io.circe.literal._
import io.circe.syntax._
import monix.execution.Scheduler
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike
import org.scalatest.{Inspectors, OptionValues}

import java.time.Instant
import java.util.UUID

class ProjectEventExchangeSpec
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

  implicit private def res: RemoteContextResolution =
    RemoteContextResolution.fixed(
      contexts.metadata -> jsonContentOf("contexts/metadata.json").topContextValueOrEmpty,
      contexts.projects -> jsonContentOf("contexts/projects.json").topContextValueOrEmpty
    )

  private val org     = Label.unsafe("myorg")
  private val project = ProjectGen.project("myorg", "myproject", base = nxv.base, uuid = uuid, orgUuid = uuid)
  println(project)

  private val (_, projects) = ProjectSetup
    .init(
      orgsToCreate = org :: Nil,
      projectsToCreate = project :: Nil
    )
    .accepted

  "A ProjectEventExchange" should {
    val tag             = TagLabel.unsafe("tag")
    val deprecatedEvent = ProjectDeprecated(
      project.label,
      uuid,
      project.organizationLabel,
      project.organizationUuid,
      1,
      Instant.EPOCH,
      subject
    )

    val exchange = new ProjectEventExchange(projects)

    "return the latest resource state from the event" in {
      println(projects.fetch(project.ref).accepted)
      val result = exchange.toLatestResource(deprecatedEvent, None).accepted.value
      result.value.toSource shouldEqual project.asJson
      result.value.toResource shouldEqual ProjectGen.resourceFor(project, subject = subject)
      result.metadata.value shouldEqual
        Metadata(project.label, uuid, project.organizationLabel, project.organizationUuid)
    }

    "return None at a particular tag" in {
      exchange.toLatestResource(deprecatedEvent, Some(tag)).accepted shouldEqual None
    }

    "return the encoded event" in {
      val result = exchange.toJsonLdEvent(deprecatedEvent).value
      result.value shouldEqual deprecatedEvent
      result.encoder.compact(result.value).accepted.json shouldEqual
        json"""{
          "@context" : [${contexts.metadata}, ${contexts.projects}],
          "@type" : "ProjectDeprecated",
          "_projectId" : "http://localhost/v1/projects/myorg/myproject",
          "_label" : "myproject",
          "_rev" : 1,
          "_instant" : "1970-01-01T00:00:00Z",
          "_subject" : "http://localhost/v1/realms/realm/users/user",
          "_organizationLabel" : "myorg",
          "_organizationUuid" : ${uuid},
          "_uuid" : ${uuid}
        }"""
    }
  }
}
