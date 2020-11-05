package ch.epfl.bluebrain.nexus.tests.kg

import akka.http.scaladsl.model.headers.{ContentDispositionTypes, HttpEncodings}
import akka.http.scaladsl.model.{ContentType, ContentTypes, HttpResponse, StatusCodes}
import akka.util.ByteString
import ch.epfl.bluebrain.nexus.testkit.CirceEq
import ch.epfl.bluebrain.nexus.tests.HttpClient._
import ch.epfl.bluebrain.nexus.tests.Identity.UserCredentials
import ch.epfl.bluebrain.nexus.tests.Optics._
import ch.epfl.bluebrain.nexus.tests.Tags.StorageTag
import ch.epfl.bluebrain.nexus.tests.config.ConfigLoader._
import ch.epfl.bluebrain.nexus.tests.config.StorageConfig
import ch.epfl.bluebrain.nexus.tests.iam.types.Permission
import ch.epfl.bluebrain.nexus.tests.iam.types.Permission.Organizations
import ch.epfl.bluebrain.nexus.tests.{BaseSpec, Identity, Realm}
import com.google.common.io.BaseEncoding
import com.typesafe.config.ConfigFactory
import io.circe.Json
import monix.bio.Task
import monix.execution.Scheduler.Implicits.global
import org.apache.commons.codec.Charsets
import org.scalatest.Assertion

import scala.collection.immutable.Seq

abstract class StorageSpec extends BaseSpec with CirceEq {

  val storageConfig: StorageConfig = load[StorageConfig](ConfigFactory.load(), "storage")

  private[tests] val orgId  = genId()
  private[tests] val projId = genId()
  private[tests] val fullId = s"$orgId/$projId"

  private[tests] val testRealm  = Realm("storage" + genString())
  private[tests] val testClient = Identity.ClientCredentials(genString(), genString(), testRealm)
  private[tests] val Coyote     = UserCredentials(genString(), genString(), testRealm)

  override def beforeAll(): Unit = {
    super.beforeAll()
    initRealm(
      testRealm,
      Identity.ServiceAccount,
      testClient,
      Coyote :: Nil
    ).runSyncUnsafe()
    ()
  }

  def storageType: String

  def storageName: String

  def locationPrefix: Option[String]

  def createStorages: Task[Assertion]

  "creating projects" should {

    "add necessary ACLs for user" taggedAs StorageTag in {
      aclDsl.addPermission(
        "/",
        Coyote,
        Organizations.Create
      )
    }

    "succeed if payload is correct" taggedAs StorageTag in {
      for {
        _ <- adminDsl.createOrganization(orgId, orgId, Coyote)
        _ <- adminDsl.createProject(orgId, projId, kgDsl.projectJson(name = fullId), Coyote)
      } yield succeed
    }

  }

  "creating a storage" should {
    s"succeed creating a $storageType storage" taggedAs StorageTag in {
      createStorages
    }

    "wait for storages to be indexed" taggedAs StorageTag in {
      eventually {
        deltaClient.get[Json](s"/storages/$fullId", Coyote) { (json, response) =>
          response.status shouldEqual StatusCodes.OK
          _total.getOption(json).value shouldEqual 3
        }
      }
    }
  }

