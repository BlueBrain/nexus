package ai.senscience.nexus.tests.kg

import ai.senscience.nexus.tests.BaseIntegrationSpec
import ai.senscience.nexus.tests.Identity.{Anonymous, typehierarchy}
import ai.senscience.nexus.tests.iam.types.Permission.TypeHierarchy
import akka.http.scaladsl.model.StatusCodes
import cats.effect.{IO, Ref}
import io.circe.Json
import io.circe.syntax.{EncoderOps, KeyOps}
import org.scalatest.Assertion

import java.time.Instant

class TypeHierarchySpec extends BaseIntegrationSpec {

  private val mapping =
    Json.obj(
      "mapping" := Json.obj(
        "https://schema.org/VideoGame" := Json.arr(
          "https://schema.org/SoftwareApplication".asJson,
          "https://schema.org/Thing".asJson
        )
      )
    )

  override def beforeAll(): Unit = {
    super.beforeAll()
    aclDsl.addPermission("/", typehierarchy.Writer, TypeHierarchy.Write).accepted
    deltaClient
      .post[Json]("/type-hierarchy", mapping, typehierarchy.Writer) { (_, _) =>
        assert(true)
      }
      .accepted
    ()
  }

  private val typeHierarchyRevisionRef = Ref.unsafe[IO, Int](0)
  private def currentRev: Int          = typeHierarchyRevisionRef.get.accepted

  "type hierarchy" should {

    "not be created without permissions" in {
      deltaClient.post[Json]("/type-hierarchy", mapping, Anonymous) { (json, response) =>
        response.status shouldEqual StatusCodes.Forbidden
        json shouldEqual authorizationFailed("POST")
      }
    }

    "not be created if it already exists" in {
      deltaClient.post[Json]("/type-hierarchy", mapping, typehierarchy.Writer) { (error, response) =>
        response.status shouldEqual StatusCodes.Conflict
        error shouldEqual typeHierarchyAlreadyExists
      }
    }

    "be fetched" in {
      deltaClient.get[Json]("/type-hierarchy", Anonymous) { (json, response) =>
        response.status shouldEqual StatusCodes.OK
        typeHierarchyRevisionRef.set(json.hcursor.get[Int]("_rev").rightValue).accepted
        json.hcursor.get[Json]("mapping").rightValue shouldEqual mapping.hcursor.get[Json]("mapping").rightValue
        assertMetadata(json, rev = currentRev)
      }
    }

    "not be fetched with invalid revision" in {
      val rev = currentRev
      deltaClient.get[Json](s"/type-hierarchy?rev=${rev + 1}", Anonymous) { (json, response) =>
        response.status shouldEqual StatusCodes.BadRequest
        json shouldEqual revisionNotFound(rev + 1, rev)
      }
    }

    "not be updated without permissions" in {
      val rev = currentRev
      deltaClient.put[Json](s"/type-hierarchy?rev=$rev", mapping, Anonymous) { (json, response) =>
        response.status shouldEqual StatusCodes.Forbidden
        json shouldEqual authorizationFailed("PUT", Some(rev))
      }
    }

    "be updated with write permissions" in {
      val rev = currentRev
      deltaClient.put[Json](s"/type-hierarchy?rev=$rev", mapping, typehierarchy.Writer) { (json, response) =>
        response.status shouldEqual StatusCodes.OK
        json.hcursor.get[Int]("_rev").rightValue shouldEqual (rev + 1)
        assertMetadata(json, rev = rev + 1)
      }
    }

  }

  def assertMetadata(json: Json, rev: Int): Assertion = {
    json.hcursor.get[Instant]("_createdAt").toOption should not be empty
    json.hcursor.get[Instant]("_updatedAt").toOption should not be empty
    json.hcursor.get[String]("_createdBy").toOption should not be empty
    json.hcursor.get[String]("_updatedBy").toOption should not be empty
    json.hcursor.get[String]("_self").toOption should not be empty
    json.hcursor.get[String]("_constrainedBy").toOption should not be empty
    json.hcursor.get[Boolean]("_deprecated").rightValue shouldEqual false
    json.hcursor.get[Int]("_rev").rightValue shouldEqual rev
  }

  def revisionNotFound(requestedRev: Int, latestRev: Int): Json =
    json"""{
             "@context" : "https://bluebrain.github.io/nexus/contexts/error.json",
             "reason":"Revision requested '$requestedRev' not found, last known revision is '$latestRev'."
           }"""

  def authorizationFailed(method: String, rev: Option[Int] = None): Json =
    json"""
          {
            "@context" : "https://bluebrain.github.io/nexus/contexts/error.json",
            "@type" : "AuthorizationFailed",
            "reason" : "The supplied authentication is not authorized to access this resource.",
            "details" : "Permission 'typehierarchy/write' is missing on '/'.\\nIncoming request was 'http://localhost:8080/v1/type-hierarchy${rev
        .map(r => s"?rev=$r")
        .getOrElse("")}' ('$method')."
          }
        """

  def typeHierarchyAlreadyExists: Json =
    json"""
          {
            "@context" : "https://bluebrain.github.io/nexus/contexts/error.json",
            "reason" : "Type hierarchy already exists."
          }
        """

}
