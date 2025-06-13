package ai.senscience.nexus.tests.kg.files

import ai.senscience.nexus.tests.CacheAssertions.expectConditionalCacheHeaders
import ai.senscience.nexus.tests.HttpClient.*
import ai.senscience.nexus.tests.Identity.storages.Coyote
import ai.senscience.nexus.tests.Optics.*
import ai.senscience.nexus.tests.config.ConfigLoader.*
import ai.senscience.nexus.tests.config.StorageConfig
import ai.senscience.nexus.tests.iam.types.Permission
import ai.senscience.nexus.tests.kg.files.FilesAssertions.expectFileContent
import ai.senscience.nexus.tests.kg.files.model.FileInput
import ai.senscience.nexus.tests.kg.files.model.FileInput.*
import ai.senscience.nexus.tests.{BaseIntegrationSpec, Identity}
import akka.http.scaladsl.model.*
import akka.http.scaladsl.model.headers.{`If-None-Match`, Accept, ETag}
import akka.util.ByteString
import cats.effect.IO
import ch.epfl.bluebrain.nexus.akka.marshalling.RdfMediaTypes
import com.typesafe.config.ConfigFactory
import io.circe.Json
import io.circe.optics.JsonPath.root
import org.scalatest.Assertion

abstract class StorageSpec extends BaseIntegrationSpec {

  implicit val currentUser: Identity.UserCredentials = Coyote

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

  def createStorages(projectRef: String, storId: String, storName: String): IO[Assertion]

  private[tests] val fileSelfPrefix = fileSelf(projectRef, attachmentPrefix)

  private val textPlain = MediaTypes.`text/plain`.withMissingCharset

  override def beforeAll(): Unit = {
    super.beforeAll()
    createProjects(Coyote, orgId, projId).accepted
  }

  "Creating a storage" should {
    s"succeed for a $storageName storage" in {
      createStorages(projectRef, storageId, storageName)
    }
  }

  "An empty file" should {

    "be successfully uploaded" in {
      val emptyFile = emptyTextFile.copy(metadata = None)
      deltaClient.uploadFile(projectRef, storageId, emptyFile, None)(expectCreated)
    }

    "be downloaded" in {
      deltaClient.get[ByteString](s"/files/$projectRef/attachment:empty", Coyote, acceptAll) {
        expectFileContent("empty", textPlain, emptyFileContent, cacheable = true)
      }
    }
  }

  "A json file" should {

    "be uploaded" in {
      deltaClient.uploadFile(projectRef, storageId, jsonFileNoContentType, None)(expectCreated)
    }

    "be downloaded" in {
      deltaClient.get[ByteString](s"/files/$projectRef/attachment:attachment.json", Coyote, acceptAll) {
        expectFileContent("attachment.json", ContentTypes.`application/json`, jsonFileContent, cacheable = true)
      }
    }

    "return not modified when passing a valid etag" in {
      val fileUrl = s"/files/$projectRef/attachment:attachment.json"
      for {
        response   <- deltaClient.getResponse(fileUrl, Coyote, acceptAll)
        etag        = response.header[ETag].value.etag
        ifNoneMatch = `If-None-Match`(etag)
        _          <- deltaClient.get[ByteString](fileUrl, Coyote, acceptAll :+ ifNoneMatch) { (_, response) =>
                        response.status shouldEqual StatusCodes.NotModified
                      }
      } yield succeed
    }

    "be downloaded as gzip" in {
      deltaClient.get[ByteString](s"/files/$projectRef/attachment:attachment.json", Coyote, gzipHeaders) {
        expectFileContent(
          "attachment.json",
          ContentTypes.`application/json`,
          jsonFileContent,
          compressed = true,
          cacheable = true
        )
      }
    }

    "be updated" in {
      val updatedFile = updatedJsonFileWithContentType.copy(metadata = None)
      deltaClient.uploadFile(projectRef, storageId, updatedFile, Some(1))(expectOk)
    }

    "download the updated file" in {
      deltaClient.get[ByteString](s"/files/$projectRef/attachment:attachment.json", Coyote, acceptAll) {
        expectFileContent(
          "attachment.json",
          ContentTypes.`application/json`,
          updatedJsonFileContent,
          cacheable = true
        )
      }
    }

    "download the previous revision" in {
      deltaClient.get[ByteString](s"/files/$projectRef/attachment:attachment.json?rev=1", Coyote, acceptAll) {
        expectFileContent(
          "attachment.json",
          ContentTypes.`application/json`,
          jsonFileContent,
          cacheable = true
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
          "project"     -> projectRef
        )*
      )

      deltaClient.get[Json](s"/files/$projectRef/attachment:attachment.json", Coyote) { (json, response) =>
        response.status shouldEqual StatusCodes.OK
        locationPrefix.foreach { l =>
          location.getOption(json).value should startWith(l)
        }
        filterMetadataKeys.andThen(filterKey("_location"))(json) shouldEqual expected
        expectConditionalCacheHeaders(response)
      }
    }
  }

