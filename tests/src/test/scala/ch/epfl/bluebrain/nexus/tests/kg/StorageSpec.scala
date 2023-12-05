package ch.epfl.bluebrain.nexus.tests.kg

import akka.http.scaladsl.model._
import akka.http.scaladsl.model.headers.{ContentDispositionTypes, HttpEncodings}
import akka.util.ByteString
import cats.effect.IO
import ch.epfl.bluebrain.nexus.tests.BaseIntegrationSpec
import ch.epfl.bluebrain.nexus.tests.HttpClient._
import ch.epfl.bluebrain.nexus.tests.Identity.storages.Coyote
import ch.epfl.bluebrain.nexus.tests.Optics._
import ch.epfl.bluebrain.nexus.tests.config.ConfigLoader._
import ch.epfl.bluebrain.nexus.tests.config.StorageConfig
import ch.epfl.bluebrain.nexus.tests.iam.types.Permission
import com.typesafe.config.ConfigFactory
import io.circe.Json
import io.circe.optics.JsonPath.root
import org.apache.commons.codec.Charsets
import org.scalatest.Assertion

import java.util.Base64

abstract class StorageSpec extends BaseIntegrationSpec {

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

  def createStorages: IO[Assertion]

  protected def fileSelf(project: String, id: String): String = {
    val uri = Uri(s"${config.deltaUri}/files/$project")
    uri.copy(path = uri.path / id).toString
  }

  private[tests] val fileSelfPrefix = fileSelf(projectRef, attachmentPrefix)

  override def beforeAll(): Unit = {
    super.beforeAll()
    createProjects(Coyote, orgId, projId).accepted
  }

