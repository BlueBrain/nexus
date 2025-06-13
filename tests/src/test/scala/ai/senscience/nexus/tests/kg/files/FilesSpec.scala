package ai.senscience.nexus.tests.kg.files

import ai.senscience.nexus.tests.Identity.Anonymous
import ai.senscience.nexus.tests.Identity.files.Writer
import ai.senscience.nexus.tests.Optics.listing._total
import ai.senscience.nexus.tests.kg.files.model.FileInput
import ai.senscience.nexus.tests.kg.files.model.FileInput.CustomMetadata
import ai.senscience.nexus.tests.{BaseIntegrationSpec, Identity}
import akka.http.scaladsl.model.headers.RawHeader
import akka.http.scaladsl.model.{ContentTypes, StatusCodes}
import cats.effect.IO
import ch.epfl.bluebrain.nexus.delta.kernel.utils.UrlUtils.encodeUriQuery
import ch.epfl.bluebrain.nexus.testkit.scalatest.FileMatchers.{description as descriptionField, keywords, name as nameField}
import ch.epfl.bluebrain.nexus.testkit.scalatest.ResourceMatchers.`@id`
import io.circe.Json
import io.circe.syntax.*
import org.scalatest.Assertion

class FilesSpec extends BaseIntegrationSpec {

  private val org        = genString()
  private val project    = genString()
  private val projectRef = s"$org/$project"

  private val dummyJson = json"""{ "random": "content" }"""

  override def beforeAll(): Unit = {
    super.beforeAll()
    createProjects(Writer, org, project).accepted
  }

  "File deprecation" should {

    "remove the deprecated file from the listing" in {
      givenAFile { id =>
        deprecateFile(id, rev = 1)
        eventually { assertFileNotInListing(id) }
      }
    }
  }

  "Files undeprecation" should {

    "reindex the previously deprecated file" in {
      givenADeprecatedFile { id =>
        undeprecateFile(id, rev = 2)
        eventually { assertFileIsInListing(id) }
      }
    }

  }

  "Creating a file with metadata" should {
    "allow a file to be found via keyword search" in {
      val cerebellumId = givenAFileWithBrainRegion("cerebellum")
      val cortexId     = givenAFileWithBrainRegion("cortex")

      val results = queryForFilesWithBrainRegion("cerebellum").accepted

      exactly(1, results) should have(`@id`(cerebellumId))
      no(results) should have(`@id`(cortexId))
    }

    "when searching for a keyword which no document has, no results should be returned" in {
      givenAFileWithBrainRegion("cerebellum")
      givenAFileWithBrainRegion("cortex")

      queryForFilesWithBrainRegion("hippocampus").accepted shouldBe empty
      queryForFilesWithKeywords("nonExistentKey" -> "value").accepted shouldBe empty
    }

    "allow a file to be found via full text search" in {
      val cerebellumId = givenAFileWithBrainRegion("cerebellum")
      val cortexId     = givenAFileWithBrainRegion("cortex")

      val results = queryForFilesWithFreeText("cerebellum").accepted

      exactly(1, results) should have(`@id`(cerebellumId))
      no(results) should have(`@id`(cortexId))
    }

    "allow a file to be found via the description" in {
      val coolId = givenAFileWithDescription("A really cool file")
      val warmId = givenAFileWithDescription("A really warm file")

      val results = queryForFilesWithFreeText("cool").accepted

      exactly(1, results) should have(`@id`(coolId))
      no(results) should have(`@id`(warmId))
    }

    "allow a file to be found via the name" in {
      val faxId  = givenAFileWithName("File o fax")
      val fishId = givenAFileWithName("File et o fish")

      val results = queryForFilesWithFreeText("fish").accepted

      exactly(1, results) should have(`@id`(fishId))
      no(results) should have(`@id`(faxId))
    }
  }

  "Updating only custom metadata" should {

    "fail without permission" in {
      givenAFile { id =>
        val md     = Json.obj(
          "name" -> Json.fromString("new name")
        )
        val header = RawHeader("x-nxs-file-metadata", md.noSpaces) :: Nil

        val rejectCustomMetadataUpdate = deltaClient
          .putEmptyBody[Json](s"/files/$projectRef/$id?rev=1", Anonymous, header) { (_, response) =>
            response.status shouldEqual StatusCodes.Forbidden
          }
        val assertFileNotUpdated       = deltaClient.get[Json](s"/files/$projectRef/$id", Writer) { (json, response) =>
          response.status shouldEqual StatusCodes.OK
          json.hcursor.get[Int]("_rev").rightValue shouldEqual 1
        }

        (rejectCustomMetadataUpdate >> assertFileNotUpdated).accepted

      }
    }

    "update the custom metadata of the file" in {
      givenAFile { id =>
        val updatedName = "new name"
        val md          = Json.obj("name" := updatedName)
        val header      = RawHeader("x-nxs-file-metadata", md.noSpaces) :: Nil

        val updateCustomMetadata = deltaClient
          .putEmptyBody[Json](s"/files/$projectRef/$id?rev=1", Writer, header) { (_, response) =>
            response.status shouldEqual StatusCodes.OK
          }

        val assertMetadataUpdated = deltaClient.get[Json](s"/files/$projectRef/$id", Writer) { (json, response) =>
          response.status shouldEqual StatusCodes.OK
          json.hcursor.get[Int]("_rev").rightValue shouldEqual 2
          json should have(nameField(updatedName))
        }

        (updateCustomMetadata >> assertMetadataUpdated).accepted
      }
    }

    "update the custom metadata and tag at the same time" in {
      givenAFile { id =>
        val updatedName = "new name"
        val md          = Json.obj("name" := updatedName)
        val header      = RawHeader("x-nxs-file-metadata", md.noSpaces) :: Nil

        val updateCustomMetadata = deltaClient
          .putEmptyBody[Json](s"/files/$projectRef/$id?rev=1&tag=tag1", Writer, header) { expectOk }

        val assertMetadataUpdated = deltaClient.get[Json](s"/files/$projectRef/$id?tag=tag1", Writer) {
          (json, response) =>
            response.status shouldEqual StatusCodes.OK
            json.hcursor.get[Int]("_rev").rightValue shouldEqual 2
            json should have(nameField(updatedName))
        }

        (updateCustomMetadata >> assertMetadataUpdated).accepted
      }
    }
  }

