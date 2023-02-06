package ch.epfl.bluebrain.nexus.tests.kg

import akka.http.scaladsl.model.StatusCodes
import ch.epfl.bluebrain.nexus.testkit.{CirceLiteral, EitherValuable, IOValues}
import ch.epfl.bluebrain.nexus.tests.BaseSpec
import ch.epfl.bluebrain.nexus.tests.Identity.supervision.Mickey
import ch.epfl.bluebrain.nexus.tests.Optics.{filterKeys, projections}
import ch.epfl.bluebrain.nexus.tests.iam.types.Permission.{Events, Organizations, Supervision}
import io.circe._

class SupervisionSpec extends BaseSpec with EitherValuable with CirceLiteral with IOValues {

  "The supervision endpoint" should {
    s"reject calls without ${Supervision.Read.value} permission" in {
      deltaClient.get[Json]("/supervision/projections", Mickey) { (_, response) =>
        response.status shouldEqual StatusCodes.Forbidden
      }
    }

    s"accept calls with ${Supervision.Read.value}" in {
      aclDsl.addPermission("/", Mickey, Supervision.Read).accepted
      deltaClient.get[Json]("/supervision/projections", Mickey) { (_, response) =>
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
        _ <- aclDsl.addPermissions("/", Mickey, Set(Organizations.Create, Events.Read))
      } yield succeed
    }

    "succeed in creating an org and project" in {
      for {
        _ <- adminDsl.createOrganization(orgId, orgId, Mickey)
        _ <- adminDsl.createProject(orgId, projId, kgDsl.projectJson(name = fullId), Mickey)
      } yield succeed
    }
  }

  "An elasticsearch view projection supervision description" should {
    val viewName                                                      = "sv-es-view"
    val viewId                                                        = s"https://dev.nexus.test.com/simplified-resource/$viewName"
    val module                                                        = "elasticsearch"
    val projectionName                                                = s"$module-$fullId-$viewId"
    val createEsViewPayload                                           =
      jsonContentOf("/kg/supervision/es-payload.json", "viewName" -> viewName, "type" -> "https://schema.org/Book")
    val updateEsViewPayload                                           =
      jsonContentOf("/kg/supervision/es-payload.json", "viewName" -> viewName, "type" -> "https://schema.org/Movie")

    def elasticsearchProjectionMetadata(revision: Int, restarts: Int) =
      metadataJson(module, projectionName, fullId, viewId, revision, restarts)

    "not exist before project is created" in {
      deltaClient.get[Json]("/supervision/projections", Mickey) { (json, _) =>
        val viewMetaDataJson = elasticsearchProjectionMetadata(revision = 1, restarts = 0)
        assert(!metadataExists(viewMetaDataJson)(json))
      }
    }

    "exist after a project is created" in {
      deltaClient.put[Json](s"/views/$fullId/test-resource:$viewName", createEsViewPayload, Mickey) { (_, _) =>
        eventually {
          deltaClient.get[Json]("/supervision/projections", Mickey) { (json, _) =>
            val expected = elasticsearchProjectionMetadata(revision = 1, restarts = 0)
            assert(metadataExists(expected)(json))
          }
        }
      }
    }

    "reflects a view update" in {
      deltaClient.put[Json](s"/views/$fullId/test-resource:$viewName?rev=1", updateEsViewPayload, Mickey) { (_, _) =>
        eventually {
          deltaClient.get[Json]("/supervision/projections", Mickey) { (json, _) =>
            val expected = elasticsearchProjectionMetadata(revision = 2, restarts = 0)
            assert(metadataExists(expected)(json))
          }
        }
      }
    }

    "reflects a view restart" in {
      deltaClient.delete[Json](s"/views/$fullId/test-resource:$viewName/offset", Mickey) { (_, _) =>
        eventually {
          deltaClient.get[Json]("/supervision/projections", Mickey) { (json, _) =>
            val expected = elasticsearchProjectionMetadata(revision = 2, restarts = 1)
            assert(metadataExists(expected)(json))
          }
        }
      }
    }

    "reflect a view deprecation" in {
      deltaClient.delete[Json](s"/views/$fullId/test-resource:$viewName?rev=2", Mickey) { (_, _) =>
        eventually {
          deltaClient.get[Json]("/supervision/projections", Mickey) { (json, _) =>
            val expected = elasticsearchProjectionMetadata(revision = 2, restarts = 1)
            assert(!metadataExists(expected)(json))
          }
        }
      }
    }
  }

