package ai.senscience.nexus.tests.kg.resources

import ai.senscience.nexus.tests.Identity.listings.Bob
import ai.senscience.nexus.tests.Optics._rev
import ai.senscience.nexus.tests.resources.SimpleResource
import ai.senscience.nexus.tests.{BaseIntegrationSpec, SchemaPayload}
import akka.http.scaladsl.model.StatusCodes
import ch.epfl.bluebrain.nexus.delta.kernel.utils.UrlUtils.encodeUriPath
import io.circe.syntax.{EncoderOps, KeyOps}
import io.circe.{Json, JsonObject}

class ResourceUpdatesSpec extends BaseIntegrationSpec {

  private val orgId   = genId()
  private val projId  = genId()
  private val project = s"$orgId/$projId"

  private val schema        = "test-schema"
  private val unconstrained = "https://bluebrain.github.io/nexus/schemas/unconstrained.json"

  override def beforeAll(): Unit = {
    super.beforeAll()
    val setup = for {
      _             <- createProjects(Bob, orgId, projId)
      schemaPayload <- SchemaPayload.loadSimple()
      _             <- deltaClient.put[Json](s"/schemas/$project/$schema", schemaPayload, Bob) { expectCreated }
    } yield ()
    setup.accepted
  }

  private def createResource(schema: String, payload: Json) = {
    val resourceId = genString()
    deltaClient
      .put[Json](s"/resources/$project/${encodeUriPath(schema)}/$resourceId", payload, Bob) { expectCreated }
      .as(resourceId)
  }

  private def updateResource(resourceId: String, rev: Int, schema: String, payload: Json) =
    deltaClient.put[Json](s"/resources/$project/${encodeUriPath(schema)}/$resourceId?rev=$rev", payload, Bob) {
      expectOk
    }

  private def assertRev(resourceId: String, expectedRev: Int) =
    deltaClient.get[Json](s"/resources/$project/_/$resourceId", Bob) { (json, response) =>
      response.status shouldEqual StatusCodes.OK
      _rev.getOption(json).value shouldEqual expectedRev
    }

  "Updating a resource" should {

    "create a new revision if the payload has changed" in {
      for {
        payload        <- SimpleResource.sourcePayload(42)
        resourceId     <- createResource(schema, payload)
        updatedPayload <- SimpleResource.sourcePayload(100)
        _              <- updateResource(resourceId, 1, schema, updatedPayload)
        _              <- assertRev(resourceId, 2)
      } yield succeed
    }

    "not create a new revision if the payload and the schema haven't changed" in {
      for {
        payload    <- SimpleResource.sourcePayload(42)
        resourceId <- createResource(schema, payload)
        _          <- updateResource(resourceId, 1, "test-schema", payload)
        _          <- assertRev(resourceId, 1)
      } yield succeed
    }

    "create a new revision if the schema has changed" in {
      for {
        payload    <- SimpleResource.sourcePayload(42)
        resourceId <- createResource(schema, payload)
        _          <- updateResource(resourceId, 1, unconstrained, payload)
        _          <- assertRev(resourceId, 2)
      } yield succeed
    }
  }

  "Checking for update changes for a large resource" should {
    "succeed" in {
      val tpe                = "Random"
      val largeRandomPayload = {
        val entry = Json.obj(
          "array"  := (1 to 100).toList,
          "string" := "some-value"
        )
        (1 to 500).foldLeft(JsonObject("@type" := tpe)) { case (acc, index) =>
          acc.add(s"prop$index", entry)
        }
      }.asJson

      for {
        resourceId <- createResource(unconstrained, largeRandomPayload)
        _          <- updateResource(resourceId, 1, unconstrained, largeRandomPayload)
        _          <- assertRev(resourceId, 1)
      } yield succeed
    }
  }

}
