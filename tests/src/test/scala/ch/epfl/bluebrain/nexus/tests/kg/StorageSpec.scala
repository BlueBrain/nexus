package ch.epfl.bluebrain.nexus.tests.kg

import akka.http.scaladsl.model.headers.{ContentDispositionTypes, HttpEncodings}
import akka.http.scaladsl.model._
import akka.util.ByteString
import ch.epfl.bluebrain.nexus.testkit.CirceEq
import ch.epfl.bluebrain.nexus.tests.BaseSpec
import ch.epfl.bluebrain.nexus.tests.HttpClient._
import ch.epfl.bluebrain.nexus.tests.Identity.storages.Coyote
import ch.epfl.bluebrain.nexus.tests.Optics._
import ch.epfl.bluebrain.nexus.tests.config.ConfigLoader._
import ch.epfl.bluebrain.nexus.tests.config.StorageConfig
import ch.epfl.bluebrain.nexus.tests.iam.types.Permission
import com.typesafe.config.ConfigFactory
import io.circe.Json
import monix.bio.Task
import monix.execution.Scheduler.Implicits.global
import org.apache.commons.codec.Charsets
import org.scalatest.Assertion

import java.util.Base64

abstract class StorageSpec extends BaseSpec with CirceEq {

  val storageConfig: StorageConfig = load[StorageConfig](ConfigFactory.load(), "storage")

  val nxv = "https://bluebrain.github.io/nexus/vocabulary/"

  private[tests] val orgId      = genId()
  private[tests] val projId     = genId()
  private[tests] val projectRef = s"$orgId/$projId"

  private[tests] val attachmentPrefix = s"${config.deltaUri}/resources/$projectRef/_/"

  def storageName: String

  def storageType: String

  def storageId: String

  def locationPrefix: Option[String]

  def createStorages: Task[Assertion]

  protected def fileSelf(project: String, id: String): String = {
    val uri = Uri(s"${config.deltaUri}/files/$project")
    uri.copy(path = uri.path / id).toString
  }

  private[tests] val fileSelfPrefix = fileSelf(projectRef, attachmentPrefix)

  override def beforeAll(): Unit = {
    super.beforeAll()
    createProjects(Coyote, orgId, projId).accepted
  }

  "creating a storage" should {
    s"succeed creating a $storageName storage" in {
      createStorages
    }

    "wait for storages to be indexed" in {
      eventually {
        deltaClient.get[Json](s"/storages/$projectRef", Coyote) { (json, response) =>
          response.status shouldEqual StatusCodes.OK
          _total.getOption(json).value shouldEqual 3
        }
      }
    }
  }