  private def assertListingTotal(id: String, expectedTotal: Int) =
    deltaClient.get[Json](s"/files/$projectRef?locate=$id&deprecated=false", Writer) { (json, response) =>
      response.status shouldEqual StatusCodes.OK
      _total.getOption(json) should contain(expectedTotal)
    }

  private def assertFileIsInListing(id: String) =
    assertListingTotal(id, 1)

  private def assertFileNotInListing(id: String) =
    assertListingTotal(id, 0)

  private def queryForFilesWithBrainRegion(brainRegion: String): IO[List[Json]] = {
    queryForFilesWithKeywords("brainRegion" -> brainRegion)
  }

  private def queryForFilesWithFreeText(text: String): IO[List[Json]] = {
    deltaClient
      .getJson[Json](s"/files/$projectRef?q=$text", Writer)
      .map { json =>
        json.hcursor.downField("_results").as[List[Json]].rightValue
      }
  }

  private def queryForFilesWithKeywords(keywords: (String, String)*): IO[List[Json]] = {
    val encodedKeywords = encodeUriQuery(keywords.toMap.asJson.noSpaces)
    deltaClient
      .getJson[Json](s"/files/$projectRef?keywords=$encodedKeywords", Writer)
      .map { json =>
        json.hcursor.downField("_results").as[List[Json]].rightValue
      }
  }

  private def givenAFileWithBrainRegion(brainRegion: String): String = {
    val id       = genString()
    val metadata = CustomMetadata(None, None, Map("brainRegion" -> brainRegion))
    val file     = FileInput(id, s"$id.json", ContentTypes.`text/plain(UTF-8)`, "file content", metadata)
    val fullId   = uploadFile(file, _ should have(keywords("brainRegion" -> brainRegion)))
    eventually { assertFileIsInListing(id) }
    fullId
  }

  private def givenAFileWithName(name: String): String = {
    val id       = genString()
    val metadata = CustomMetadata(Some(name), None, Map.empty)
    val file     = FileInput(id, s"$id.json", ContentTypes.`text/plain(UTF-8)`, "file content", metadata)
    val fullId   = uploadFile(file, _ should have(nameField(name)))
    eventually { assertFileIsInListing(id) }
    fullId
  }

  private def givenAFileWithDescription(description: String): String = {
    val id       = genString()
    val metadata = CustomMetadata(None, Some(description), Map.empty)
    val file     = FileInput(id, s"$id.json", ContentTypes.`text/plain(UTF-8)`, "file content", metadata)
    val fullId   = uploadFile(file, _ should have(descriptionField(description)))
    eventually { assertFileIsInListing(id) }
    fullId
  }

  private def extractId(json: Json) = {
    json.hcursor.downField("@id").as[String].getOrElse(fail("Could not extract @id from response"))
  }

  /** Provides a file in the default storage */
  private def givenAFile(assertion: String => Assertion): Assertion = {
    val id   = genString()
    val file = FileInput(id, s"$id.json", ContentTypes.`text/plain(UTF-8)`, "file content")
    uploadFile(file, _ => succeed)
    eventually { assertFileIsInListing(id) }
    assertion(id)
  }

  private def uploadFile(file: FileInput, assertJson: Json => Assertion) = {
    implicit val identity: Identity = Writer
    val fullId                      = {
      for {
        _    <- deltaClient.uploadFile(projectRef, None, file, None) { case (json, response) =>
                  response.status shouldEqual StatusCodes.Created
                  assertJson(json)
                }
        json <- deltaClient.getJson(s"/files/$projectRef/${file.fileId}", Writer)
      } yield extractId(json)
    }.accepted

    fullId
  }

  private def deprecateFile(id: String, rev: Int): Assertion =
    deltaClient
      .delete[Json](s"/files/$projectRef/$id?rev=$rev", Writer) { (_, response) =>
        response.status shouldEqual StatusCodes.OK
      }
      .accepted

  private def givenADeprecatedFile(assertion: String => Assertion): Assertion = {
    givenAFile { id =>
      deprecateFile(id, 1)
      eventually { assertFileNotInListing(id) }
      assertion(id)
    }
  }

  private def undeprecateFile(id: String, rev: Int): Assertion =
    deltaClient
      .put[Json](s"/files/$projectRef/$id/undeprecate?rev=$rev", dummyJson, Writer) { (_, response) =>
        response.status shouldEqual StatusCodes.OK
      }
      .accepted

}