  "Creating a storage" should {
    s"succeed for a $storageName storage" in {
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

  "An empty file" should {

    val emptyFileContent = ""

    "be successfully uploaded" in {
      deltaClient.uploadFile[Json](
        s"/files/$projectRef/empty?storage=nxv:$storageId",
        emptyFileContent,
        ContentTypes.`text/plain(UTF-8)`,
        "empty",
        Coyote
      ) { expectCreated }
    }

    "be downloaded" in {
      deltaClient.get[ByteString](s"/files/$projectRef/attachment:empty", Coyote, acceptAll) {
        expectDownload("empty", ContentTypes.`text/plain(UTF-8)`, emptyFileContent)
      }
    }
  }

  "A json file" should {

    val jsonFileContent        = """{ "initial": ["is", "a", "test", "file"] }"""
    val updatedJsonFileContent = """{ "updated": ["is", "a", "test", "file"] }"""

    "be uploaded" in {
      deltaClient.uploadFile[Json](
        s"/files/$projectRef/attachment.json?storage=nxv:$storageId",
        jsonFileContent,
        ContentTypes.NoContentType,
        "attachment.json",
        Coyote
      ) {
        expectCreated
      }
    }

    "be downloaded" in {
      deltaClient.get[ByteString](s"/files/$projectRef/attachment:attachment.json", Coyote, acceptAll) {
        expectDownload("attachment.json", ContentTypes.`application/json`, jsonFileContent)
      }
    }

    "be downloaded as gzip" in {
      deltaClient.get[ByteString](s"/files/$projectRef/attachment:attachment.json", Coyote, gzipHeaders) {
        expectDownload("attachment.json", ContentTypes.`application/json`, jsonFileContent, compressed = true)
      }
    }

    "be updated" in {
      deltaClient.uploadFile[Json](
        s"/files/$projectRef/attachment.json?storage=nxv:$storageId&rev=1",
        updatedJsonFileContent,
        ContentTypes.`application/json`,
        "attachment.json",
        Coyote
      ) {
        expectOk
      }
    }

    "download the updated file" in {
      deltaClient.get[ByteString](s"/files/$projectRef/attachment:attachment.json", Coyote, acceptAll) {
        expectDownload(
          "attachment.json",
          ContentTypes.`application/json`,
          updatedJsonFileContent
        )
      }
    }

    "download the previous revision" in {
      deltaClient.get[ByteString](s"/files/$projectRef/attachment:attachment.json?rev=1", Coyote, acceptAll) {
        expectDownload(
          "attachment.json",
          ContentTypes.`application/json`,
          jsonFileContent
        )
      }
    }

    "deprecate the file" in {
      deltaClient.delete[Json](s"/files/$projectRef/attachment:attachment.json?rev=2", Coyote) {
        expectOk
      }
    }

    "have the expected metadata" in {
      val id       = s"${attachmentPrefix}attachment.json"
      val expected = jsonContentOf(
        "kg/files/attachment-metadata.json",
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
  }

  "A file without extension" should {

    val textFileContent = "text file"

    "be uploaded" in {
      deltaClient.uploadFile[Json](
        s"/files/$projectRef/attachment2?storage=nxv:$storageId",
        textFileContent,
        ContentTypes.NoContentType,
        "attachment2",
        Coyote
      ) {
        expectCreated
      }
    }

    "be downloaded" in {
      deltaClient.get[ByteString](s"/files/$projectRef/attachment:attachment2", Coyote, acceptAll) {
        expectDownload("attachment2", ContentTypes.`application/octet-stream`, textFileContent)
      }
    }
  }

  "Uploading a file against a unknown storage" should {

    val textFileContent = "text file"

    "fail" in {
      deltaClient.uploadFile[Json](
        s"/files/$projectRef/attachment3?storage=nxv:wrong-id",
        textFileContent,
        ContentTypes.NoContentType,
        "attachment2",
        Coyote
      ) { expectNotFound }
    }
  }

  "Uploading a file against a storage with custom permissions" should {

    val textFileContent = "text file"

    def uploadStorageWithCustomPermissions: ((Json, HttpResponse) => Assertion) => IO[Assertion] =
      deltaClient.uploadFile[Json](
        s"/files/$projectRef/attachment3?storage=nxv:${storageId}2",
        textFileContent,
        ContentTypes.NoContentType,
        "attachment2",
        Coyote
      )

    "fail without these custom permissions" in {
      uploadStorageWithCustomPermissions { expectForbidden }
    }

    "succeed with the appropriate permissions" in {
      val permissions = Set(Permission(storageName, "read"), Permission(storageName, "write"))
      for {
        _ <- aclDsl.addPermissions("/", Coyote, permissions)
        _ <- uploadStorageWithCustomPermissions { expectCreated }
      } yield (succeed)
    }
  }

  "Getting statistics" should {
    "return the correct statistics" in eventually {
      deltaClient.get[Json](s"/storages/$projectRef/nxv:$storageId/statistics", Coyote) { (json, response) =>
        response.status shouldEqual StatusCodes.OK
        val expected =
          json"""{ "@context": "https://bluebrain.github.io/nexus/contexts/storages.json", "files": 4, "spaceUsed": 93 }"""
        filterKey("lastProcessedEventDateTime")(json) shouldEqual expected
      }
    }

    "fail for an unknown storage" in eventually {
      deltaClient.get[Json](s"/storages/$projectRef/nxv:fail/statistics", Coyote) { (json, response) =>
        response.status shouldEqual StatusCodes.NotFound
        json shouldEqual jsonContentOf(
          "kg/storages/not-found.json",
          "storageId" -> (nxv + "fail"),
          "projId"    -> s"$projectRef"
        )
      }
    }
  }

  "List files" in eventually {
    deltaClient.get[Json](s"/files/$projectRef", Coyote) { (json, response) =>
      response.status shouldEqual StatusCodes.OK
      val mapping  = replacements(
        Coyote,
        "project"        -> projectRef,
        "fileSelfPrefix" -> fileSelfPrefix,
        "storageId"      -> storageId,
        "storageType"    -> storageType
      )
      val expected = jsonContentOf("kg/files/list.json", mapping: _*)
      filterSearchMetadata
        .andThen(filterResults(Set("_location")))(json) should equalIgnoreArrayOrder(expected)
    }
  }

  "Query the default sparql view for files" in eventually {
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
      val expected = jsonContentOf("kg/files/sparql.json", mapping: _*)
      json should equalIgnoreArrayOrder(expected)
    }
  }

  "Upload files with the .custom extension" should {
    val fileContent = "file content"

    def uploadCustomFile(id: String, contentType: ContentType): ((Json, HttpResponse) => Assertion) => IO[Assertion] =
      deltaClient.uploadFile[Json](
        s"/files/$projectRef/$id?storage=nxv:$storageId",
        fileContent,
        contentType,
        "file.custom",
        Coyote
      )

    def assertContentType(id: String, expectedContentType: String) =
      deltaClient.get[Json](s"/files/$projectRef/attachment:$id", Coyote) { (json, response) =>
        response.status shouldEqual StatusCodes.OK
        root._mediaType.string.getOption(json) shouldEqual Some(expectedContentType)
      }

    "autodetect the correct content type when no header is passed" in {
      val id = "file.custom"
      for {
        _ <- uploadCustomFile(id, ContentTypes.NoContentType) { expectCreated }
        _ <- assertContentType(id, "application/custom")
      } yield succeed
    }

    "assign the content-type header provided by the user" in {
      val id = "file2.custom"
      for {
        _ <- uploadCustomFile(id, ContentTypes.`application/json`) { expectCreated }
        _ <- assertContentType(id, "application/json")
      } yield succeed
    }
  }

  "Deprecating a storage" should {

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
      ) {
        expectBadRequest
      }
    }

    "fetch metadata" in {
      val id       = s"${attachmentPrefix}attachment2"
      val expected = jsonContentOf(
        "kg/files/attachment2-metadata.json",
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

  "Undeprecating a storage" should {

    "allow uploading a file again" in {
      val undeprecateStorage = deltaClient
        .put[Json](s"/storages/$projectRef/nxv:$storageId/undeprecate?rev=2", Json.obj(), Coyote) { expectOk }
      val uploadFile         = deltaClient.uploadFile[Json](
        s"/files/$projectRef/${genString()}?storage=nxv:$storageId",
        "",
        ContentTypes.NoContentType,
        "attachment3",
        Coyote
      ) {
        expectCreated
      }
      undeprecateStorage >> uploadFile
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
        decodeGzip(content) shouldEqual expectedContent
      } else
        content.utf8String shouldEqual expectedContent
    }
}
