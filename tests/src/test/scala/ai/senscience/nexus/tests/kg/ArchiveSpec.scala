package ai.senscience.nexus.tests.kg

import ai.senscience.nexus.tests.HttpClient.*
import ai.senscience.nexus.tests.Identity.archives.Tweety
import ai.senscience.nexus.tests.Optics.*
import ai.senscience.nexus.tests.admin.ProjectPayload
import ai.senscience.nexus.tests.iam.types.Permission.{Projects, Resources}
import ai.senscience.nexus.tests.resources.SimpleResource
import ai.senscience.nexus.tests.{BaseIntegrationSpec, Identity, SchemaPayload}
import akka.http.scaladsl.model.headers.{Accept, Location}
import akka.http.scaladsl.model.{MediaRanges, MediaTypes, StatusCodes, Uri}
import akka.http.scaladsl.unmarshalling.PredefinedFromEntityUnmarshallers
import akka.util.ByteString
import ch.epfl.bluebrain.nexus.delta.kernel.utils.UrlUtils
import ch.epfl.bluebrain.nexus.test.archive.ArchiveHelpers
import io.circe.Json

import java.nio.file.Paths

class ArchiveSpec extends BaseIntegrationSpec with ArchiveHelpers {

  private val orgId   = genId()
  private val projId  = genId()
  private val projId2 = genId()
  private val fullId  = s"$orgId/$projId"
  private val fullId2 = s"$orgId/$projId2"

  private val schemaId      = "test-schema"
  private val schemaPayload = SchemaPayload.loadSimple().accepted

  private val resource1Id = "https://dev.nexus.test.com/simplified-resource/1"
  private val payload1    = SimpleResource.sourcePayload(resource1Id, 5).accepted

  private val resource2Id = "https://dev.nexus.test.com/simplified-resource/2"
  private val payload2    = SimpleResource.sourcePayload(resource2Id, 6).accepted

  private val payloadResponse1 = SimpleResource.fetchResponse(Tweety, fullId, resource1Id, 1, 5).accepted

  private val payloadResponse2 = SimpleResource.fetchResponse(Tweety, fullId2, resource2Id, 1, 6).accepted

  private val nexusLogoDigest =
    "edd70eff895cde1e36eaedd22ed8e9c870bb04155d05d275f970f4f255488e993a32a7c914ee195f6893d43b8be4e0b00db0a6d545a8462491eae788f664ea6b"

  private[tests] def archiveSelf(project: String, id: String): String = {
    val uri = Uri(s"${config.deltaUri}/archives/$project")
    uri.copy(path = uri.path / id).toString
  }

  "Setup" should {

    "create projects, resources and add necessary acls" in {
      for {
        _ <- adminDsl.createOrganization(orgId, orgId, Identity.ServiceAccount)
        _ <- aclDsl.addPermission(s"/$orgId", Tweety, Projects.Create)
        _ <- adminDsl.createProject(orgId, projId, ProjectPayload.generate(fullId), Tweety)
        _ <- adminDsl.createProject(orgId, projId2, ProjectPayload.generate(fullId2), Tweety)
      } yield succeed
    }

    "create test schemas" in {
      for {
        _ <- deltaClient.put[Json](s"/schemas/$fullId/$schemaId", schemaPayload, Tweety) { expectCreated }
        _ <- deltaClient.put[Json](s"/schemas/$fullId2/$schemaId", schemaPayload, Tweety) { expectCreated }
      } yield succeed
    }

    "create resources" in {
      for {
        _ <- deltaClient.putAttachmentFromPath[Json](
               s"/files/$fullId/test-resource:logo",
               Paths.get(getClass.getResource("/kg/files/nexus-logo.png").toURI),
               MediaTypes.`image/png`,
               "nexus-logo.png",
               Tweety
             ) { expectCreated }
        _ <- deltaClient.put[Json](s"/resources/$fullId/$schemaId/test-resource:1", payload1, Tweety) { expectCreated }
        _ <- deltaClient.put[Json](s"/resources/$fullId2/$schemaId/test-resource:2", payload2, Tweety) { expectCreated }
      } yield succeed
    }
  }

