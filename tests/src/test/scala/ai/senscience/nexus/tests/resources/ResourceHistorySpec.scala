package ai.senscience.nexus.tests.resources

import ai.senscience.nexus.tests.Identity.Anonymous
import ai.senscience.nexus.tests.Identity.resources.Rick
import ai.senscience.nexus.tests.kg.files.model.FileInput
import ai.senscience.nexus.tests.kg.files.model.FileInput.CustomMetadata
import ai.senscience.nexus.tests.{BaseIntegrationSpec, Identity, Optics}
import akka.http.scaladsl.model.{ContentTypes, StatusCodes}
import ch.epfl.bluebrain.nexus.delta.kernel.utils.UrlUtils
import io.circe.Json

class ResourceHistorySpec extends BaseIntegrationSpec {

  implicit val currentUser: Identity.UserCredentials = Rick

  private val orgId   = genId()
  private val projId  = genId()
  private val project = s"$orgId/$projId"

  private val resourceId        = "https://bbp.epfl.ch/data/my-resource"
  private val encodedResourceId = UrlUtils.encodeUriPath(resourceId)

  private val fileInput     = FileInput(
    "https://bbp.epfl.ch/data/my-file",
    "attachment.json",
    ContentTypes.NoContentType,
    """{ "content: "Some content" }""",
    CustomMetadata("Crb 2", "A cerebellum file", Map("brainRegion" -> "cerebellum"))
  )
  private val encodedFileId = UrlUtils.encodeUriPath(fileInput.fileId)

  private val mapping = replacements(
    Rick,
    "org"     -> orgId,
    "project" -> projId
  )

  override def beforeAll(): Unit = {
    super.beforeAll()
    val setup = for {
      _               <- createOrg(Rick, orgId)
      _               <- createProjects(Rick, orgId, projId)
      resourcePayload <- SimpleResource.sourcePayload(resourceId, 42)
      // Creating, updating, tagging and deprecating a resource
      _               <- deltaClient.post[Json](s"/resources/$project/_/", resourcePayload, Rick) { expectCreated }
      updatedPayload  <- SimpleResource.sourcePayload(resourceId, 9000)
      _               <- deltaClient.put[Json](s"/resources/$project/_/$encodedResourceId?rev=1", updatedPayload, Rick) {
                           expectOk
                         }
      _               <- deltaClient.post[Json](s"/resources/$project/_/$encodedResourceId/tags?rev=2", tag("v1.0.0", 1), Rick) {
                           expectCreated
                         }
      _               <- deltaClient.delete(s"/resources/$project/_/$encodedResourceId?rev=3", Rick) { expectOk }
      _               <- deltaClient.uploadFile(project, None, fileInput, None) { expectCreated }
      _               <- deltaClient.post[Json](s"/files/$project/$encodedFileId/tags?rev=1", tag("v1.0.0", 1), Rick) {
                           expectCreated
                         }
    } yield ()
    setup.accepted
  }

  private def filterInstantField = Optics.filterNestedKeys("instant")

  "Getting the history of a resource" should {
    "fail for a user without access" in {
      deltaClient.get[Json](s"/history/resources/$project/$encodedResourceId", Anonymous) { expectForbidden }
    }

    "succeed on a resource for a user with access" in eventually {
      deltaClient.get[Json](s"/history/resources/$project/$encodedResourceId", Rick) { (json, response) =>
        response.status shouldEqual StatusCodes.OK
        val expected = jsonContentOf("resources/history-resource.json", mapping*)
        filterInstantField(json) shouldEqual expected
      }
    }

    "succeed on a file for a user with access" in eventually {
      deltaClient.get[Json](s"/history/resources/$project/$encodedFileId", Rick) { (json, response) =>
        val expected = jsonContentOf("resources/history-file.json", mapping*)
        response.status shouldEqual StatusCodes.OK
        filterInstantField(json) shouldEqual expected
      }
    }
  }
}
