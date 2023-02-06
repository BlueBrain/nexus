package ch.epfl.bluebrain.nexus.tests.kg

import akka.http.scaladsl.model.StatusCodes
import ch.epfl.bluebrain.nexus.testkit.{CirceLiteral, EitherValuable, IOValues}
import ch.epfl.bluebrain.nexus.tests.BaseSpec
import ch.epfl.bluebrain.nexus.tests.Identity.views.ScoobyDoo
import ch.epfl.bluebrain.nexus.tests.Optics.{filterKeys, projections}
import ch.epfl.bluebrain.nexus.tests.iam.types.Permission.{Organizations, Supervision}
import io.circe._

class SupervisionSpec extends BaseSpec with EitherValuable with CirceLiteral with IOValues {

  "The supervision endpoint" should {
    s"reject calls without ${Supervision.Read.value} permission" in {
      deltaClient.get[Json]("/supervision/projections", ScoobyDoo) { (_, response) =>
        response.status shouldEqual StatusCodes.Forbidden
      }
    }

    s"accept calls with ${Supervision.Read.value}" in {
      aclDsl.addPermission("/", ScoobyDoo, Supervision.Read).accepted
      deltaClient.get[Json]("/supervision/projections", ScoobyDoo) { (_, response) =>
        response.status shouldEqual StatusCodes.OK
      }
    }
  }

  private val orgId  = genId()
  private val projId = genId()
  private val fullId = s"$orgId/$projId"

  "creating projects" should {
    "add necessary permissions for user" in {
      for {
        _ <- aclDsl.addPermission("/", ScoobyDoo, Organizations.Create)
      } yield succeed
    }

    "succeed in creating an org and project" in {
      for {
        _ <- adminDsl.createOrganization(orgId, orgId, ScoobyDoo)
        _ <- adminDsl.createProject(orgId, projId, kgDsl.projectJson(name = fullId), ScoobyDoo)
      } yield succeed
    }
  }

  "An elasticsearch view projection supervision description" should {
    val esViewName          = "sv-es-view"
    val createEsViewPayload =
      jsonContentOf("/kg/supervision/es-payload.json", "viewName" -> esViewName, "type" -> "https://schema.org/Book")
    val updateEsViewPayload =
      jsonContentOf("/kg/supervision/es-payload.json", "viewName" -> esViewName, "type" -> "https://schema.org/Movie")

    "not exist before project is created" in {
      deltaClient.get[Json]("/supervision/projections", ScoobyDoo) { (json, _) =>
        val viewMetaDataJson = jsonContentOf(
          "/kg/supervision/scoped-projection-metadata.json",
          "module"   -> "elasticsearch",
          "project"  -> fullId,
          "viewName" -> esViewName,
          "revision" -> 1,
          "restarts" -> 0
        )
        assert(!metadataExists(viewMetaDataJson)(json))
      }
    }

    "exist after a project is created" in {
      deltaClient.put[Json](s"/views/$fullId/test-resource:$esViewName", createEsViewPayload, ScoobyDoo) { (_, _) =>
        eventually {
          deltaClient.get[Json]("/supervision/projections", ScoobyDoo) { (json, _) =>
            val expected = jsonContentOf(
              "/kg/supervision/scoped-projection-metadata.json",
              "module"   -> "elasticsearch",
              "project"  -> fullId,
              "viewName" -> esViewName,
              "revision" -> 1,
              "restarts" -> 0
            )
            assert(metadataExists(expected)(json))
          }
        }
      }
    }

    "reflects a view update" in {
      deltaClient.put[Json](s"/views/$fullId/test-resource:$esViewName?rev=1", updateEsViewPayload, ScoobyDoo) {
        (_, _) =>
          eventually {
            deltaClient.get[Json]("/supervision/projections", ScoobyDoo) { (json, _) =>
              val expected = jsonContentOf(
                "/kg/supervision/scoped-projection-metadata.json",
                "module"   -> "elasticsearch",
                "project"  -> fullId,
                "viewName" -> esViewName,
                "revision" -> 2,
                "restarts" -> 0
              )
              assert(metadataExists(expected)(json))
            }
          }
      }
    }

    "reflects a view restart" in {
      deltaClient.delete[Json](s"/views/$fullId/test-resource:$esViewName/offset", ScoobyDoo) { (_, _) =>
        eventually {
          deltaClient.get[Json]("/supervision/projections", ScoobyDoo) { (json, _) =>
            val expected = jsonContentOf(
              "/kg/supervision/scoped-projection-metadata.json",
              "module"   -> "elasticsearch",
              "project"  -> fullId,
              "viewName" -> esViewName,
              "revision" -> 2,
              "restarts" -> 1
            )
            assert(metadataExists(expected)(json))
          }
        }
      }
    }

    "reflect a view deprecation" in {
      deltaClient.delete[Json](s"/views/$fullId/test-resource:$esViewName?rev=2", ScoobyDoo) { (_, _) =>
        eventually {
          deltaClient.get[Json]("/supervision/projections", ScoobyDoo) { (json, _) =>
            val expected = jsonContentOf(
              "/kg/supervision/scoped-projection-metadata.json",
              "module"   -> "elasticsearch",
              "project"  -> fullId,
              "viewName" -> esViewName,
              "revision" -> 2,
              "restarts" -> 1
            )
            assert(!metadataExists(expected)(json))
          }
        }
      }
    }
  }

  /** For a JSON array of supervised description, checks that it contains the provided content */
  private def metadataExists(expectedMetadata: Json) = (supervisedDescriptions: Json) =>
    projections.json
      .getAll(supervisedDescriptions)
      .map(filterKeys(Set("executionStrategy", "status", "progress")))
      .contains(expectedMetadata)

}