  "creating archives" should {
    "succeed" in {
      val payload = jsonContentOf("kg/archives/archive.json", "project2" -> fullId2)
      deltaClient.put[Json](s"/archives/$fullId/test-resource:archive", payload, Tweety) { (_, response) =>
        response.status shouldEqual StatusCodes.Created
      }
    }

    "succeed with file link" in {
      var fileSelf: String = ""
      val archiveId        = "test-resource:archive-link"
      for {
        _      <- deltaClient.get[Json](s"/files/$fullId/test-resource:logo", Tweety) { (json, response) =>
                    fileSelf = json.hcursor.downField("_self").as[String].toOption.value
                    response.status shouldEqual StatusCodes.OK
                  }
        payload = jsonContentOf("kg/archives/archive-with-file-self.json", "value" -> fileSelf)
        _      <- deltaClient.put[Json](s"/archives/$fullId/$archiveId", payload, Tweety) { expectCreated }
        _      <- deltaClient.get[ByteString](s"/archives/$fullId/$archiveId", Tweety, acceptZip) { (byteString, response) =>
                    response.status shouldEqual StatusCodes.OK
                    contentType(response) shouldEqual MediaTypes.`application/zip`.toContentType
                    val result = fromZip(byteString)

                    val resource1FileName = UrlUtils.encodeUri(s"$resource1Id?rev=1")

                    val actualContent1 = result.entryAsJson(s"$fullId/compacted/${resource1FileName}.json")
                    val actualDigest3  = result.entryDigest("/some/other/nexus-logo.png")

                    filterMetadataKeys(actualContent1) should equalIgnoreArrayOrder(payloadResponse1)
                    actualDigest3 shouldEqual nexusLogoDigest
                  }

      } yield succeed
    }

    "succeed and redirect" in {
      val payload = jsonContentOf("kg/archives/archive.json", "project2" -> fullId2)

      val archiveId       = "https://dev.nexus.test.com/simplified-resource/archiveRedirect"
      val archiveLocation = s"${config.deltaUri}/archives/$fullId/${UrlUtils.encodeUriPath(archiveId)}"

      deltaClient.put[String](
        s"/archives/$fullId/test-resource:archiveRedirect",
        payload,
        Tweety,
        extraHeaders = List(Accept(MediaRanges.`*/*`))
      )({ (string, response) =>
        string should startWith("The response to the request can be found under")
        response.status shouldEqual StatusCodes.SeeOther
        val locationHeaderValue = response.header[Location].value.uri.toString()
        locationHeaderValue shouldEqual archiveLocation
      })(PredefinedFromEntityUnmarshallers.stringUnmarshaller)
    }

    "fail if payload is wrong" in {
      val payload = jsonContentOf("kg/archives/archive-wrong.json")

      deltaClient.put[Json](s"/archives/$fullId/archive2", payload, Tweety) { (json, response) =>
        response.status shouldEqual StatusCodes.BadRequest
        filterKey("report")(json) shouldEqual jsonContentOf("kg/archives/archive-wrong-response.json")
      }
    }

    "fail on wrong path" in {
      val wrong1    = jsonContentOf(s"kg/archives/archive-wrong-path1.json")
      val expected1 = jsonContentOf("kg/archives/archive-path-invalid1.json")

      for {
        _        <- deltaClient.put[Json](s"/archives/$fullId/archive2", wrong1, Tweety) { (json, response) =>
                      json shouldEqual expected1
                      response.status shouldEqual StatusCodes.BadRequest
                    }
        wrong2    = jsonContentOf(s"kg/archives/archive-wrong-path2.json")
        expected2 = jsonContentOf("kg/archives/archive-path-invalid2.json")
        _        <- deltaClient.put[Json](s"/archives/$fullId/archive2", wrong2, Tweety) { (json, response) =>
                      json shouldEqual expected2
                      response.status shouldEqual StatusCodes.BadRequest
                    }
      } yield succeed
    }

    "fail on path collisions" in {
      val wrong    = jsonContentOf(s"kg/archives/archive-path-collision.json")
      val expected = jsonContentOf(s"kg/archives/archive-path-dup.json")

      deltaClient.put[Json](s"/archives/$fullId/archive2", wrong, Tweety) { (json, response) =>
        json shouldEqual expected
        response.status shouldEqual StatusCodes.BadRequest
      }
    }
  }

