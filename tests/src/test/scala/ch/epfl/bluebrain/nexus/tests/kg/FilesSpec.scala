package ch.epfl.bluebrain.nexus.tests.kg

import akka.http.scaladsl.model.{ContentTypes, StatusCodes}
import ch.epfl.bluebrain.nexus.tests.BaseIntegrationSpec
import ch.epfl.bluebrain.nexus.tests.Identity.files.Writer
import ch.epfl.bluebrain.nexus.tests.Optics.listing._total
import io.circe.Json
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

  private def assertListingTotal(id: String, expectedTotal: Int) =
    deltaClient.get[Json](s"/files/$projectRef?locate=$id&deprecated=false", Writer) { (json, response) =>
      response.status shouldEqual StatusCodes.OK
      _total.getOption(json) should contain(expectedTotal)
    }

  private def assertFileIsInListing(id: String) =
    assertListingTotal(id, 1)

  private def assertFileNotInListing(id: String) =
    assertListingTotal(id, 0)

  /** Provides a file in the default storage */
  private def givenAFile(assertion: String => Assertion): Assertion = {
    val id = genString()
    deltaClient
      .uploadFile[Json](
        s"/files/$projectRef/$id",
        "file content",
        ContentTypes.`text/plain(UTF-8)`,
        s"$id.txt",
        Writer
      ) { (_, response) =>
        response.status shouldEqual StatusCodes.Created
      }
      .accepted
    eventually { assertFileIsInListing(id) }
    assertion(id)
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
