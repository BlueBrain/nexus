package ch.epfl.bluebrain.nexus.tests.kg

import akka.http.scaladsl.model.headers.{ContentDispositionTypes, HttpEncodings}
import akka.http.scaladsl.model.{ContentType, ContentTypes, HttpResponse, StatusCodes}
import akka.util.ByteString
import ch.epfl.bluebrain.nexus.testkit.CirceEq
import ch.epfl.bluebrain.nexus.tests.BaseSpec
import ch.epfl.bluebrain.nexus.tests.HttpClient._
import ch.epfl.bluebrain.nexus.tests.Identity.storages.Coyote
import ch.epfl.bluebrain.nexus.tests.Optics._
import ch.epfl.bluebrain.nexus.tests.config.ConfigLoader._
import ch.epfl.bluebrain.nexus.tests.config.StorageConfig
import ch.epfl.bluebrain.nexus.tests.iam.types.Permission
import ch.epfl.bluebrain.nexus.tests.iam.types.Permission.Organizations
import com.typesafe.config.ConfigFactory
import io.circe.Json
import monix.bio.Task
import monix.execution.Scheduler.Implicits.global
import org.apache.commons.codec.Charsets
import org.scalatest.Assertion

import java.util.Base64
import scala.collection.immutable.Seq

abstract class StorageSpec extends BaseSpec with CirceEq {

  val storageConfig: StorageConfig = load[StorageConfig](ConfigFactory.load(), "storage")

  val nxv = "https://bluebrain.github.io/nexus/vocabulary/"

  private[tests] val orgId  = genId()
  private[tests] val projId = genId()
  private[tests] val fullId = s"$orgId/$projId"

  def storageName: String

  def storageType: String

  def storageId: String

  def locationPrefix: Option[String]

  def createStorages: Task[Assertion]

  "creating projects" should {

    "add necessary ACLs for user" in {
      aclDsl.addPermission(
        "/",
        Coyote,
        Organizations.Create
      )
    }

    "succeed if payload is correct" in {
      for {
        _ <- adminDsl.createOrganization(orgId, orgId, Coyote)
        _ <- adminDsl.createProject(orgId, projId, kgDsl.projectJson(name = fullId), Coyote)
      } yield succeed
    }

  }

  "creating a storage" should {
    s"succeed creating a $storageName storage" in {
      createStorages
    }

    "wait for storages to be indexed" in {
      eventually {
        deltaClient.get[Json](s"/storages/$fullId", Coyote) { (json, response) =>
          response.status shouldEqual StatusCodes.OK
          _total.getOption(json).value shouldEqual 3
        }
      }
    }
  }

  s"uploading an attachment against the $storageName storage" should {

    "upload attachment with JSON" in {
      deltaClient.putAttachment[Json](
        s"/files/$fullId/attachment.json?storage=nxv:$storageId",
        contentOf("/kg/files/attachment.json"),
        ContentTypes.`application/json`,
        "attachment.json",
        Coyote
      ) { (_, response) =>
        response.status shouldEqual StatusCodes.Created
      }
    }

    "fetch attachment" in {
      deltaClient.get[ByteString](s"/files/$fullId/attachment:attachment.json", Coyote, acceptAll) {
        (content, response) =>
          assertFetchAttachment(
            response,
            "attachment.json",
            ContentTypes.`application/json`
          )
          content.utf8String shouldEqual contentOf("/kg/files/attachment.json")
      }
    }

    "fetch gzipped attachment" in {
      deltaClient.get[ByteString](s"/files/$fullId/attachment:attachment.json", Coyote, gzipHeaders) {
        (content, response) =>
          assertFetchAttachment(
            response,
            "attachment.json",
            ContentTypes.`application/json`
          )
          httpEncodings(response) shouldEqual Seq(HttpEncodings.gzip)
          decodeGzip(content) shouldEqual contentOf("/kg/files/attachment.json")
      }
    }

    "update attachment with JSON" in {
      deltaClient.putAttachment[Json](
        s"/files/$fullId/attachment.json?storage=nxv:$storageId&rev=1",
        contentOf("/kg/files/attachment2.json"),
        ContentTypes.`application/json`,
        "attachment.json",
        Coyote
      ) { (_, response) =>
        response.status shouldEqual StatusCodes.OK
      }
    }

    "fetch updated attachment" in {
      deltaClient.get[ByteString](s"/files/$fullId/attachment:attachment.json", Coyote, acceptAll) {
        (content, response) =>
          assertFetchAttachment(
            response,
            "attachment.json",
            ContentTypes.`application/json`
          )
          content.utf8String shouldEqual contentOf("/kg/files/attachment2.json")
      }
    }

    "fetch previous revision of attachment" in {
      deltaClient.get[ByteString](s"/files/$fullId/attachment:attachment.json?rev=1", Coyote, acceptAll) {
        (content, response) =>
          assertFetchAttachment(
            response,
            "attachment.json",
            ContentTypes.`application/json`
          )
          content.utf8String shouldEqual contentOf("/kg/files/attachment.json")
      }
    }

    "upload second attachment to created storage" in {
      deltaClient.putAttachment[Json](
        s"/files/$fullId/attachment2?storage=nxv:$storageId",
        contentOf("/kg/files/attachment2"),
        ContentTypes.NoContentType,
        "attachment2",
        Coyote
      ) { (_, response) =>
        response.status shouldEqual StatusCodes.Created
      }
    }

    "fetch second attachment" in {
      deltaClient.get[ByteString](s"/files/$fullId/attachment:attachment2", Coyote, acceptAll) { (content, response) =>
        assertFetchAttachment(
          response,
          "attachment2"
        )
        content.utf8String shouldEqual contentOf("/kg/files/attachment2")
      }
    }

    "delete the attachment" in {
      deltaClient.delete[Json](s"/files/$fullId/attachment:attachment.json?rev=2", Coyote) { (_, response) =>
        response.status shouldEqual StatusCodes.OK
      }
    }

    "fetch attachment metadata" in {
      val expected = jsonContentOf(
        "/kg/files/attachment-metadata.json",
        replacements(
          Coyote,
          "id"          -> s"${config.deltaUri}/resources/$fullId/_/attachment.json",
          "filename"    -> s"attachment.json",
          "storageId"   -> storageId,
          "storageType" -> storageType,
          "projId"      -> s"$fullId",
          "project"     -> s"${config.deltaUri}/projects/$fullId"
        ): _*
      )

      deltaClient.get[Json](s"/files/$fullId/attachment:attachment.json", Coyote) { (json, response) =>
        response.status shouldEqual StatusCodes.OK
        locationPrefix.foreach { l =>
          location.getOption(json).value should startWith(l)
        }
        filterMetadataKeys.andThen(filterKey("_location"))(json) shouldEqual expected
      }
    }

    "attempt to upload a third attachment against an storage that does not exists" in {
      deltaClient.putAttachment[Json](
        s"/files/$fullId/attachment3?storage=nxv:wrong-id",
        contentOf("/kg/files/attachment2"),
        ContentTypes.NoContentType,
        "attachment2",
        Coyote
      ) { (_, response) =>
        response.status shouldEqual StatusCodes.NotFound
      }
    }

    "fail to upload file against a storage with custom permissions" in {
      deltaClient.putAttachment[Json](
        s"/files/$fullId/attachment3?storage=nxv:${storageId}2",
        contentOf("/kg/files/attachment2"),
        ContentTypes.NoContentType,
        "attachment2",
        Coyote
      ) { (_, response) =>
        response.status shouldEqual StatusCodes.Forbidden
      }
    }

    "add ACLs for custom storage" in {
      aclDsl.addPermissions(
        "/",
        Coyote,
        Set(Permission(storageName, "read"), Permission(storageName, "write"))
      )
    }

    "upload file against a storage with custom permissions" in {
      deltaClient.putAttachment[Json](
        s"/files/$fullId/attachment3?storage=nxv:${storageId}2",
        contentOf("/kg/files/attachment2"),
        ContentTypes.NoContentType,
        "attachment2",
        Coyote
      ) { (_, response) =>
        response.status shouldEqual StatusCodes.Created
      }
    }
  }