  "A file without extension" should {

    "be uploaded" in {
      deltaClient.uploadFile(projectRef, storageId, textFileNoContentType.copy(metadata = None), None)(expectCreated)
    }

    "be downloaded" in {
      deltaClient.get[ByteString](s"/files/$projectRef/attachment:attachment2", Coyote, acceptAll) {
        expectFileContent(
          textFileNoContentType.filename,
          ContentTypes.`application/octet-stream`,
          textFileNoContentType.contents,
          cacheable = true
        )
      }
    }
  }

  "Uploading a file against a unknown storage" should {
    "fail" in {
      deltaClient.uploadFile(projectRef, "wrong-id", randomTextFile, None) { expectNotFound }
    }
  }

  "Uploading a file against a storage with custom permissions" should {

    def uploadStorageWithCustomPermissions: ((Json, HttpResponse) => Assertion) => IO[Assertion] = {
      val file = FileInput("attachment3", "attachment2", ContentTypes.NoContentType, "text file")
      deltaClient.uploadFile(projectRef, s"${storageId}2", file, None)
    }

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
      val expected = jsonContentOf("kg/files/list.json", mapping*)
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
        |}
        |
      """.stripMargin

    val acceptJsonLd = Seq(Accept(RdfMediaTypes.`application/ld+json`))
    deltaClient.sparqlQuery[Json](s"/views/$projectRef/graph/sparql", query, Coyote, acceptJsonLd) { (json, response) =>
      response.status shouldEqual StatusCodes.OK
      val mapping  = replacements(
        Coyote,
        "project"   -> projectRef,
        "self"      -> fileSelf(projectRef, id),
        "storageId" -> storageId
      )
      val expected = jsonContentOf("kg/files/sparql.json", mapping*)
      json should equalIgnoreArrayOrder(expected)
    }
  }

  "Upload files with the .custom extension" should {
    val fileContent = "file content"

    def uploadCustomFile(id: String, contentType: ContentType): ((Json, HttpResponse) => Assertion) => IO[Assertion] = {
      val file = FileInput(id, "file.custom", contentType, fileContent)
      deltaClient.uploadFile(
        projectRef,
        storageId,
        file,
        None
      )
    }

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

  "A custom binary file" should {
    "not be downloadable compressed" in {
      for {
        _ <- deltaClient.uploadFile(projectRef, storageId, customBinaryContent, None)(expectCreated)
        _ <- deltaClient
               .get[ByteString](s"/files/$projectRef/attachment:${customBinaryContent.fileId}", Coyote, gzipHeaders) {
                 expectFileContent(
                   customBinaryContent.filename,
                   customBinaryContent.contentType,
                   customBinaryContent.contents,
                   compressed = false, // the response should not be compressed despite the gzip headers
                   cacheable = true
                 )
               }
      } yield succeed
    }
  }

  "Deprecating a storage" should {

    "deprecate a storage" in {
      deltaClient.delete[Json](s"/storages/$projectRef/nxv:$storageId?rev=1", Coyote) { expectOk }
    }

    "reject uploading a new file against the deprecated storage" in {
      deltaClient.uploadFile(projectRef, storageId, randomTextFile, None) { expectBadRequest }
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
          "project"     -> projectRef,
          "storageType" -> storageType
        )*
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
        .putEmptyBody[Json](s"/storages/$projectRef/nxv:$storageId/undeprecate?rev=2", Coyote) { expectOk }
      val uploadFile         = deltaClient.uploadFile(projectRef, storageId, randomTextFile, None) { expectCreated }
      undeprecateStorage >> uploadFile
    }

  }
}
