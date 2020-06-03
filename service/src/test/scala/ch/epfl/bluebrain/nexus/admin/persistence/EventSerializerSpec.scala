package ch.epfl.bluebrain.nexus.admin.persistence

import java.time.Instant
import java.util.UUID

import akka.actor.ExtendedActorSystem
import ch.epfl.bluebrain.nexus.admin.organizations.OrganizationEvent._
import ch.epfl.bluebrain.nexus.admin.projects.ProjectEvent._
import ch.epfl.bluebrain.nexus.commons.test.{ActorSystemFixture, EitherValues, Resources}
import ch.epfl.bluebrain.nexus.iam.client.types.Identity
import ch.epfl.bluebrain.nexus.rdf.implicits._
import io.circe.Json
import io.circe.parser.parse
import org.scalatest.Inspectors
import org.scalatest.matchers.should.Matchers

class EventSerializerSpec
    extends ActorSystemFixture("EventSerializerSpec")
    with Matchers
    with Inspectors
    with EitherValues
    with Resources {

  private val orgId   = UUID.fromString("d8cf3015-1bce-4dda-ba80-80cd4b5281e5")
  private val projId  = UUID.fromString("27f5429c-b56f-4f8e-8481-f4334ebb334c")
  private val instant = Instant.parse("2018-12-21T15:37:44.203831Z")
  private val subject = Identity.User("alice", "bbp")
  private val base    = url"http://localhost:8080/base"
  private val voc     = url"http://localhost:8080/voc"
  private val mappings = Map(
    "nxv" -> url"https://bluebrain.github.io/nexus/vocabulary/",
    "rdf" -> url"http://www.w3.org/1999/02/22-rdf-syntax-ns#"
  )

  private val data: Map[AnyRef, (String, Json)] = Map(
    (
      OrganizationCreated(orgId, "myorg", Some("My organization"), instant, subject),
      ("OrganizationEvent", jsonContentOf("/serializer/org-created.json"))
    ),
    (
      OrganizationUpdated(orgId, 42L, "myorg", Some("My organization"), instant, subject),
      ("OrganizationEvent", jsonContentOf("/serializer/org-updated.json"))
    ),
    (
      OrganizationDeprecated(orgId, 42L, instant, subject),
      ("OrganizationEvent", jsonContentOf("/serializer/org-deprecated.json"))
    ),
    (
      ProjectCreated(projId, "myproj", orgId, "myorg", None, mappings, base, voc, instant, subject),
      ("ProjectEvent", jsonContentOf("/serializer/project-created.json"))
    ),
    (
      ProjectUpdated(projId, "myproj", Some("My project"), mappings, base, voc, 42L, instant, subject),
      ("ProjectEvent", jsonContentOf("/serializer/project-updated.json"))
    ),
    (
      ProjectDeprecated(projId, 42L, instant, subject),
      ("ProjectEvent", jsonContentOf("/serializer/project-deprecated.json"))
    )
  )

  "An EventSerializer" should {
    val serializer = new EventSerializer(system.asInstanceOf[ExtendedActorSystem])

    "produce the correct event manifests" in {
      forAll(data.toList) {
        case (event, (manifest, _)) =>
          serializer.manifest(event) shouldEqual manifest
      }
    }

    "correctly serialize known serializer" in {
      forAll(data.toList) {
        case (event, (_, json)) =>
          parse(new String(serializer.toBinary(event))).rightValue shouldEqual json
      }
    }

    "correctly deserialize known serializer" in {
      forAll(data.toList) {
        case (event, (manifest, json)) =>
          serializer.fromBinary(json.noSpaces.getBytes, manifest) shouldEqual event
      }
    }

    "fail to produce a manifest" in {
      intercept[IllegalArgumentException](serializer.manifest("aaa"))
    }

    "fail to serialize an unknown type" in {
      intercept[IllegalArgumentException](serializer.toBinary("aaa"))
    }

    "fail to deserialize an unknown type" in {
      forAll(data.toList) {
        case (event, (manifest, repr)) =>
          intercept[IllegalArgumentException] {
            serializer.fromBinary((repr.spaces2 + "typo").getBytes, manifest) shouldEqual event
          }
      }
    }
  }

}
