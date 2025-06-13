package ai.senscience.nexus.tests.schemas

import ai.senscience.nexus.tests.Identity.Anonymous
import ai.senscience.nexus.tests.Identity.resources.Rick
import ai.senscience.nexus.tests.Optics.filterNestedKeys
import ai.senscience.nexus.tests.iam.types.Permission.Schemas
import ai.senscience.nexus.tests.resources.SimpleResource
import ai.senscience.nexus.tests.{BaseIntegrationSpec, SchemaPayload}
import akka.http.scaladsl.model.StatusCodes
import io.circe.Json

final class SchemaValidationJobSpec extends BaseIntegrationSpec {

  private val orgId   = genId()
  private val projId  = genId()
  private val project = s"$orgId/$projId"

  private val unconstrainedSchema = "_"

  private val testSchema = "test-schema"

  private val initiallyValidResource = genId()

  private def createResource(id: String, tpe: Option[String], schema: String) = {
    for {
      payload <- tpe match {
                   case None      => SimpleResource.sourcePayload(id, 42)
                   case Some(tpe) => SimpleResource.sourcePayloadWithType(tpe, 42)
                 }
      _       <- deltaClient.post[Json](s"/resources/$project/$schema/", payload, Rick) {
                   expectCreated
                 }
    } yield ()
  }

  override def beforeAll(): Unit = {
    super.beforeAll()
    val setup = for {
      _                       <- createOrg(Rick, orgId)
      _                       <- createProjects(Rick, orgId, projId)
      // Adding the permission to run the schema validation job
      _                       <- aclDsl.addPermission("/", Rick, Schemas.Run)
      // Create schema
      schemaPayload           <- SchemaPayload.loadSimpleNoId()
      _                       <- deltaClient.put[Json](s"/schemas/$project/$testSchema", schemaPayload, Rick) { expectCreated }
      // Creating a validated resource which will not resist the update of the schema because of its type
      _                       <- createResource(initiallyValidResource, None, testSchema)
      // Creating unconstrained resource
      unconstrainedResource    = genId()
      _                       <- createResource(unconstrainedResource, None, unconstrainedSchema)
      // Creating deprecated resource
      deprecatedResource       = genId()
      _                       <- createResource(deprecatedResource, None, testSchema)
      _                       <- deltaClient.delete(s"/resources/$project/_/$deprecatedResource?rev=1", Rick) { expectOk }
      // Updating the schema
      anotherType              = "schema:AnotherType"
      updatedSchemaPayload    <- SchemaPayload.loadSimpleNoId(anotherType)
      _                       <- deltaClient.put[Json](s"/schemas/$project/$testSchema?rev=1", updatedSchemaPayload, Rick) { expectOk }
      // Creating a validated resource with the new type which should succeed the job
      anotherValidatedResource = genId()
      _                       <- createResource(anotherValidatedResource, Some(anotherType), testSchema)
    } yield ()
    setup.accepted
  }

  "Running the validation job" should {

    "not be allowed for an unauthorized user" in {
      deltaClient.post[Json](s"/jobs/schemas/validation/$project/", Json.Null, Anonymous) {
        expectForbidden
      }
    }

    "be run for an authorized user" in {
      deltaClient.post[Json](s"/jobs/schemas/validation/$project/", Json.Null, Rick) { (_, response) =>
        response.status shouldEqual StatusCodes.Accepted
      }
    }

    "return the expected stats" in eventually {
      val expected =
        json"""
        {
        "@context": "https://bluebrain.github.io/nexus/contexts/statistics.json",
        "delayInSeconds": 0,
        "discardedEvents": 2,
        "evaluatedEvents": 1,
        "failedEvents": 1,
        "processedEvents": 4,
        "remainingEvents": 0,
        "totalEvents": 4
      }"""
      deltaClient.get[Json](s"/jobs/schemas/validation/$project/statistics", Rick) { (json, response) =>
        response.status shouldEqual StatusCodes.OK
        filterNestedKeys("lastEventDateTime", "lastProcessedEventDateTime")(json) shouldEqual expected
      }
    }

    "return the schema validation job errors" in {
      deltaClient.get[Json](s"/jobs/schemas/validation/$project/errors", Rick) { (json, response) =>
        response.status shouldEqual StatusCodes.OK
        val expected =
          json"""
            {
              "id" : "http://delta:8080/v1/resources/$project/_/$initiallyValidResource",
              "project" : "$project",
              "rev" : 1
            }"""
        filterNestedKeys("offset", "reason")(json) shouldEqual expected
      }
    }

  }

}
