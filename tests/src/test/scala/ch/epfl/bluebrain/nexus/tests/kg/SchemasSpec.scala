package ch.epfl.bluebrain.nexus.tests.kg

import akka.http.scaladsl.model.StatusCodes
import ch.epfl.bluebrain.nexus.delta.kernel.utils.UrlUtils
import ch.epfl.bluebrain.nexus.testkit.CirceEq
import ch.epfl.bluebrain.nexus.testkit.scalatest.EitherValues
import ch.epfl.bluebrain.nexus.tests.BaseIntegrationSpec
import ch.epfl.bluebrain.nexus.tests.Identity.resources.Rick
import ch.epfl.bluebrain.nexus.tests.builders.SchemaPayloads._
import ch.epfl.bluebrain.nexus.tests.iam.types.Permission.Organizations
import io.circe.Json
import org.scalatest.BeforeAndAfterAll
import org.scalatest.LoneElement._

class SchemasSpec extends BaseIntegrationSpec with EitherValues with CirceEq with BeforeAndAfterAll {

  private val orgId  = genId()
  private val projId = genId()
  private val id     = s"$orgId/$projId"

  override def beforeAll(): Unit = {
    super.beforeAll()

    (for {
      _ <- aclDsl.addPermission(
             "/",
             Rick,
             Organizations.Create
           )
      _ <- adminDsl.createOrganization(orgId, orgId, Rick)
      _ <- adminDsl.createProject(orgId, projId, kgDsl.projectJson(name = id), Rick)
    } yield succeed).accepted
    ()
  }

