package ch.epfl.bluebrain.nexus.tests.kg

import akka.http.scaladsl.model.{HttpResponse, StatusCodes}
import ch.epfl.bluebrain.nexus.delta.kernel.utils.UrlUtils
import ch.epfl.bluebrain.nexus.testkit.builders.ResourceBuilder.resourceWith
import ch.epfl.bluebrain.nexus.testkit.builders.SchemaBuilder.State.{HasId, Valid}
import ch.epfl.bluebrain.nexus.testkit.builders.SchemaBuilder.schemaWith
import ch.epfl.bluebrain.nexus.testkit.builders.ShapeBuilder.shapeWith
import ch.epfl.bluebrain.nexus.testkit.builders.{ResourceBuilder, SchemaBuilder}
import ch.epfl.bluebrain.nexus.testkit.matchers.JsonMatchers.{`@type`, detailsWhichIsAValidationReport, field}
import ch.epfl.bluebrain.nexus.testkit.{CirceEq, EitherValuable}
import ch.epfl.bluebrain.nexus.tests.BaseSpec
import ch.epfl.bluebrain.nexus.tests.Identity.resources.Rick
import ch.epfl.bluebrain.nexus.tests.builders.SchemaPayloads._
import ch.epfl.bluebrain.nexus.tests.iam.types.Permission.Organizations
import ch.epfl.bluebrain.nexus.tests.kg.Bicycles.{GearsField, Namespace, Prefix, RoadBicycleResourceType}
import io.circe.Json
import monix.execution.Scheduler.Implicits.global
import org.scalactic.source.Position
import org.scalatest.LoneElement._
import org.scalatest.{Assertion, BeforeAndAfterAll}

class SchemasSpec extends BaseSpec with EitherValuable with CirceEq with BeforeAndAfterAll {

  private val orgId  = genId()
  private val projId = genId()
  private val id     = s"$orgId/$projId"

  private def minCount(expectedMinCount: Int) = {
    field("minCount", expectedMinCount)
  }

  private def shapes(schema: Json): List[Json] = {
    schema.hcursor.downField("shapes").as[List[Json]].getOrElse(Nil)
  }

  private def fetchTheSchema(schemaId: String) = { (resp: (Json, HttpResponse) => Assertion) =>
    deltaClient.get[Json](s"/schemas/$id/${UrlUtils.encode(schemaId)}", Rick)(resp).accepted
  }

  private def gearsPropertyWithMaxValue(amount: Int): Json = {
    shapeWith(id = "bicycles:gears", typ = "sh:PropertyShape", path = GearsField, name = "Number of gears")
      .targetClass(RoadBicycleResourceType)
      .datatype("xsd:integer")
      .maxInclusive(amount)
      .build
  }

  private def bicycleSchemaWithMaximumGears(id: String, maxGears: Int) = {
    schemaWith
      .id(id)
      .shape(gearsPropertyWithMaxValue(maxGears))
      .prefixForNamespace(Prefix, Namespace)
  }

  private def thereIsASchema[A <: Valid with HasId](schema: SchemaBuilder[A])(implicit pos: Position) = {
    deltaClient
      .post[Json](s"/schemas/$id", schema.build, Rick) { (_, response) =>
        response.status shouldEqual StatusCodes.Created
      }
      .accepted
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
    val payload    = jsonContentOf(
      "/kg/resources/resource-with-power-level.json",
      "id"         -> resourceId,
      "powerLevel" -> powerLevel
    )
    whenAResourceIsCreated(schemaId, payload)
  }

  private def whenAResourceIsCreated(
      schemaId: String,
      resource: ResourceBuilder
  ): ((Json, HttpResponse) => Assertion) => Assertion = { (resp: (Json, HttpResponse) => Assertion) =>
    deltaClient
      .post[Json](s"/resources/$id/${UrlUtils.encode(schemaId)}", resource.build, Rick)(resp)
      .accepted
  }

  private def whenAResourceIsCreated(
      schemaId: String,
      payload: Json
  ): ((Json, HttpResponse) => Assertion) => Assertion = { (resp: (Json, HttpResponse) => Assertion) =>
    deltaClient
      .post[Json](s"/resources/$id/${UrlUtils.encode(schemaId)}", payload, Rick)(resp)
      .accepted
  }

  private def bicycleWithGears(numberOfGears: Int, id: String = genId()): ResourceBuilder = {
    resourceWith(id, typ = RoadBicycleResourceType)
      .withPrefixForNamespace(Prefix, Namespace)
      .withField(GearsField, numberOfGears)
  }

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

      thereIsASchema(withMinCount(schemaId, minCount = 1))

      schemaIsUpdated(schemaId, 1, withMinCount(schemaId, minCount = 2))

      fetchTheSchema(schemaId) { (json, response) =>
        response.status shouldEqual StatusCodes.OK
        shapes(json).loneElement should have(minCount(2))
      }
    }

    "refresh a schema" in {
      val powerLevelSchemaId = "https://dev.nexus.test.com/schema-with-power-level"
      val schemaId           = "https://dev.nexus.test.com/refreshable-schema"

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

    "when a resource is created, validate it against a schema" in {
      val schemaId = "bicycle-validation-schema"

      thereIsASchema(
        bicycleSchemaWithMaximumGears(schemaId, maxGears = 13)
      )

      whenAResourceIsCreated(schemaId, bicycleWithGears(13)) { (_, response) =>
        response.status shouldEqual StatusCodes.Created
      }

      whenAResourceIsCreated(schemaId, bicycleWithGears(14)) { (_, response) =>
        response.status shouldEqual StatusCodes.BadRequest
      }
    }

    "validate a resource against another schema" in {
      val schemaId13Gears = "bicycle-with-13-gears"
      val schemaId12Gears = "bicycle-with-12-gears"

      thereIsASchema(
        bicycleSchemaWithMaximumGears(schemaId13Gears, maxGears = 13)
      )
      thereIsASchema(
        bicycleSchemaWithMaximumGears(schemaId12Gears, maxGears = 12)
      )

      val resourceId = "my-bike"
      whenAResourceIsCreated(schemaId13Gears, bicycleWithGears(13, resourceId)) { (_, response) =>
        response.status shouldEqual StatusCodes.Created
      }

      for {
        _ <- deltaClient.get[Json](s"/resources/$id/$schemaId13Gears/$resourceId/validate", Rick) { (json, response) =>
               response.status shouldEqual StatusCodes.OK
               json should have(`@type`("sh:ValidationReport"))
             }
        _ <- deltaClient.get[Json](s"/resources/$id/$schemaId12Gears/$resourceId/validate", Rick) { (json, response) =>
               response.status shouldEqual StatusCodes.BadRequest
               json should have(`@type`("InvalidResource"))
               json should have(detailsWhichIsAValidationReport)
             }
      } yield succeed
    }
  }

}

object Bicycles {
  val Prefix                  = "bicycles"
  val Namespace               = "https://bicycles.epfl.ch/"
  val GearsField              = "bicycles:gears"
  val RoadBicycleResourceType = "bicycles:RoadBicycle"
}