  "deprecating a storage" should {

    "deprecate a storage" in {
      deltaClient.delete[Json](s"/storages/$fullId/nxv:$storageId?rev=1", Coyote) { (_, response) =>
        response.status shouldEqual StatusCodes.OK
      }
    }

    "reject uploading a new file against the deprecated storage" in {
      deltaClient.putAttachment[Json](
        s"/files/$fullId/${genString()}?storage=nxv:$storageId",
        "",
        ContentTypes.NoContentType,
        "attachment3",
        Coyote
      ) { (_, response) =>
        response.status shouldEqual StatusCodes.BadRequest
      }
    }

    "fetch second attachment metadata" in {
      val expected = jsonContentOf(
        "/kg/files/attachment2-metadata.json",
        replacements(
          Coyote,
          "storageId"   -> storageId,
          "storageType" -> storageType,
          "projId"      -> s"$fullId",
          "project"     -> s"${config.deltaUri}/projects/$fullId",
          "storageType" -> storageType
        ): _*
      )

      deltaClient.get[Json](s"/files/$fullId/attachment:attachment2", Coyote) { (json, response) =>
        response.status shouldEqual StatusCodes.OK
        filterMetadataKeys.andThen(filterKey("_location"))(json) shouldEqual expected
      }
    }
  }

  "getting statistics" should {
    "return the correct statistics" in eventually {
      deltaClient.get[Json](s"/storages/$fullId/nxv:$storageId/statistics", Coyote) { (json, response) =>
        response.status shouldEqual StatusCodes.OK
        filterKey("lastProcessedEventDateTime")(json) shouldEqual jsonContentOf("/kg/storages/statistics.json")
      }
    }

    "fail for an unknown storage" in eventually {
      deltaClient.get[Json](s"/storages/$fullId/nxv:fail/statistics", Coyote) { (json, response) =>
        response.status shouldEqual StatusCodes.NotFound
        json shouldEqual jsonContentOf(
          "/kg/storages/not-found.json",
          "storageId" -> (nxv + "fail"),
          "projId"    -> s"$fullId"
        )
      }
    }
  }

  private def attachmentString(filename: String): String = {
    val encodedFilename = new String(Base64.getEncoder.encode(filename.getBytes(Charsets.UTF_8)))
    s"=?UTF-8?B?$encodedFilename?="
  }

  private def assertFetchAttachment(
      response: HttpResponse,
      expectedFilename: String,
      expectedContentType: ContentType
  ): Assertion = {
    assertFetchAttachment(response, expectedFilename)
    contentType(response) shouldEqual expectedContentType
  }

  private def assertFetchAttachment(response: HttpResponse, expectedFilename: String): Assertion = {
    response.status shouldEqual StatusCodes.OK
    dispositionType(response) shouldEqual ContentDispositionTypes.attachment
    attachmentName(response) shouldEqual attachmentString(expectedFilename)
  }

}