  s"uploading an attachment against the $storageName storage" should {

    "upload empty file" in {
      deltaClient.uploadFile[Json](
        s"/files/$projectRef/empty?storage=nxv:$storageId",
        contentOf("/kg/files/empty"),
        ContentTypes.`text/plain(UTF-8)`,
        "empty",
        Coyote
      ) { expectCreated }
    }

    "fetch empty file" in {
      deltaClient.get[ByteString](s"/files/$projectRef/attachment:empty", Coyote, acceptAll) {
        expectDownload(
          "empty",
          ContentTypes.`text/plain(UTF-8)`,
          contentOf("/kg/files/empty")
        )
      }
    }

    "upload attachment with JSON" in {
      deltaClient.uploadFile[Json](
        s"/files/$projectRef/attachment.json?storage=nxv:$storageId",
        contentOf("/kg/files/attachment.json"),
        ContentTypes.NoContentType,
        "attachment.json",
        Coyote
      ) { expectCreated }
    }

    "fetch attachment" in {
      deltaClient.get[ByteString](s"/files/$projectRef/attachment:attachment.json", Coyote, acceptAll) {
        expectDownload(
          "attachment.json",
          ContentTypes.`application/json`,
          contentOf("/kg/files/attachment.json")
        )
      }
    }

    "fetch gzipped attachment" in {
      deltaClient.get[ByteString](s"/files/$projectRef/attachment:attachment.json", Coyote, gzipHeaders) {
        expectDownload(
          "attachment.json",
          ContentTypes.`application/json`,
          contentOf("/kg/files/attachment.json"),
          compressed = true
        )
      }
    }

    "update attachment with JSON" in {
      deltaClient.uploadFile[Json](
        s"/files/$projectRef/attachment.json?storage=nxv:$storageId&rev=1",
        contentOf("/kg/files/attachment2.json"),
        ContentTypes.`application/json`,
        "attachment.json",
        Coyote
      ) { expectOk }
    }

    "fetch updated attachment" in {
      deltaClient.get[ByteString](s"/files/$projectRef/attachment:attachment.json", Coyote, acceptAll) {
        expectDownload(
          "attachment.json",
          ContentTypes.`application/json`,
          contentOf("/kg/files/attachment2.json")
        )
      }
    }

    "fetch previous revision of attachment" in {
      deltaClient.get[ByteString](s"/files/$projectRef/attachment:attachment.json?rev=1", Coyote, acceptAll) {
        expectDownload(
          "attachment.json",
          ContentTypes.`application/json`,
          contentOf("/kg/files/attachment.json")
        )
      }
    }

    "upload second attachment to created storage" in {
      deltaClient.uploadFile[Json](
        s"/files/$projectRef/attachment2?storage=nxv:$storageId",
        contentOf("/kg/files/attachment2"),
        ContentTypes.NoContentType,
        "attachment2",
        Coyote
      ) { expectCreated }
    }

    "fetch second attachment" in {
      deltaClient.get[ByteString](s"/files/$projectRef/attachment:attachment2", Coyote, acceptAll) {
        expectDownload("attachment2", ContentTypes.`text/plain(UTF-8)`, contentOf("/kg/files/attachment2"))
      }
    }

    "deprecate the attachment" in {
      deltaClient.delete[Json](s"/files/$projectRef/attachment:attachment.json?rev=2", Coyote) { expectOk }
    }

    "fetch attachment metadata" in {
      val id       = s"${attachmentPrefix}attachment.json"
      val expected = jsonContentOf(
        "/kg/files/attachment-metadata.json",
        replacements(
          Coyote,
          "id"          -> id,
          "self"        -> fileSelf(projectRef, id),
          "filename"    -> "attachment.json",
          "storageId"   -> storageId,
          "storageType" -> storageType,
          "projId"      -> s"$projectRef",
          "project"     -> s"${config.deltaUri}/projects/$projectRef"
        ): _*
      )

      deltaClient.get[Json](s"/files/$projectRef/attachment:attachment.json", Coyote) { (json, response) =>
        response.status shouldEqual StatusCodes.OK
        locationPrefix.foreach { l =>
          location.getOption(json).value should startWith(l)
        }
        filterMetadataKeys.andThen(filterKey("_location"))(json) shouldEqual expected
      }
    }

    "attempt to upload a third attachment against an storage that does not exists" in {
      deltaClient.uploadFile[Json](
        s"/files/$projectRef/attachment3?storage=nxv:wrong-id",
        contentOf("/kg/files/attachment2"),
        ContentTypes.NoContentType,
        "attachment2",
        Coyote
      ) { expectNotFound }
    }

    "fail to upload file against a storage with custom permissions" in {
      deltaClient.uploadFile[Json](
        s"/files/$projectRef/attachment3?storage=nxv:${storageId}2",
        contentOf("/kg/files/attachment2"),
        ContentTypes.NoContentType,
        "attachment2",
        Coyote
      ) { expectForbidden }
    }

    "add ACLs for custom storage" in {
      aclDsl.addPermissions(
        "/",
        Coyote,
        Set(Permission(storageName, "read"), Permission(storageName, "write"))
      )
    }

    "upload file against a storage with custom permissions" in {
      deltaClient.uploadFile[Json](
        s"/files/$projectRef/attachment3?storage=nxv:${storageId}2",
        contentOf("/kg/files/attachment2"),
        ContentTypes.NoContentType,
        "attachment2",
        Coyote
      ) { expectCreated }
    }
  }

  "deprecating a storage" should {

    "deprecate a storage" in {
      deltaClient.delete[Json](s"/storages/$projectRef/nxv:$storageId?rev=1", Coyote) { expectOk }
    }

    "reject uploading a new file against the deprecated storage" in {
      deltaClient.uploadFile[Json](
        s"/files/$projectRef/${genString()}?storage=nxv:$storageId",
        "",
        ContentTypes.NoContentType,
        "attachment3",
        Coyote
      ) { expectBadRequest }
    }

    "fetch second attachment metadata" in {
      val id       = s"${attachmentPrefix}attachment2"
      val expected = jsonContentOf(
        "/kg/files/attachment2-metadata.json",
        replacements(
          Coyote,
          "id"          -> id,
          "storageId"   -> storageId,
          "self"        -> fileSelf(projectRef, id),
          "storageType" -> storageType,
          "projId"      -> s"$projectRef",
          "project"     -> s"${config.deltaUri}/projects/$projectRef",
          "storageType" -> storageType
        ): _*
      )

      deltaClient.get[Json](s"/files/$projectRef/attachment:attachment2", Coyote) { (json, response) =>
        response.status shouldEqual StatusCodes.OK
        filterMetadataKeys.andThen(filterKey("_location"))(json) shouldEqual expected
      }
    }
  }

