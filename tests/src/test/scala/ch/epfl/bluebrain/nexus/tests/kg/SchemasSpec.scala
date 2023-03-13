package ch.epfl.bluebrain.nexus.tests.kg

import akka.http.scaladsl.model.{HttpResponse, StatusCodes}
import ch.epfl.bluebrain.nexus.delta.kernel.utils.UrlUtils
import ch.epfl.bluebrain.nexus.testkit.TestHelpers.jsonContentOf
import ch.epfl.bluebrain.nexus.testkit.{CirceEq, EitherValuable}
import ch.epfl.bluebrain.nexus.tests.BaseSpec
import ch.epfl.bluebrain.nexus.tests.Identity.resources.Rick
import ch.epfl.bluebrain.nexus.tests.iam.types.Permission.Organizations
import ch.epfl.bluebrain.nexus.tests.kg.SchemaPayloads.{withImportOfPowerLevelShape, withMinCount, withPowerLevelShape}
import io.circe.{Json, JsonObject}
import monix.execution.Scheduler.Implicits.global
import org.scalatest.{Assertion, BeforeAndAfterAll}
import org.scalatest.LoneElement._
import org.scalatest.matchers.{HavePropertyMatchResult, HavePropertyMatcher}

object SchemaPayloads {
  def withPowerLevelShape(id: String, maxPowerLevel: Int): Json = {
    jsonContentOf("/kg/schemas/schema-with-power-level.json", "id" -> id, "maxPowerLevel" -> maxPowerLevel)
  }

  def withImportOfPowerLevelShape(id: String, importedSchemaId: String): Json = {
    jsonContentOf("/kg/schemas/schema-that-imports-power-level.json", "id" -> id, "import" -> importedSchemaId)
  }

  def withMinCount(id: String, minCount: Int): Json = {
    jsonContentOf("/kg/schemas/schema.json", "id" -> id, "minCount" -> minCount)
  }
}

class SchemasSpec extends BaseSpec with EitherValuable with CirceEq with BeforeAndAfterAll {

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

  private def minCount(expectedMinCount: Int) = HavePropertyMatcher[JsonObject, Option[Int]] { json =>
    val actualMinCount = Json.fromJsonObject(json).hcursor.downField("minCount").as[Int]
    HavePropertyMatchResult(
      actualMinCount.contains(expectedMinCount),
      "minCount",
      Some(expectedMinCount),
      actualMinCount.toOption
    )
  }

  private def shapes(schema: Json): List[JsonObject] = {
    schema.hcursor.downField("shapes").as[List[JsonObject]].getOrElse(Nil)
  }

  private def fetchTheSchema(schemaId: String) = { (resp: (Json, HttpResponse) => Assertion) =>
    deltaClient.get[Json](s"/schemas/$id/${UrlUtils.encode(schemaId)}", Rick)(resp).accepted
  }

  "update a schema" in {
    val schemaId = "updatable-schema"

    thereIsASchema(withMinCount(schemaId, minCount = 1))

    schemaIsUpdated(schemaId, 1, withMinCount(schemaId, minCount = 2))

    fetchTheSchema(schemaId) { (json, response) =>
      response.status shouldEqual StatusCodes.OK
      shapes(json).loneElement should have(minCount(2))
    }
  }

  private def thereIsASchema(payload: Json) = {
    deltaClient
      .post[Json](s"/schemas/$id", payload, Rick) { (_, response) =>
        response.status shouldEqual StatusCodes.Created
      }
      .accepted
  }

  private def schemaIsUpdated(schemaId: String, revision: Int, payload: Json) = {
    deltaClient
      .put[Json](s"/schemas/$id/${UrlUtils.encode(schemaId)}?rev=$revision", payload, Rick) { (_, response) =>
        response.status shouldEqual StatusCodes.OK
      }
      .accepted
  }

  private def schemaIsRefreshed(schemaId: String) = {
    deltaClient
      .put[Json](s"/schemas/$id/${UrlUtils.encode(schemaId)}/refresh", Json.Null, Rick) { (_, response) =>
        response.status shouldEqual StatusCodes.OK
      }
      .accepted
  }

  private def whenAResourceIsCreatedWith(
      schemaId: String,
      powerLevel: Int
  ): ((Json, HttpResponse) => Assertion) => Assertion = {
    val resourceId = genId()
    val payload = jsonContentOf(
      "/kg/resources/resource-with-power-level.json",
      "id"         -> resourceId,
      "powerLevel" -> powerLevel
    )

    (resp: (Json, HttpResponse) => Assertion) =>
      deltaClient
        .put[Json](s"/resources/$id/${UrlUtils.encode(schemaId)}/${UrlUtils.encode(resourceId)}", payload, Rick)(resp)
        .accepted
  }

  "refresh a schema" in {
    val powerLevelSchemaId = "https://dev.nexus.test.com/schema-with-power-level"
    val schemaId = "https://dev.nexus.test.com/refreshable-schema"

    thereIsASchema(withPowerLevelShape(id = powerLevelSchemaId, maxPowerLevel = 10000))
    thereIsASchema(withImportOfPowerLevelShape(id = schemaId, importedSchemaId = powerLevelSchemaId))

    whenAResourceIsCreatedWith(schemaId, powerLevel = 9001) { (_, response) =>
      response.status shouldEqual StatusCodes.Created
    }

    schemaIsUpdated(
      powerLevelSchemaId,
      revision = 1,
      withPowerLevelShape(id = powerLevelSchemaId, maxPowerLevel = 9000)
    )

    schemaIsRefreshed(schemaId)

    whenAResourceIsCreatedWith(schemaId, powerLevel = 9001) { (_, response) =>
      response.status shouldEqual StatusCodes.BadRequest
    }
  }
}
