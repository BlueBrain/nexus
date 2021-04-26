package ch.epfl.bluebrain.nexus.tests.kg

import akka.http.scaladsl.model.headers.{Accept, Location}
import akka.http.scaladsl.model.{MediaRanges, MediaTypes, StatusCodes}
import akka.http.scaladsl.unmarshalling.PredefinedFromEntityUnmarshallers
import akka.util.ByteString
import ch.epfl.bluebrain.nexus.testkit.{CirceEq, EitherValuable}
import ch.epfl.bluebrain.nexus.tests.HttpClient._
import ch.epfl.bluebrain.nexus.tests.Identity.UserCredentials
import ch.epfl.bluebrain.nexus.tests.Optics._
import ch.epfl.bluebrain.nexus.tests.Tags.ArchivesTag
import ch.epfl.bluebrain.nexus.tests.iam.types.Permission.{Projects, Resources}
import ch.epfl.bluebrain.nexus.tests.{BaseSpec, Identity, Realm}
import io.circe.Json
import io.circe.parser._
import monix.execution.Scheduler.Implicits.global
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream

import java.io.ByteArrayInputStream
import java.nio.file.{Path, Paths}
import java.security.MessageDigest
import scala.annotation.tailrec

class ArchiveSpec extends BaseSpec with CirceEq with EitherValuable {

  private val testRealm  = Realm("resources" + genString())
  private val testClient = Identity.ClientCredentials(genString(), genString(), testRealm)
  private val Tweety     = UserCredentials(genString(), genString(), testRealm)

  private val orgId   = genId()
  private val projId  = genId()
  private val projId2 = genId()
  private val fullId  = s"$orgId/$projId"
  private val fullId2 = s"$orgId/$projId2"

  private val schemaPayload = jsonContentOf("/kg/schemas/simple-schema.json")

  private val payload1 = jsonContentOf(
    "/kg/resources/simple-resource.json",
    "priority"   -> "5",
    "resourceId" -> "1"
  )

  private val payload2 = jsonContentOf(
    "/kg/resources/simple-resource.json",
    "priority"   -> "6",
    "resourceId" -> "2"
  )

  private val payloadResponse1 = jsonContentOf(
    "/kg/resources/simple-resource-response.json",
    "deltaUri"   -> config.deltaUri,
    "realm"      -> testRealm.name,
    "user"       -> Tweety.name,
    "priority"   -> "5",
    "rev"        -> "1",
    "resources"  -> s"${config.deltaUri}/resources/$fullId",
    "project"    -> s"${config.deltaUri}/projects/$fullId",
    "resourceId" -> "1"
  )

  private val payloadResponse2 = jsonContentOf(
    "/kg/resources/simple-resource-response.json",
    "deltaUri"   -> config.deltaUri,
    "realm"      -> testRealm.name,
    "user"       -> Tweety.name,
    "priority"   -> "6",
    "rev"        -> "1",
    "resources"  -> s"${config.deltaUri}/resources/$fullId2",
    "project"    -> s"${config.deltaUri}/projects/$fullId2",
    "resourceId" -> "2"
  )

  private val nexusLogoDigest =
    "edd70eff895cde1e36eaedd22ed8e9c870bb04155d05d275f970f4f255488e993a32a7c914ee195f6893d43b8be4e0b00db0a6d545a8462491eae788f664ea6b"

  private type PathAndContent = (Path, ByteString)

  override def beforeAll(): Unit = {
    super.beforeAll()
    initRealm(
      testRealm,
      Identity.ServiceAccount,
      testClient,
      Tweety :: Nil
    ).runSyncUnsafe()
  }

  @tailrec
  private def readEntries(tar: TarArchiveInputStream, entries: List[PathAndContent] = Nil): List[PathAndContent] = {
    val entry = tar.getNextTarEntry
    if (entry == null) entries
    else {
      val data = Array.ofDim[Byte](entry.getSize.toInt)
      tar.read(data)
      readEntries(tar, (Paths.get(entry.getName) -> ByteString(data)) :: entries)
    }
  }

  def digest(byteString: ByteString): String = {
    val digest = MessageDigest.getInstance("SHA-512")
    digest.update(byteString.asByteBuffer)
    digest.digest().map("%02x".format(_)).mkString
  }