  "fetching archive" should {
    "succeed returning metadata" in {
      deltaClient.get[Json](s"/archives/$fullId/test-resource:archive", Tweety) { (json, response) =>
        response.status shouldEqual StatusCodes.OK
        val expected = jsonContentOf(
          "kg/archives/archive-response.json",
          replacements(
            Tweety,
            "project2" -> fullId2,
            "self"     -> archiveSelf(fullId, "https://dev.nexus.test.com/simplified-resource/archive"),
            "project1" -> fullId
          )*
        )
        filterKeys(
          Set("_createdAt", "_updatedAt", "_expiresInSeconds")
        )(json) should equalIgnoreArrayOrder(expected)
      }
    }

    "succeed returning zip" in {
      deltaClient.get[ByteString](s"/archives/$fullId/test-resource:archive", Tweety, acceptZip) {
        (byteString, response) =>
          contentType(response) shouldEqual MediaTypes.`application/zip`.toContentType
          response.status shouldEqual StatusCodes.OK

          val result = fromZip(byteString)

          val resource1FileName = UrlUtils.encodeUri(s"$resource1Id?rev=1")
          val actualContent1    = result.entryAsJson(s"$fullId/compacted/$resource1FileName.json")
          val resource2FileName = UrlUtils.encodeUri(resource2Id)
          val actualContent2    = result.entryAsJson(s"$fullId2/compacted/$resource2FileName.json")
          val actualDigest3     = result.entryDigest("/some/other/nexus-logo.png")

          filterMetadataKeys(actualContent1) should equalIgnoreArrayOrder(payloadResponse1)
          filterMetadataKeys(actualContent2) should equalIgnoreArrayOrder(payloadResponse2)
          actualDigest3 shouldEqual nexusLogoDigest
      }
    }

    "delete resources/read permissions for user on project 2" in
      aclDsl.deletePermission(s"/$fullId2", Tweety, 1, Resources.Read)

    "fail when a resource in the archive cannot be fetched due to missing permissions" in {
      deltaClient.get[Json](s"/archives/$fullId/test-resource:archive", Tweety, acceptAll) { expectForbidden }
    }

    "succeed getting archive using query param ignoreNotFound" in {
      val payload = jsonContentOf("kg/archives/archive-not-found.json")

      def assertContent(archive: Map[String, ByteString]) = {
        val resource1FileName = UrlUtils.encodeUri(s"$resource1Id?rev=1")
        val actualContent1    = archive.entryAsJson(s"$fullId/compacted/$resource1FileName.json")
        filterMetadataKeys(actualContent1) should equalIgnoreArrayOrder(payloadResponse1)
      }

      for {
        _           <- deltaClient.put[ByteString](s"/archives/$fullId/test-resource:archive-not-found", payload, Tweety) {
                         expectCreated
                       }
        downloadLink = s"/archives/$fullId/test-resource:archive-not-found?ignoreNotFound=true"
        _           <- deltaClient.get[ByteString](downloadLink, Tweety, acceptZip) { (byteString, response) =>
                         contentType(response) shouldEqual MediaTypes.`application/zip`.toContentType
                         response.status shouldEqual StatusCodes.OK
                         assertContent(fromZip(byteString))
                       }
      } yield succeed
    }
  }
}