  "Schemas" should {
    "update a schema" in {
      val schemaId = "updatable-schema"
      for {
        _ <- deltaClient
               .post[Json](s"/schemas/$id", withMinCount(schemaId, minCount = 1), Rick) { (_, response) =>
                 response.status shouldEqual StatusCodes.Created
               }

        _ <-
          deltaClient
            .put[Json](s"/schemas/$id/${UrlUtils.encode(schemaId)}?rev=1", withMinCount(schemaId, minCount = 2), Rick) {
              (_, response) =>
                response.status shouldEqual StatusCodes.OK
            }

        _ <- deltaClient.get[Json](s"/schemas/$id/${UrlUtils.encode(schemaId)}", Rick) { (json, response) =>
               response.status shouldEqual StatusCodes.OK
               json.hcursor
                 .downField("shapes")
                 .as[List[Json]]
                 .getOrElse(Nil)
                 .loneElement
                 .hcursor
                 .downField("minCount")
                 .as[Int]
                 .toOption shouldEqual Some(2)
             }
      } yield succeed
    }

    "refresh a schema" in {
      val powerLevelSchemaId = "https://dev.nexus.test.com/schema-with-power-level"
      val schemaId           = "https://dev.nexus.test.com/refreshable-schema"

      for {
        _ <- deltaClient
               .post[Json](s"/schemas/$id", withPowerLevelShape(id = powerLevelSchemaId, maxPowerLevel = 10000), Rick) {
                 (_, response) =>
                   response.status shouldEqual StatusCodes.Created
               }
        _ <- deltaClient
               .post[Json](
                 s"/schemas/$id",
                 withImportOfPowerLevelShape(id = schemaId, importedSchemaId = powerLevelSchemaId),
                 Rick
               ) { (_, response) =>
                 response.status shouldEqual StatusCodes.Created
               }
        _ <- deltaClient
               .post[Json](
                 s"/resources/$id/${UrlUtils.encode(schemaId)}",
                 jsonContentOf(
                   "/kg/resources/resource-with-power-level.json",
                   "id"         -> genId(),
                   "powerLevel" -> 9001
                 ),
                 Rick
               ) { (_, response) =>
                 response.status shouldEqual StatusCodes.Created
               }
        _ <- deltaClient
               .put[Json](
                 s"/schemas/$id/${UrlUtils.encode(powerLevelSchemaId)}?rev=1",
                 withPowerLevelShape(id = powerLevelSchemaId, maxPowerLevel = 9000),
                 Rick
               ) { (_, response) =>
                 response.status shouldEqual StatusCodes.OK
               }
        _ <- deltaClient
               .put[Json](s"/schemas/$id/${UrlUtils.encode(schemaId)}/refresh", Json.Null, Rick) { (_, response) =>
                 response.status shouldEqual StatusCodes.OK
               }
        _ <- deltaClient
               .post[Json](
                 s"/resources/$id/${UrlUtils.encode(schemaId)}",
                 jsonContentOf(
                   "/kg/resources/resource-with-power-level.json",
                   "id"         -> genId(),
                   "powerLevel" -> 9001
                 ),
                 Rick
               ) { (_, response) =>
                 response.status shouldEqual StatusCodes.BadRequest
               }
      } yield succeed
    }

    "when a resource is created, validate it against a schema" in {
      val schemaId = "bicycle-validation-schema"

      for {
        _ <- deltaClient
               .post[Json](
                 s"/schemas/$id",
                 jsonContentOf("/kg/schemas/bicycle-schema.json", "id" -> schemaId, "maxNumberOfGears" -> 13),
                 Rick
               ) { (_, response) =>
                 response.status shouldEqual StatusCodes.Created
               }
        _ <- deltaClient
               .post[Json](
                 s"/resources/$id/${UrlUtils.encode(schemaId)}",
                 jsonContentOf("/kg/resources/bicycle.json", "id" -> genId(), "gears" -> 13),
                 Rick
               ) { (_, response) =>
                 response.status shouldEqual StatusCodes.Created
               }
        _ <- deltaClient
               .post[Json](
                 s"/resources/$id/${UrlUtils.encode(schemaId)}",
                 jsonContentOf("/kg/resources/bicycle.json", "id" -> genId(), "gears" -> 14),
                 Rick
               ) { (_, response) =>
                 response.status shouldEqual StatusCodes.BadRequest
               }
      } yield succeed
    }

    "validate a resource against another schema" in {
      val schemaId13Gears = "bicycle-with-13-gears"
      val schemaId12Gears = "bicycle-with-12-gears"
      val resourceId      = "my-bike"

      for {
        _ <- deltaClient
               .post[Json](
                 s"/schemas/$id",
                 jsonContentOf("/kg/schemas/bicycle-schema.json", "id" -> schemaId13Gears, "maxNumberOfGears" -> 13),
                 Rick
               ) { (_, response) =>
                 response.status shouldEqual StatusCodes.Created
               }
        _ <- deltaClient
               .post[Json](
                 s"/schemas/$id",
                 jsonContentOf("/kg/schemas/bicycle-schema.json", "id" -> schemaId12Gears, "maxNumberOfGears" -> 12),
                 Rick
               ) { (_, response) =>
                 response.status shouldEqual StatusCodes.Created
               }
        _ <- deltaClient
               .post[Json](
                 s"/resources/$id/$schemaId13Gears",
                 jsonContentOf("/kg/resources/bicycle.json", "id" -> resourceId, "gears" -> 13),
                 Rick
               ) { (_, response) =>
                 response.status shouldEqual StatusCodes.Created
               }
        _ <- deltaClient.get[Json](s"/resources/$id/$schemaId13Gears/$resourceId/validate", Rick) { (json, response) =>
               response.status shouldEqual StatusCodes.OK
               json.hcursor.downField("@type").as[String].toOption shouldEqual Some("Validated")
             }
        _ <- deltaClient.get[Json](s"/resources/$id/$schemaId12Gears/$resourceId/validate", Rick) { (json, response) =>
               response.status shouldEqual StatusCodes.BadRequest
               json.hcursor.downField("@type").as[String].toOption shouldEqual Some("InvalidResource")
               json.hcursor.downField("details").downField("@type").as[String].toOption shouldEqual Some(
                 "sh:ValidationReport"
               )
             }
      } yield succeed
    }
  }

}
