package ai.senscience.nexus.tests.resources

import ai.senscience.nexus.tests.Identity.listings.{Alice, Bob}
import ai.senscience.nexus.tests.{BaseIntegrationSpec, SchemaPayload}
import akka.http.scaladsl.model.StatusCodes
import io.circe.Json
import io.circe.optics.JsonPath.root

class ResourcesTrialSpec extends BaseIntegrationSpec {

  private val org  = genId()
  private val proj = genId()
  private val ref  = s"$org/$proj"

  private val schemaId      = "test-schema"
  private val schemaPayload = SchemaPayload.loadSimple().accepted

  private val resourceId      = s"http://delta:8080/v1/resources/$ref/_/my-resource"
  private val resourcePayload = SimpleResource.sourcePayload(resourceId, 5).accepted

  override def beforeAll(): Unit = {
    super.beforeAll()
    val setup = for {
      // Create a project
      _ <- createProjects(Bob, org, proj)
      // Create a schema
      _ <- deltaClient.put[Json](s"/schemas/$ref/$schemaId", schemaPayload, Bob) { expectCreated }
    } yield ()
    setup.accepted
  }

  "Generating resources" should {

    val payloadWithoutSchema      = json"""{ "resource": $resourcePayload }"""
    val payloadWithExistingSchema = json"""{ "schema": "$schemaId" ,"resource": $resourcePayload }"""
    val newSchemaId               = "https://localhost/schema/new-schema"
    val newSchemaPayload          = root.`@id`.string.modify(_ => newSchemaId)(schemaPayload)
    val payloadWithNewSchema      = json"""{ "schema": $newSchemaPayload ,"resource": $resourcePayload }"""

    def errorType = root.error.`@type`.string.getOption(_)
    def resultId  = root.result.`@id`.string.getOption(_)
    def schema    = root.schema.`@id`.string.getOption(_)

    "fail for a user without access" in {
      deltaClient.post[Json](s"/trial/resources/$ref/", payloadWithoutSchema, Alice)(expectForbidden)
    }

    "fail for an unknown project" in {
      deltaClient.post[Json](s"/trial/resources/$org/xxx/", payloadWithoutSchema, Alice)(expectForbidden)
    }

    "succeed for a payload without schema" in {
      deltaClient.post[Json](s"/trial/resources/$ref/", payloadWithoutSchema, Bob) { (json, response) =>
        response.status shouldEqual StatusCodes.OK
        resultId(json).value shouldEqual resourceId
        schema(json) shouldBe empty
        errorType(json) shouldBe empty
      }
    }

    "succeed for a payload with an existing schema" in {
      deltaClient.post[Json](s"/trial/resources/$ref/", payloadWithExistingSchema, Bob) { (json, response) =>
        response.status shouldEqual StatusCodes.OK
        resultId(json).value shouldEqual resourceId
        schema(json) shouldBe empty
        errorType(json) shouldBe empty
      }
    }

    "succeed for a payload with a new schema" in {
      deltaClient.post[Json](s"/trial/resources/$ref/", payloadWithNewSchema, Bob) { (json, response) =>
        response.status shouldEqual StatusCodes.OK
        resultId(json).value shouldEqual resourceId
        schema(json).value shouldBe newSchemaId
        errorType(json) shouldBe empty
      }
    }

    "fail for a resource with an invalid context without generating any schema" in {
      val payload = json"""{  "resource": { "@context": [ "https://bbp.epfl.ch/unknown-context" ], "test": "fail" } }"""
      deltaClient.post[Json](s"/trial/resources/$ref/", payload, Bob) { (json, response) =>
        response.status shouldEqual StatusCodes.OK
        resultId(json) shouldBe empty
        schema(json) shouldBe empty
        errorType(json).value shouldEqual "InvalidJsonLdFormat"
      }
    }

    "fail for a resource with an invalid context but also returning the generated schema" in {
      val resourcePayload = json"""{ "@context": [ "https://bbp.epfl.ch/unknown-context" ], "test": "fail" }"""
      val payload         = json"""{  "schema": $newSchemaPayload, "resource": $resourcePayload }"""
      deltaClient.post[Json](s"/trial/resources/$ref/", payload, Bob) { (json, response) =>
        response.status shouldEqual StatusCodes.OK
        resultId(json) shouldBe empty
        schema(json).value shouldBe newSchemaId
        errorType(json).value shouldEqual "InvalidJsonLdFormat"
      }
    }

    "fail for a resource when shacl validation fails returning the generated " in {
      val resourcePayload = SimpleResource.sourcePayloadWithType("nxv:UnexpectedType", 99).accepted
      val payload         = json"""{ "schema": $newSchemaPayload ,"resource": $resourcePayload }"""
      deltaClient.post[Json](s"/trial/resources/$ref/", payload, Bob) { (json, response) =>
        response.status shouldEqual StatusCodes.OK
        resultId(json) shouldBe empty
        schema(json).value shouldBe newSchemaId
        errorType(json).value shouldEqual "NoTargetedNode"
      }
    }
  }
}