  "Setup" should {

    "create projects, resources and add necessary acls" taggedAs ArchivesTag in {
      for {
        _ <- adminDsl.createOrganization(orgId, orgId, Identity.ServiceAccount)
        _ <- aclDsl.addPermission(s"/$orgId", Tweety, Projects.Create)
        _ <- adminDsl.createProject(orgId, projId, kgDsl.projectJson(name = fullId), Tweety)
        _ <- adminDsl.createProject(orgId, projId2, kgDsl.projectJson(name = fullId2), Tweety)
      } yield succeed
    }

    "create test schemas" taggedAs ArchivesTag in {
      for {
        _ <- deltaClient.put[Json](s"/schemas/$fullId/test-schema", schemaPayload, Tweety) { (_, response) =>
               response.status shouldEqual StatusCodes.Created
             }
        _ <- deltaClient.put[Json](s"/schemas/$fullId2/test-schema", schemaPayload, Tweety) { (_, response) =>
               response.status shouldEqual StatusCodes.Created
             }
      } yield succeed
    }

    "create resources" taggedAs ArchivesTag in {
      for {
        _ <- deltaClient.putAttachmentFromPath[Json](
               s"/files/$fullId/test-resource:logo",
               Paths.get(getClass.getResource("/kg/files/nexus-logo.png").toURI),
               MediaTypes.`image/png`,
               "nexus-logo.png",
               Tweety
             ) { (_, response) =>
               response.status shouldEqual StatusCodes.Created
             }
        _ <-
          deltaClient.put[Json](s"/resources/$fullId/test-schema/test-resource:1", payload1, Tweety) { (_, response) =>
            response.status shouldEqual StatusCodes.Created
          }
        _ <-
          deltaClient.put[Json](s"/resources/$fullId2/test-schema/test-resource:2", payload2, Tweety) { (_, response) =>
            response.status shouldEqual StatusCodes.Created
          }
      } yield succeed
    }
  }

  "creating archives" should {
    "succeed" taggedAs ArchivesTag in {
      val payload = jsonContentOf("/kg/archives/archive.json", "project2" -> fullId2)

      deltaClient.put[Json](s"/archives/$fullId/test-resource:archive", payload, Tweety) { (_, response) =>
        response.status shouldEqual StatusCodes.Created
      }
    }

    "succeed and redirect" taggedAs ArchivesTag in {
      val payload = jsonContentOf("/kg/archives/archive.json", "project2" -> fullId2)

      deltaClient.put[String](
        s"/archives/$fullId/test-resource:archiveRedirect",
        payload,
        Tweety,
        extraHeaders = List(Accept(MediaRanges.`*/*`))
      )({ (string, response) =>
        string should startWith("The response to the request can be found under")
        response.status shouldEqual StatusCodes.SeeOther
        response
          .header[Location]
          .value
          .uri
          .toString() shouldEqual s"${config.deltaUri}/archives/$fullId/https:%2F%2Fdev.nexus.test.com%2Fsimplified-resource%2FarchiveRedirect"
      })(PredefinedFromEntityUnmarshallers.stringUnmarshaller)
    }

    "fail if payload is wrong" taggedAs ArchivesTag in {
      val payload = jsonContentOf("/kg/archives/archive-wrong.json")

      deltaClient.put[Json](s"/archives/$fullId/archive2", payload, Tweety) { (json, response) =>
        response.status shouldEqual StatusCodes.BadRequest
        filterKey("report")(json) shouldEqual jsonContentOf("/kg/archives/archive-wrong-response.json")
      }
    }

    "fail on wrong path" taggedAs ArchivesTag in {
      val wrong1    = jsonContentOf(s"/kg/archives/archive-wrong-path1.json")
      val expected1 = jsonContentOf("/kg/archives/archive-path-invalid1.json")

      deltaClient.put[Json](s"/archives/$fullId/archive2", wrong1, Tweety) { (json, response) =>
        json shouldEqual expected1
        response.status shouldEqual StatusCodes.BadRequest
      }

      val wrong2    = jsonContentOf(s"/kg/archives/archive-wrong-path2.json")
      val expected2 = jsonContentOf("/kg/archives/archive-path-invalid2.json")

      deltaClient.put[Json](s"/archives/$fullId/archive2", wrong2, Tweety) { (json, response) =>
        json shouldEqual expected2
        response.status shouldEqual StatusCodes.BadRequest
      }
    }

    "fail on path collisions" taggedAs ArchivesTag in {
      val wrong    = jsonContentOf(s"/kg/archives/archive-path-collision.json")
      val expected = jsonContentOf(s"/kg/archives/archive-path-dup.json")

      deltaClient.put[Json](s"/archives/$fullId/archive2", wrong, Tweety) { (json, response) =>
        json shouldEqual expected
        response.status shouldEqual StatusCodes.BadRequest
      }
    }
  }