  "A blazegraph view projection supervision description" should {

    val viewName                                                   = "sv-bg-view"
    val viewId                                                     = s"https://dev.nexus.test.com/simplified-resource/$viewName"
    val module                                                     = "blazegraph"
    val projectionName                                             = s"$module-$fullId-$viewId"
    val createBgViewPayload                                        =
      jsonContentOf("/kg/supervision/bg-payload.json", "viewName" -> viewName, "type" -> "https://schema.org/Book")
    val updateBgViewPayload                                        =
      jsonContentOf("/kg/supervision/bg-payload.json", "viewName" -> viewName, "type" -> "https://schema.org/Movie")

    def blazegraphProjectionMetadata(revision: Int, restarts: Int) =
      metadataJson(module, projectionName, fullId, viewId, revision, restarts)

    "not exist before project is created" in {
      deltaClient.get[Json]("/supervision/projections", Mickey) { (json, _) =>
        val viewMetaDataJson = blazegraphProjectionMetadata(revision = 1, restarts = 0)
        assert(!metadataExists(viewMetaDataJson)(json))
      }
    }

    "exist after a project is created" in {
      deltaClient.put[Json](s"/views/$fullId/test-resource:$viewName", createBgViewPayload, Mickey) { (_, _) =>
        eventually {
          deltaClient.get[Json]("/supervision/projections", Mickey) { (json, _) =>
            val expected = blazegraphProjectionMetadata(revision = 1, restarts = 0)
            assert(metadataExists(expected)(json))
          }
        }
      }
    }

    "reflects a view update" in {
      deltaClient.put[Json](s"/views/$fullId/test-resource:$viewName?rev=1", updateBgViewPayload, Mickey) { (_, _) =>
        eventually {
          deltaClient.get[Json]("/supervision/projections", Mickey) { (json, _) =>
            val expected = blazegraphProjectionMetadata(revision = 2, restarts = 0)
            assert(metadataExists(expected)(json))
          }
        }
      }
    }

    "reflects a view restart" in {
      deltaClient.delete[Json](s"/views/$fullId/test-resource:$viewName/offset", Mickey) { (_, _) =>
        eventually {
          deltaClient.get[Json]("/supervision/projections", Mickey) { (json, _) =>
            val expected = blazegraphProjectionMetadata(revision = 2, restarts = 1)
            assert(metadataExists(expected)(json))
          }
        }
      }
    }

    "reflect a view deprecation" in {
      deltaClient.delete[Json](s"/views/$fullId/test-resource:$viewName?rev=2", Mickey) { (_, _) =>
        eventually {
          deltaClient.get[Json]("/supervision/projections", Mickey) { (json, _) =>
            val expected = blazegraphProjectionMetadata(revision = 2, restarts = 1)
            assert(!metadataExists(expected)(json))
          }
        }
      }
    }
  }

  "A composite view projection supervision description" should {
    val viewName                   = "sv-composite-view"
    val viewId                     = s"https://dev.nexus.test.com/simplified-resource/$viewName"
    val module                     = "compositeviews"
    val projectionName             = s"composite-views-$fullId-$viewId"
    val createCompositeViewPayload =
      jsonContentOf(
        "/kg/supervision/composite-payload.json",
        "viewName" -> viewName,
        "interval" -> "5 seconds"
      )
    val updateCompositeViewPayload =
      jsonContentOf(
        "/kg/supervision/composite-payload.json",
        "viewName" -> viewName,
        "interval" -> "10 seconds"
      )

    def compositeProjectionMetadata(revision: Int, restart: Int) =
      metadataJson(module, projectionName, fullId, viewId, revision, restart)

    "not exist before project is created" in {
      deltaClient.get[Json]("/supervision/projections", Mickey) { (json, _) =>
        val viewMetaDataJson = compositeProjectionMetadata(revision = 1, restart = 0)
        assert(!metadataExists(viewMetaDataJson)(json))
      }
    }

    "exist after a project is created" in {
      deltaClient.put[Json](s"/views/$fullId/test-resource:$viewName", createCompositeViewPayload, Mickey) {
        (_, _) =>
          eventually {
            deltaClient.get[Json]("/supervision/projections", Mickey) { (json, _) =>
              val expected = compositeProjectionMetadata(revision = 1, restart = 0)
              assert(metadataExists(expected)(json))
            }
          }
      }
    }

    "reflects a view update" in {
      deltaClient.put[Json](s"/views/$fullId/test-resource:$viewName?rev=1", updateCompositeViewPayload, Mickey) {
        (_, _) =>
          eventually {
            deltaClient.get[Json]("/supervision/projections", Mickey) { (json, _) =>
              val expected = compositeProjectionMetadata(revision = 2, restart = 0)
              assert(metadataExists(expected)(json))
            }
          }
      }
    }

    "reflects a view restart" in {
      deltaClient.delete[Json](s"/views/$fullId/test-resource:$viewName/offset", Mickey) { (_, response) =>
        response.status shouldEqual StatusCodes.OK
        eventually {
          deltaClient.get[Json]("/supervision/projections", Mickey) { (json, _) =>
            val expected = compositeProjectionMetadata(revision = 2, restart = 1)
            assert(metadataExists(expected)(json))
          }
        }
      }
    }

    "reflect a view deprecation" in {
      deltaClient.delete[Json](s"/views/$fullId/test-resource:$viewName?rev=2", Mickey) { (_, _) =>
        eventually {
          deltaClient.get[Json]("/supervision/projections", Mickey) { (json, _) =>
            val expected = compositeProjectionMetadata(revision = 2, restart = 1)
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

  private def metadataJson(
      module: String,
      projectionName: String,
      orgProject: String,
      viewId: String,
      revision: Int,
      restarts: Int
  ) =
    jsonContentOf(
      "/kg/supervision/scoped-projection-metadata.json",
      "module"         -> module,
      "projectionName" -> projectionName,
      "project"        -> orgProject,
      "viewId"         -> viewId,
      "revision"       -> revision,
      "restarts"       -> restarts
    )

}
