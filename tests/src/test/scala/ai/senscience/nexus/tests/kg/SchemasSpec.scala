package ai.senscience.nexus.tests.kg

import ai.senscience.nexus.tests.Identity.Anonymous
import ai.senscience.nexus.tests.Identity.resources.Rick
import ai.senscience.nexus.tests.Optics.sparql
import ai.senscience.nexus.tests.builders.SchemaPayloads
import ai.senscience.nexus.tests.builders.SchemaPayloads.*
import ai.senscience.nexus.tests.{BaseIntegrationSpec, Optics}
import akka.http.scaladsl.model.StatusCodes
import ch.epfl.bluebrain.nexus.delta.kernel.utils.UrlUtils.encodeUriPath
import io.circe.Json
import io.circe.optics.JsonPath.root

class SchemasSpec extends BaseIntegrationSpec {

  private val orgId   = genId()
  private val projId  = genId()
  private val project = s"$orgId/$projId"

  override def beforeAll(): Unit = {
    super.beforeAll()
    createProjects(Rick, orgId, projId).accepted
  }

  "Schemas" should {
    "update a schema" in {
      val schemaId  = "updatable-schema"
      val encodedId = encodeUriPath(schemaId)

      def expectMinCount(json: Json, value: Int) =
        root.shapes.index(0).minCount.int.getOption(json) shouldEqual Some(value)

      for {
        withMin1 <- withMinCount(schemaId, minCount = 1)
        _        <- deltaClient.post[Json](s"/schemas/$project", withMin1, Rick) { expectCreated }
        withMin2 <- withMinCount(schemaId, minCount = 2)
        _        <- deltaClient.put[Json](s"/schemas/$project/$encodedId?rev=1", withMin2, Rick) { expectOk }
        _        <- deltaClient.get[Json](s"/schemas/$project/$encodedId", Rick) { (json, response) =>
                      response.status shouldEqual StatusCodes.OK
                      expectMinCount(json, 2)
                    }
      } yield succeed
    }

    "fail creating a schema with a deprecated import" in {
      val baseSchemaId      = "https://localhost/base-schema"
      val importingSchemaId = "https://localhost/importing-schema"

      def checkDeprecationError(json: Json) = {
        val rejectionsRoot = root.schemaImports.report.history.index(0).rejections
        rejectionsRoot.arr.getOption(json).map(_.length) shouldEqual Some(1)
        rejectionsRoot.index(0).cause.`@type`.string.getOption(json) shouldEqual Some("ResourceIsDeprecated")
      }

      for {
        baseSchemaPayload      <- withPowerLevelShape(id = baseSchemaId, maxPowerLevel = 10000)
        _                      <- deltaClient.post[Json](s"/schemas/$project", baseSchemaPayload, Rick) { expectCreated }
        _                      <- deltaClient.delete[Json](s"/schemas/$project/${encodeUriPath(baseSchemaId)}?rev=1", Rick) { expectOk }
        importingSchemaPayload <- withImportOfPowerLevelShape(id = importingSchemaId, importedSchemaId = baseSchemaId)
        _                      <- deltaClient.post[Json](s"/schemas/$project", importingSchemaPayload, Rick) { (json, response) =>
                                    response.status shouldEqual StatusCodes.BadRequest
                                    json should have(`@type`("InvalidSchemaResolution"))
                                    checkDeprecationError(json)
                                  }
      } yield succeed
    }

    "have indexed the schema in the default sparql namespace" in eventually {
      val query =
        s"""
           |prefix nxv: <https://bluebrain.github.io/nexus/vocabulary/>
           |SELECT (COUNT(*) as ?count)
           |WHERE { ?s a nxv:Schema }
      """.stripMargin
      deltaClient.sparqlQuery[Json](s"/views/$project/graph/sparql", query, Rick) { (json, response) =>
        response.status shouldEqual StatusCodes.OK
        sparql.countResult(json).value should be > 0
      }
    }

    "refresh a schema" in {
      val powerLevelSchemaId = "https://dev.nexus.test.com/schema-with-power-level"
      val schemaId           = "https://dev.nexus.test.com/refreshable-schema"

      val encodedPowerLevelSchemaId = encodeUriPath(powerLevelSchemaId)
      val encodedSchemaId           = encodeUriPath(schemaId)

      def genResource =
        jsonContentOf(
          "kg/resources/resource-with-power-level.json",
          "id"         -> genId(),
          "powerLevel" -> 9001
        )

      for {
        powerLevelPayload  <- withPowerLevelShape(id = powerLevelSchemaId, maxPowerLevel = 10000)
        _                  <- deltaClient.post[Json](s"/schemas/$project", powerLevelPayload, Rick) { expectCreated }
        withImportsPayload <- withImportOfPowerLevelShape(id = schemaId, importedSchemaId = powerLevelSchemaId)
        _                  <- deltaClient.post[Json](s"/schemas/$project", withImportsPayload, Rick) { expectCreated }
        _                  <- deltaClient.post[Json](s"/resources/$project/$encodedSchemaId", genResource, Rick) { expectCreated }
        powerLevelUpdated  <- withPowerLevelShape(id = powerLevelSchemaId, maxPowerLevel = 9000)
        _                  <- deltaClient.put[Json](s"/schemas/$project/$encodedPowerLevelSchemaId?rev=1", powerLevelUpdated, Rick) {
                                expectOk
                              }
        _                  <- deltaClient.put[Json](s"/schemas/$project/$encodedSchemaId/refresh", Json.Null, Rick) { expectOk }
        _                  <- deltaClient.post[Json](s"/resources/$project/$encodedSchemaId", genResource, Rick) { expectBadRequest }
      } yield succeed
    }

    "when a resource is created, validate it against a schema" in {
      val schemaId        = "bicycle-validation-schema"
      val encodedSchemaId = encodeUriPath(schemaId)

      def bicycleSchema = jsonContentOf("kg/schemas/bicycle-schema.json", "id" -> schemaId, "maxNumberOfGears" -> 13)

      def generateBicycleResource(gears: Int) =
        jsonContentOf("kg/resources/bicycle.json", "id" -> genId(), "gears" -> gears)

      for {
        _ <- deltaClient.post[Json](s"/schemas/$project", bicycleSchema, Rick) { expectCreated }
        _ <- deltaClient.post[Json](s"/resources/$project/$encodedSchemaId", generateBicycleResource(13), Rick) {
               expectCreated
             }
        _ <- deltaClient.post[Json](s"/resources/$project/$encodedSchemaId", generateBicycleResource(14), Rick) {
               expectBadRequest
             }
      } yield succeed
    }

    "validate a resource against another schema" in {
      val schemaId13Gears = "bicycle-with-13-gears"
      val schemaId12Gears = "bicycle-with-12-gears"
      val resourceId      = "my-bike"

      def bicycleSchema(schemaId: String, gears: Int) =
        jsonContentOf("kg/schemas/bicycle-schema.json", "id" -> schemaId, "maxNumberOfGears" -> gears)
      def bicycleResource                             = jsonContentOf("kg/resources/bicycle.json", "id" -> resourceId, "gears" -> 13)

      for {
        _ <- deltaClient.post[Json](s"/schemas/$project", bicycleSchema(schemaId13Gears, 13), Rick) { expectCreated }
        _ <- deltaClient.post[Json](s"/schemas/$project", bicycleSchema(schemaId12Gears, 12), Rick) { expectCreated }
        _ <- deltaClient.post[Json](s"/resources/$project/$schemaId13Gears", bicycleResource, Rick) { expectCreated }
        _ <- deltaClient.get[Json](s"/resources/$project/$schemaId13Gears/$resourceId/validate", Rick) {
               (json, response) =>
                 response.status shouldEqual StatusCodes.OK
                 Optics.`@type`.getOption(json).value shouldEqual "Validated"
             }
        _ <- deltaClient.get[Json](s"/resources/$project/$schemaId12Gears/$resourceId/validate", Rick) {
               (json, response) =>
                 response.status shouldEqual StatusCodes.BadRequest
                 Optics.`@type`.getOption(json).value shouldEqual "InvalidResource"
                 root.details.`@type`.string.getOption(json).value shouldEqual "sh:ValidationReport"
             }
      } yield succeed
    }

    "have the ability to be undeprecated" in {
      val id = genId()
      for {
        payload <- SchemaPayloads.simple(id)
        _       <- deltaClient.post[Json](s"/schemas/$project", payload, Rick) { expectCreated }
        _       <- deltaClient.delete[Json](s"/schemas/$project/$id?rev=1", Rick) { expectOk }
        _       <- deltaClient.put[Json](s"/schemas/$project/$id/undeprecate?rev=2", Json.Null, Rick) { expectOk }
        _       <- deltaClient.get[Json](s"/schemas/$project/$id", Rick) { (json, response) =>
                     response.status shouldEqual StatusCodes.OK
                     json.hcursor.downField("_deprecated").as[Boolean].toOption shouldEqual Some(false)
                   }
      } yield succeed
    }

    "fetch the original payload for a user with access" in {
      val id = genId()
      for {
        payload <- SchemaPayloads.simple(id)
        _       <- deltaClient.post[Json](s"/schemas/$project", payload, Rick) { expectCreated }
        // Forbidden for anonymous
        _       <- deltaClient.get[Json](s"/schemas/$project/$id/source", Anonymous) { expectForbidden }
        // Granted for the user with the read permission
        _       <- deltaClient.get[Json](s"/schemas/$project/$id/source", Rick) { (json, response) =>
                     response.status shouldEqual StatusCodes.OK
                     json shouldEqual payload
                   }
        _       <- deltaClient.get[Json](s"/schemas/$project/$id/source?annotate=true", Rick) { (json, response) =>
                     response.status shouldEqual StatusCodes.OK
                     json.asObject.value.keys should contain allOf ("_createdAt", "_updatedAt", "@id", "@type")
                   }
      } yield succeed
    }

    "create a schema against the resource endpoint" in {
      val id            = genId()
      val schemaSegment = encodeUriPath("https://bluebrain.github.io/nexus/schemas/shacl-20170720.ttl")

      for {
        payload <- SchemaPayloads.simple(id)
        _       <- deltaClient.put[Json](s"/resources/$project/$schemaSegment/$id", payload, Rick) { expectCreated }
        // Attempting to create it again and to get a 409 as a response
        _       <- deltaClient.put[Json](s"/resources/$project/$schemaSegment/$id", payload, Rick) { (json, response) =>
                     json should have(`@type`("ResourceAlreadyExists"))
                     response.status shouldEqual StatusCodes.Conflict
                   }
        // Should be fetched as a schema
        _       <- deltaClient.get[Json](s"/schemas/$project/$id", Rick) { expectOk }
      } yield succeed

    }
  }

}
