package ch.epfl.bluebrain.nexus.tests.kg

import akka.http.scaladsl.model.StatusCodes
import ch.epfl.bluebrain.nexus.delta.kernel.utils.UrlUtils.encodeUriPath
import ch.epfl.bluebrain.nexus.tests.BaseIntegrationSpec
import ch.epfl.bluebrain.nexus.tests.Identity.Anonymous
import ch.epfl.bluebrain.nexus.tests.Identity.resources.Rick
import ch.epfl.bluebrain.nexus.tests.builders.SchemaPayloads
import ch.epfl.bluebrain.nexus.tests.builders.SchemaPayloads.*
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
      val schemaId = "updatable-schema"

      def expectMinCount(json: Json, value: Int) =
        root.shapes.index(0).minCount.int.getOption(json) shouldEqual Some(value)

      for {
        _ <- deltaClient.postIO[Json](s"/schemas/$project", withMinCount(schemaId, minCount = 1), Rick) {
               expectCreated
             }
        _ <-
          deltaClient
            .putIO[Json](
              s"/schemas/$project/${encodeUriPath(schemaId)}?rev=1",
              withMinCount(schemaId, minCount = 2),
              Rick
            ) {
              expectOk
            }
        _ <- deltaClient.get[Json](s"/schemas/$project/${encodeUriPath(schemaId)}", Rick) { (json, response) =>
               response.status shouldEqual StatusCodes.OK
               expectMinCount(json, 2)
             }
      } yield succeed
    }

    "fail creating a schema with a deprecated import" in {
      val baseSchemaId           = "https://localhost/base-schema"
      val baseSchemaPayload      = withPowerLevelShape(id = baseSchemaId, maxPowerLevel = 10000).accepted
      val importingSchemaId      = "https://localhost/importing-schema"
      val importingSchemaPayload =
        withImportOfPowerLevelShape(id = importingSchemaId, importedSchemaId = baseSchemaId).accepted

      def checkDeprecationError(json: Json) = {
        val rejectionsRoot = root.schemaImports.report.history.index(0).rejections
        rejectionsRoot.arr.getOption(json).map(_.length) shouldEqual Some(1)
        rejectionsRoot.index(0).cause.`@type`.string.getOption(json) shouldEqual Some("ResourceIsDeprecated")
      }

      for {
        _ <- deltaClient.post[Json](s"/schemas/$project", baseSchemaPayload, Rick) { expectCreated }
        _ <- deltaClient.delete[Json](s"/schemas/$project/${encodeUriPath(baseSchemaId)}?rev=1", Rick) { expectOk }
        _ <- deltaClient.post[Json](s"/schemas/$project", importingSchemaPayload, Rick) { (json, response) =>
               response.status shouldEqual StatusCodes.BadRequest
               json should have(`@type`("InvalidSchemaResolution"))
               checkDeprecationError(json)
             }
      } yield succeed
    }

    "refresh a schema" in {
      val powerLevelSchemaId = "https://dev.nexus.test.com/schema-with-power-level"
      val schemaId           = "https://dev.nexus.test.com/refreshable-schema"

      def resourceWithPowerLevel(id: String, powerLevel: Int) =
        jsonContentOf(
          "kg/resources/resource-with-power-level.json",
          "id"         -> id,
          "powerLevel" -> powerLevel
        )

      for {
        _ <- deltaClient.postIO[Json](
               s"/schemas/$project",
               withPowerLevelShape(id = powerLevelSchemaId, maxPowerLevel = 10000),
               Rick
             ) { expectCreated }
        _ <- deltaClient
               .postIO[Json](
                 s"/schemas/$project",
                 withImportOfPowerLevelShape(id = schemaId, importedSchemaId = powerLevelSchemaId),
                 Rick
               ) { expectCreated }
        _ <- deltaClient
               .post[Json](
                 s"/resources/$project/${encodeUriPath(schemaId)}",
                 resourceWithPowerLevel(genId(), 9001),
                 Rick
               ) { expectCreated }
        _ <- deltaClient
               .putIO[Json](
                 s"/schemas/$project/${encodeUriPath(powerLevelSchemaId)}?rev=1",
                 withPowerLevelShape(id = powerLevelSchemaId, maxPowerLevel = 9000),
                 Rick
               ) { expectOk }
        _ <- deltaClient
               .put[Json](s"/schemas/$project/${encodeUriPath(schemaId)}/refresh", Json.Null, Rick) { expectOk }
        _ <- deltaClient
               .post[Json](
                 s"/resources/$project/${encodeUriPath(schemaId)}",
                 resourceWithPowerLevel(genId(), 9001),
                 Rick
               ) { expectBadRequest }
      } yield succeed
    }

    "when a resource is created, validate it against a schema" in {
      val schemaId = "bicycle-validation-schema"

      for {
        _ <- deltaClient
               .post[Json](
                 s"/schemas/$project",
                 jsonContentOf("kg/schemas/bicycle-schema.json", "id" -> schemaId, "maxNumberOfGears" -> 13),
                 Rick
               ) { expectCreated }
        _ <- deltaClient
               .post[Json](
                 s"/resources/$project/${encodeUriPath(schemaId)}",
                 jsonContentOf("kg/resources/bicycle.json", "id" -> genId(), "gears" -> 13),
                 Rick
               ) { expectCreated }
        _ <- deltaClient
               .post[Json](
                 s"/resources/$project/${encodeUriPath(schemaId)}",
                 jsonContentOf("kg/resources/bicycle.json", "id" -> genId(), "gears" -> 14),
                 Rick
               ) { expectBadRequest }
      } yield succeed
    }

    "validate a resource against another schema" in {
      val schemaId13Gears = "bicycle-with-13-gears"
      val schemaId12Gears = "bicycle-with-12-gears"
      val resourceId      = "my-bike"

      for {
        _ <- deltaClient
               .post[Json](
                 s"/schemas/$project",
                 jsonContentOf("kg/schemas/bicycle-schema.json", "id" -> schemaId13Gears, "maxNumberOfGears" -> 13),
                 Rick
               ) { expectCreated }
        _ <- deltaClient
               .post[Json](
                 s"/schemas/$project",
                 jsonContentOf("kg/schemas/bicycle-schema.json", "id" -> schemaId12Gears, "maxNumberOfGears" -> 12),
                 Rick
               ) { expectCreated }
        _ <- deltaClient
               .post[Json](
                 s"/resources/$project/$schemaId13Gears",
                 jsonContentOf("kg/resources/bicycle.json", "id" -> resourceId, "gears" -> 13),
                 Rick
               ) { expectCreated }
        _ <- deltaClient.get[Json](s"/resources/$project/$schemaId13Gears/$resourceId/validate", Rick) {
               (json, response) =>
                 response.status shouldEqual StatusCodes.OK
                 json.hcursor.downField("@type").as[String].toOption shouldEqual Some("Validated")
             }
        _ <- deltaClient.get[Json](s"/resources/$project/$schemaId12Gears/$resourceId/validate", Rick) {
               (json, response) =>
                 response.status shouldEqual StatusCodes.BadRequest
                 json should have(`@type`("InvalidResource"))
                 json.hcursor.downField("details").downField("@type").as[String].toOption shouldEqual Some(
                   "sh:ValidationReport"
                 )
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
