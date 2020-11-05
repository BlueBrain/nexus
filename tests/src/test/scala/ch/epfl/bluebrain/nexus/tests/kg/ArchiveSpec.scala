package ch.epfl.bluebrain.nexus.tests.kg

import java.io.ByteArrayInputStream
import java.nio.file.{Path, Paths}
import java.security.MessageDigest

import akka.http.scaladsl.model.{MediaTypes, StatusCodes}
import akka.util.ByteString
import cats.implicits._
import ch.epfl.bluebrain.nexus.testkit.CirceEq
import ch.epfl.bluebrain.nexus.tests.HttpClient._
import ch.epfl.bluebrain.nexus.tests.Identity.UserCredentials
import ch.epfl.bluebrain.nexus.tests.Optics._
import ch.epfl.bluebrain.nexus.tests.Tags.ArchivesTag
import ch.epfl.bluebrain.nexus.tests.iam.types.Permission.{Projects, Resources}
import ch.epfl.bluebrain.nexus.tests.{BaseSpec, Identity, Realm}
import io.circe.{Json, Printer}
import monix.execution.Scheduler.Implicits.global
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream

import scala.annotation.tailrec

class ArchiveSpec extends BaseSpec with CirceEq {

  private val testRealm  = Realm("resources" + genString())
  private val testClient = Identity.ClientCredentials(genString(), genString(), testRealm)
  private val Tweety     = UserCredentials(genString(), genString(), testRealm)

  private val orgId          = genId()
  private val projId         = genId()
  private val projId2        = genId()
  private val fullId         = s"$orgId/$projId"
  private val fullId2        = s"$orgId/$projId2"
  private val defaultPrinter = Printer.spaces2.copy(dropNullValues = true)

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

  private val nexusLogoDigest =
    "edd70eff895cde1e36eaedd22ed8e9c870bb04155d05d275f970f4f255488e993a32a7c914ee195f6893d43b8be4e0b00db0a6d545a8462491eae788f664ea6b"
  private val payload1Digest  = digest(ByteString(payload1.printWith(defaultPrinter)))
  private val payload2Digest  = digest(ByteString(payload2.printWith(defaultPrinter)))

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

    "create resources" taggedAs ArchivesTag in {
      eventually {
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
          _ <- deltaClient.put[Json](s"/resources/$fullId/_/test-resource:1", payload1, Tweety) { (_, response) =>
                 response.status shouldEqual StatusCodes.Created
               }
          _ <- deltaClient.put[Json](s"/resources/$fullId2/_/test-resource:2", payload2, Tweety) { (_, response) =>
                 response.status shouldEqual StatusCodes.Created
               }
        } yield succeed
      }
    }
  }

  "creating archives" should {
    "succeed" taggedAs ArchivesTag in {
      val payload = jsonContentOf("/kg/archives/archive3.json", "project2" -> fullId2)

      deltaClient.put[Json](s"/archives/$fullId/test-resource:archive", payload, Tweety) { (_, response) =>
        response.status shouldEqual StatusCodes.Created
      }
    }

    "failed if payload is wrong" taggedAs ArchivesTag in {
      val payload = jsonContentOf("/kg/archives/archive-wrong.json")

      deltaClient.put[Json](s"/archives/$fullId/archive2", payload, Tweety) { (json, response) =>
        response.status shouldEqual StatusCodes.BadRequest
        filterKey("report")(json) shouldEqual jsonContentOf("/kg/archives/archive-wrong-response.json")
      }
    }

    "failed on wrong path" taggedAs ArchivesTag in {
      val wrong    = List.tabulate(2)(i => jsonContentOf(s"/kg/archives/archive-wrong-path${i + 1}.json"))
      val expected = jsonContentOf("/kg/archives/archive-path-invalid.json")

      wrong.traverse { payload =>
        deltaClient.put[Json](s"/archives/$fullId/archive2", payload, Tweety) { (json, response) =>
          json shouldEqual expected
          response.status shouldEqual StatusCodes.BadRequest
        }
      }
    }

    "failed on path collisions" taggedAs ArchivesTag in {
      val collisions = List.tabulate(2)(i => jsonContentOf(s"/kg/archives/archive-path-collision${i + 1}.json"))
      val expected   = jsonContentOf(
        "/kg/archives/archive-path-dup.json",
        "project" -> fullId,
        "kg"      -> config.deltaUri.toString()
      )

      collisions.traverse { payload =>
        deltaClient.put[Json](s"/archives/$fullId/archive2", payload, Tweety) { (json, response) =>
          json shouldEqual expected
          response.status shouldEqual StatusCodes.BadRequest
        }
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
      val prefix = "https:%2F%2Fdev.nexus.test.com%2Fsimplified-resource%2F"
      deltaClient.get[ByteString](s"/archives/$fullId/test-resource:archive", Tweety, acceptAll) {
        (byteString, response) =>
          contentType(response) shouldEqual MediaTypes.`application/x-tar`.toContentType
          response.status shouldEqual StatusCodes.OK

          val bytes  = new ByteArrayInputStream(byteString.toArray)
          val tar    = new TarArchiveInputStream(bytes)
          val unpack = readEntries(tar).map { case (path, content) => path.toString -> digest(content) }.toMap
          unpack(s"$fullId/${prefix}1.json") shouldEqual payload1Digest
          unpack(s"$fullId2/${prefix}2.json") shouldEqual payload2Digest
          unpack("some/other/nexus-logo.png") shouldEqual nexusLogoDigest
      }
    }

    "delete resources/read permissions for user on project 2" taggedAs ArchivesTag in
      aclDsl.deletePermission(
        s"/$fullId2",
        Tweety,
        1L,
        Resources.Read
      )

    "failed when a resource in the archive cannot be fetched" taggedAs ArchivesTag in {
      deltaClient.get[Json](s"/archives/$fullId/test-resource:archive", Tweety, acceptAll) { (json, response) =>
        json shouldEqual jsonContentOf("/kg/archives/archive-element-not-found.json")
        response.status shouldEqual StatusCodes.NotFound
      }
    }

    "succeed returning metadata using query param ignoreNotFound" taggedAs ArchivesTag in {
      deltaClient.get[ByteString](s"/archives/$fullId/test-resource:archive?ignoreNotFound=true", Tweety, acceptAll) {
        (_, response) =>
          contentType(response) shouldEqual MediaTypes.`application/x-tar`.toContentType
          response.status shouldEqual StatusCodes.OK
      }
    }
  }
}
