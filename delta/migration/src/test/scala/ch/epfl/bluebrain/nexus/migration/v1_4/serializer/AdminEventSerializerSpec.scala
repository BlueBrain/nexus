package ch.epfl.bluebrain.nexus.migration.v1_4.serializer

import ch.epfl.bluebrain.nexus.delta.sdk.model.Label
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Identity
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.PrefixIri
import ch.epfl.bluebrain.nexus.delta.sdk.syntax._
import ch.epfl.bluebrain.nexus.migration.v1_4.events.admin.OrganizationEvent.{OrganizationCreated, OrganizationDeprecated, OrganizationUpdated}
import ch.epfl.bluebrain.nexus.migration.v1_4.events.admin.ProjectEvent.{ProjectCreated, ProjectDeprecated, ProjectUpdated}
import ch.epfl.bluebrain.nexus.testkit.TestHelpers
import io.circe.Json
import org.scalatest.Inspectors
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

import java.time.Instant
import java.util.UUID

class AdminEventSerializerSpec extends AnyWordSpecLike with Matchers with Inspectors with TestHelpers {

  private val orgId    = UUID.fromString("d8cf3015-1bce-4dda-ba80-80cd4b5281e5")
  private val projId   = UUID.fromString("27f5429c-b56f-4f8e-8481-f4334ebb334c")
  private val instant  = Instant.parse("2018-12-21T15:37:44.203831Z")
  private val subject  = Identity.User("alice", Label.unsafe("bbp"))
  private val base     = PrefixIri.unsafe(iri"http://localhost:8080/base/")
  private val voc      = PrefixIri.unsafe(iri"http://localhost:8080/voc/")
  private val mappings = Map(
    "nxv" -> iri"https://bluebrain.github.io/nexus/vocabulary/",
    "rdf" -> iri"http://www.w3.org/1999/02/22-rdf-syntax-ns#"
  )

  private val data: Map[AnyRef, (String, Json)] = Map(
    (
      OrganizationCreated(orgId, Label.unsafe("myorg"), Some("My organization"), instant, subject),
      ("OrganizationEvent", jsonContentOf("/serialization/org-created.json"))
    ),
    (
      OrganizationUpdated(orgId, 42L, Label.unsafe("myorg"), Some("My organization"), instant, subject),
      ("OrganizationEvent", jsonContentOf("/serialization/org-updated.json"))
    ),
    (
      OrganizationDeprecated(orgId, 42L, instant, subject),
      ("OrganizationEvent", jsonContentOf("/serialization/org-deprecated.json"))
    ),
    (
      ProjectCreated(
        projId,
        "myproj",
        orgId,
        Label.unsafe("myorg"),
        None,
        mappings,
        base,
        voc,
        instant,
        subject
      ),
      ("ProjectEvent", jsonContentOf("/serialization/project-created.json"))
    ),
    (
      ProjectUpdated(projId, "myproj", Some("My project"), mappings, base, voc, 42L, instant, subject),
      ("ProjectEvent", jsonContentOf("/serialization/project-updated.json"))
    ),
    (
      ProjectDeprecated(projId, 42L, instant, subject),
      ("ProjectEvent", jsonContentOf("/serialization/project-deprecated.json"))
    )
  )

  "An EventSerializer" should {

    "correctly deserialize known serializer" in {
      forAll(data.toList) { case (event, (manifest, json)) =>
        AdminEventSerializer.fromBinary(json.noSpaces.getBytes, manifest) shouldEqual event
      }
    }
  }

}
