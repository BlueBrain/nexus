package ch.epfl.bluebrain.nexus.delta.service.projects

import ch.epfl.bluebrain.nexus.delta.kernel.utils.UUIDF
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.{contexts, nxv}
import ch.epfl.bluebrain.nexus.delta.sdk.Resources
import ch.epfl.bluebrain.nexus.delta.sdk.generators.ProjectGen
import ch.epfl.bluebrain.nexus.delta.sdk.generators.ProjectGen.defaultApiMappings
import ch.epfl.bluebrain.nexus.delta.sdk.implicits._
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Identity
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Identity.Subject
import ch.epfl.bluebrain.nexus.delta.sdk.model.metrics.EventMetric
import ch.epfl.bluebrain.nexus.delta.sdk.model.metrics.EventMetric.ProjectScopedMetric
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.Project.Metadata
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.ProjectEvent.ProjectDeprecated
import ch.epfl.bluebrain.nexus.delta.sdk.model.{BaseUri, Label}
import ch.epfl.bluebrain.nexus.delta.sdk.testkit.ProjectSetup
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Tag.UserTag
import ch.epfl.bluebrain.nexus.testkit.{IOFixedClock, IOValues, TestHelpers}
import io.circe.JsonObject
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

  private val org     = Label.unsafe("myorg")
  private val project = ProjectGen.project(
    "myorg",
    "myproject",
    base = nxv.base,
    uuid = uuid,
    orgUuid = uuid,
    mappings = Resources.mappings
  )

  private val (_, projects) = ProjectSetup
    .init(
      orgsToCreate = org :: Nil,
      projectsToCreate = project :: Nil
    )
    .accepted

  "A ProjectEventExchange" should {
    val tag             = UserTag.unsafe("tag")
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
      val result = exchange.toResource(deprecatedEvent, None).accepted.value
      result.value.source shouldEqual project.asJson
      result.value.resource shouldEqual ProjectGen.resourceFor(project, subject = subject)
      result.metadata.value shouldEqual
        Metadata(project.label, uuid, project.organizationLabel, project.organizationUuid, project.apiMappings, false)
    }

    "return None at a particular tag" in {
      exchange.toResource(deprecatedEvent, Some(tag)).accepted shouldEqual None
    }

    "return the metric" in {
      val metric = exchange.toMetric(deprecatedEvent).accepted.value

      metric shouldEqual ProjectScopedMetric(
        Instant.EPOCH,
        subject,
        1L,
        EventMetric.Deprecated,
        project.ref,
        project.organizationLabel,
        iri"http://localhost/v1/projects/myorg/myproject",
        Set(nxv.Project),
        JsonObject.empty
      )
    }

    "return the encoded event" in {
      val result = exchange.toJsonEvent(deprecatedEvent).value
      result.value shouldEqual deprecatedEvent
      result.encoder(result.value) shouldEqual
        json"""{
          "@context" : [${contexts.metadata}, ${contexts.projects}],
          "@type" : "ProjectDeprecated",
          "_projectId" : "http://localhost/v1/projects/myorg/myproject",
          "_resourceId" : "http://localhost/v1/projects/myorg/myproject",
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