  "getting statistics" should {
    "return the correct statistics" in eventually {
      deltaClient.get[Json](s"/storages/$projectRef/nxv:$storageId/statistics", Coyote) { (json, response) =>
        response.status shouldEqual StatusCodes.OK
        filterKey("lastProcessedEventDateTime")(json) shouldEqual jsonContentOf("/kg/storages/statistics.json")
      }
    }

    "fail for an unknown storage" in eventually {
      deltaClient.get[Json](s"/storages/$projectRef/nxv:fail/statistics", Coyote) { (json, response) =>
        response.status shouldEqual StatusCodes.NotFound
        json shouldEqual jsonContentOf(
          "/kg/storages/not-found.json",
          "storageId" -> (nxv + "fail"),
          "projId"    -> s"$projectRef"
        )
      }
    }
  }

  "list files" in eventually {
    deltaClient.get[Json](s"/files/$projectRef", Coyote) { (json, response) =>
      response.status shouldEqual StatusCodes.OK
      val mapping  = replacements(
        Coyote,
        "project"        -> projectRef,
        "fileSelfPrefix" -> fileSelfPrefix,
        "storageId"      -> storageId,
        "storageType"    -> storageType
      )
      val expected = jsonContentOf("/kg/files/list.json", mapping: _*)
      filterSearchMetadata
        .andThen(filterResults(Set("_location")))(json) should equalIgnoreArrayOrder(expected)
    }
  }

  "query the default sparql view for files" in eventually {
    val id    = s"http://delta:8080/v1/resources/$projectRef/_/attachment.json"
    val query =
      s"""
        |prefix nxv: <https://bluebrain.github.io/nexus/vocabulary/>
        |prefix : <https://bluebrain.github.io/test/>
        |
        |CONSTRUCT {
        |      ?id  a                   ?type        ;
        |           :filename           ?filename    ;
        |           :bytes              ?bytes       ;
        |           :digestValue        ?digestValue    ;
        |           :digestAlgo         ?digestAlgo     ;
        |           :mediaType          ?mediaType      ;
        |           :storageId          ?storageId      ;
        |           :createdBy          ?createdBy      ;
        |           :updatedBy          ?updatedBy      ;
        |           :deprecated         ?deprecated     ;
        |           :rev                ?rev            ;
        |           :project            ?project        ;
        |           :self               ?self           ;
        |           :incoming           ?incoming       ;
        |           :outgoing           ?outgoing       ;
        |} WHERE {
        |      BIND(<$id> as ?id) .
        |
        |      ?id  a  ?type  .
        |      ?id  nxv:filename               ?filename;
        |           nxv:bytes                  ?bytes;
        |           nxv:digest / nxv:value     ?digestValue;
        |           nxv:digest / nxv:algorithm ?digestAlgo;
        |           nxv:mediaType   ?mediaType;
        |           nxv:storage     ?storage;
        |           nxv:createdBy   ?createdBy;
        |           nxv:updatedBy   ?updatedBy;
        |           nxv:deprecated  ?deprecated;
        |           nxv:rev         ?rev;
        |           nxv:rev         ?rev;
        |           nxv:project     ?project;
        |           nxv:self        ?self;
        |           nxv:incoming    ?incoming;
        |           nxv:outgoing    ?outgoing;
        |}
        |
      """.stripMargin

    deltaClient.sparqlQuery[Json](s"/views/$projectRef/graph/sparql", query, Coyote) { (json, response) =>
      response.status shouldEqual StatusCodes.OK
      val mapping  = replacements(
        Coyote,
        "project"   -> projectRef,
        "self"      -> fileSelf(projectRef, id),
        "storageId" -> storageId
      )
      val expected = jsonContentOf("/kg/files/sparql.json", mapping: _*)
      json should equalIgnoreArrayOrder(expected)
    }
  }

  private def attachmentString(filename: String): String = {
    val encodedFilename = new String(Base64.getEncoder.encode(filename.getBytes(Charsets.UTF_8)))
    s"=?UTF-8?B?$encodedFilename?="
  }

  private def expectDownload(
      expectedFilename: String,
      expectedContentType: ContentType,
      expectedContent: String,
      compressed: Boolean = false
  ) =
    (content: ByteString, response: HttpResponse) => {
      response.status shouldEqual StatusCodes.OK
      dispositionType(response) shouldEqual ContentDispositionTypes.attachment
      attachmentName(response) shouldEqual attachmentString(expectedFilename)
      contentType(response) shouldEqual expectedContentType
      if (compressed) {
        httpEncodings(response) shouldEqual Seq(HttpEncodings.gzip)
        decodeGzip(content) shouldEqual contentOf("/kg/files/attachment.json")
      } else
        content.utf8String shouldEqual expectedContent
    }
}