  s"uploading an attachment against the $storageType storage" should {

    "upload attachment with JSON" taggedAs StorageTag in {
      deltaClient.putAttachment[Json](
        s"/files/$fullId/attachment.json?storage=nxv:$storageName",
        contentOf("/kg/files/attachment.json"),
        ContentTypes.`application/json`,
        "attachment.json",
        Coyote
      ) { (_, response) =>
        response.status shouldEqual StatusCodes.Created
      }
    }

    "fetch attachment" taggedAs StorageTag in {
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

    "fetch gzipped attachment" taggedAs StorageTag in {
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

    "update attachment with JSON" taggedAs StorageTag in {
      deltaClient.putAttachment[Json](
        s"/files/$fullId/attachment.json?storage=nxv:$storageName&rev=1",
        contentOf("/kg/files/attachment2.json"),
        ContentTypes.`application/json`,
        "attachment.json",
        Coyote
      ) { (_, response) =>
        response.status shouldEqual StatusCodes.OK
      }
    }

    "fetch updated attachment" taggedAs StorageTag in {
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

    "fetch previous revision of attachment" taggedAs StorageTag in {
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

    "upload second attachment to created storage" taggedAs StorageTag in {
      deltaClient.putAttachment[Json](
        s"/files/$fullId/attachment2?storage=nxv:$storageName",
        contentOf("/kg/files/attachment2"),
        ContentTypes.NoContentType,
        "attachment2",
        Coyote
      ) { (_, response) =>
        response.status shouldEqual StatusCodes.Created
      }
    }

    "fetch second attachment" taggedAs StorageTag in {
      deltaClient.get[ByteString](s"/files/$fullId/attachment:attachment2", Coyote, acceptAll) { (content, response) =>
        assertFetchAttachment(
          response,
          "attachment2"
        )
        content.utf8String shouldEqual contentOf("/kg/files/attachment2")
      }
    }

    "delete the attachment" taggedAs StorageTag in {
      deltaClient.delete[Json](s"/files/$fullId/attachment:attachment.json?rev=2", Coyote) { (_, response) =>
        response.status shouldEqual StatusCodes.OK
      }
    }

    "fetch attachment metadata" taggedAs StorageTag in {
      val expected = jsonContentOf(
        "/kg/files/attachment-metadata.json",
        replacements(
          Coyote,
          "id"        -> s"${config.deltaUri}/resources/$fullId/_/attachment.json",
          "filename"  -> s"attachment.json",
          "storageId" -> s"nxv:$storageName",
          "projId"    -> s"$fullId",
          "project"   -> s"${config.deltaUri}/projects/$fullId"
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

    "attempt to upload a third attachment against an storage that does not exists" taggedAs StorageTag in {
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

    "fail to upload file against a storage with custom permissions" taggedAs StorageTag in {
      deltaClient.putAttachment[Json](
        s"/files/$fullId/attachment3?storage=nxv:${storageName}2",
        contentOf("/kg/files/attachment2"),
        ContentTypes.NoContentType,
        "attachment2",
        Coyote
      ) { (_, response) =>
        response.status shouldEqual StatusCodes.Forbidden
      }
    }

    "add ACLs for custom storage" taggedAs StorageTag in {
      aclDsl.addPermissions(
        "/",
        Coyote,
        Set(Permission(storageType, "read"), Permission(storageType, "write"))
      )
    }

    "upload file against a storage with custom permissions" taggedAs StorageTag in {
      deltaClient.putAttachment[Json](
        s"/files/$fullId/attachment3?storage=nxv:${storageName}2",
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

    "deprecate a DiskStorage" taggedAs StorageTag in {
      deltaClient.delete[Json](s"/storages/$fullId/nxv:$storageName?rev=1", Coyote) { (_, response) =>
        response.status shouldEqual StatusCodes.OK
      }
    }

    "reject uploading a new file against the deprecated storage" taggedAs StorageTag in {
      deltaClient.putAttachment[Json](
        s"/files/$fullId/attachment3?storage=nxv:$storageName",
        "",
        ContentTypes.NoContentType,
        "attachment3",
        Coyote
      ) { (_, response) =>
        response.status shouldEqual StatusCodes.NotFound
      }
    }

    "fetch second attachment metadata" taggedAs StorageTag in {
      val expected = jsonContentOf(
        "/kg/files/attachment2-metadata.json",
        replacements(
          Coyote,
          "storageId" -> s"nxv:$storageName",
          "projId"    -> s"$fullId",
          "project"   -> s"${config.deltaUri}/projects/$fullId"
        ): _*
      )

      deltaClient.get[Json](s"/files/$fullId/attachment:attachment2", Coyote) { (json, response) =>
        response.status shouldEqual StatusCodes.OK
        filterMetadataKeys.andThen(filterKey("_location"))(json) shouldEqual expected
      }
    }
  }

  private def attachmentString(filename: String): String = {
    val encodedFilename = BaseEncoding.base64().encode(filename.getBytes(Charsets.UTF_8))
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
