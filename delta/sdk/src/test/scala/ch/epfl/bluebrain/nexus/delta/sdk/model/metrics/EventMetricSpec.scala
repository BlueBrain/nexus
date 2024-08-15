package ch.epfl.bluebrain.nexus.delta.sdk.model.metrics

import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.nxv
import ch.epfl.bluebrain.nexus.delta.sdk.model.metrics.EventMetric.ProjectScopedMetric
import ch.epfl.bluebrain.nexus.delta.sdk.model.metrics.EventMetricSpec.SimpleEvent
import ch.epfl.bluebrain.nexus.delta.sourcing.event.Event.ScopedEvent
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.{Anonymous, Subject}
import ch.epfl.bluebrain.nexus.delta.sourcing.model.ProjectRef
import ch.epfl.bluebrain.nexus.testkit.CirceLiteral
import ch.epfl.bluebrain.nexus.testkit.scalatest.BaseSpec
import io.circe.JsonObject
import io.circe.syntax.EncoderOps

import java.time.Instant

class EventMetricSpec extends BaseSpec with CirceLiteral {

  "A metric" should {
    "be correctly created from the event" in {
      val event = SimpleEvent(
        ProjectRef.unsafe("org", "proj"),
        2,
        Instant.EPOCH,
        Anonymous
      )

      val id          = nxv + "id"
      val types       = Set(nxv + "Type1", nxv + "Type1#Type2")
      val extraFields = JsonObject("extra" -> "someString".asJson)

      val metric: EventMetric = ProjectScopedMetric.from[SimpleEvent](
        event,
        EventMetric.Created,
        id,
        types,
        extraFields
      )

      metric shouldEqual ProjectScopedMetric(
        Instant.EPOCH,
        Anonymous,
        2,
        Set(EventMetric.Created),
        event.project,
        event.project.organization,
        id,
        types,
        extraFields
      )

      metric.asJson shouldEqual json"""{
                                        "instant" : "1970-01-01T00:00:00Z",
                                        "subject" : {
                                          "@type" : "Anonymous"
                                        },
                                        "action" : ["Created"],
                                        "@id" : "https://bluebrain.github.io/nexus/vocabulary/id",
                                        "@type" : [
                                          {
                                            "raw": "https://bluebrain.github.io/nexus/vocabulary/Type1",
                                            "short": "Type1"
                                          },
                                          {
                                            "raw": "https://bluebrain.github.io/nexus/vocabulary/Type1#Type2",
                                            "short": "Type2"
                                          }
                                        ],
                                        "project" : "org/proj",
                                        "organization" : "org",
                                        "extra" : "someString"
                                      }"""
    }
  }

}

object EventMetricSpec {

  final case class SimpleEvent(project: ProjectRef, rev: Int, instant: Instant, subject: Subject) extends ScopedEvent {
    def id: Iri = nxv + "id"
  }

}