  "fetching archive" should {
    "succeed returning metadata" taggedAs ArchivesTag in {
      deltaClient.get[Json](s"/archives/$fullId/test-resource:archive", Tweety) { (json, response) =>
        response.status shouldEqual StatusCodes.OK
        val expected = jsonContentOf(
          "/kg/archives/archive-response.json",
          replacements(
            Tweety,
            "project2" -> fullId2,
            "project1" -> fullId
          ): _*
        )
        filterKeys(
          Set("_createdAt", "_updatedAt", "_expiresInSeconds")
        )(json) should equalIgnoreArrayOrder(expected)
      }
    }

    "succeed returning binary" taggedAs ArchivesTag in {
      val prefix = "https%3A%2F%2Fdev.nexus.test.com%2Fsimplified-resource%2F"
      deltaClient.get[ByteString](s"/archives/$fullId/test-resource:archive", Tweety, acceptAll) {
        (byteString, response) =>
          contentType(response) shouldEqual MediaTypes.`application/x-tar`.toContentType
          response.status shouldEqual StatusCodes.OK

          val bytes  = new ByteArrayInputStream(byteString.toArray)
          val tar    = new TarArchiveInputStream(bytes)
          val unpack = readEntries(tar).map { case (path, content) => (path.toString, content) }.toMap

          val actualContent1 = parse(unpack.get(s"$fullId/${prefix}1%3Frev%3D1.json").value.utf8String).rightValue
          val actualContent2 = parse(unpack.get(s"$fullId2/${prefix}2.json").value.utf8String).rightValue
          val actualDigest3  = digest(unpack.get("/some/other/nexus-logo.png").value)

          filterMetadataKeys(actualContent1) should equalIgnoreArrayOrder(payloadResponse1)
          filterMetadataKeys(actualContent2) should equalIgnoreArrayOrder(payloadResponse2)
          actualDigest3 shouldEqual nexusLogoDigest
      }
    }

    "delete resources/read permissions for user on project 2" taggedAs ArchivesTag in
      aclDsl.deletePermission(
        s"/$fullId2",
        Tweety,
        1L,
        Resources.Read
      )

    "fail when a resource in the archive cannot be fetched due to missing permissions" taggedAs ArchivesTag in {
      deltaClient.get[Json](s"/archives/$fullId/test-resource:archive", Tweety, acceptAll) { (json, response) =>
        json shouldEqual jsonContentOf("/kg/archives/authorization-failed.json", "project" -> fullId2)
        response.status shouldEqual StatusCodes.Forbidden
      }
    }

    "succeed using query param ignoreNotFound" taggedAs ArchivesTag in {
      val payload = jsonContentOf("/kg/archives/archive-not-found.json")

      for {
        _ <- deltaClient.put[ByteString](s"/archives/$fullId/test-resource:archive-not-found", payload, Tweety) {
               (_, response) =>
                 response.status shouldEqual StatusCodes.Created
             }
        _ <- deltaClient.get[ByteString](
               s"/archives/$fullId/test-resource:archive-not-found?ignoreNotFound=true",
               Tweety,
               acceptAll
             ) { (byteString, response) =>
               contentType(response) shouldEqual MediaTypes.`application/x-tar`.toContentType
               response.status shouldEqual StatusCodes.OK
               val bytes          = new ByteArrayInputStream(byteString.toArray)
               val tar            = new TarArchiveInputStream(bytes)
               val unpack         = readEntries(tar).map { case (path, content) => (path.toString, content) }.toMap
               val actualContent1 = parse(
                 unpack
                   .get(s"$fullId/https%3A%2F%2Fdev.nexus.test.com%2Fsimplified-resource%2F1%3Frev%3D1.json")
                   .value
                   .utf8String
               ).rightValue
               filterMetadataKeys(actualContent1) should equalIgnoreArrayOrder(payloadResponse1)
             }
      } yield succeed
    }
  }
}
