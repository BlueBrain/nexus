package ch.epfl.bluebrain.nexus.tests.kg

import java.net.URLEncoder

import akka.http.scaladsl.model.StatusCodes
import cats.implicits._
import ch.epfl.bluebrain.nexus.testkit.{CirceEq, EitherValuable}
import ch.epfl.bluebrain.nexus.tests.Identity.UserCredentials
import ch.epfl.bluebrain.nexus.tests.Optics._
import ch.epfl.bluebrain.nexus.tests.Tags.ResourcesTag
import ch.epfl.bluebrain.nexus.tests.iam.types.Permission.Organizations
import ch.epfl.bluebrain.nexus.tests.{BaseSpec, Identity, Realm}
import io.circe.Json
import monix.bio.Task
import monix.execution.Scheduler.Implicits.global

class ResourcesSpec extends BaseSpec with EitherValuable with CirceEq {

  private val testRealm  = Realm("resources" + genString())
  private val testClient = Identity.ClientCredentials(genString(), genString(), testRealm)
  private val Rick       = UserCredentials(genString(), genString(), testRealm)

  private val orgId   = genId()
  private val projId1 = genId()
  private val projId2 = genId()
  private val id1     = s"$orgId/$projId1"
  private val id2     = s"$orgId/$projId2"

  override def beforeAll(): Unit = {
    super.beforeAll()
    initRealm(
      testRealm,
      Identity.ServiceAccount,
      testClient,
      Rick :: Nil
    ).runSyncUnsafe()
  }

  "creating projects" should {

    "add necessary permissions for user" taggedAs ResourcesTag in {
      aclDsl.addPermission(
        "/",
        Rick,
        Organizations.Create
      )
    }

    "succeed if payload is correct" taggedAs ResourcesTag in {
      for {
        _ <- adminDsl.createOrganization(orgId, orgId, Rick)
        _ <- adminDsl.createProject(orgId, projId1, kgDsl.projectJson(name = id1), Rick)
        _ <- adminDsl.createProject(orgId, projId2, kgDsl.projectJson(name = id2), Rick)
      } yield succeed
    }
  }

  //TODO Remove when in-project resolver creation is automated
  "create the in-project resolver" should {
    "work" taggedAs ResourcesTag in {
      val resolverPayload = jsonContentOf("/kg/resources/in-project-resolver.json")

      for {
        _ <- deltaClient.post[Json](s"/resolvers/$id1", resolverPayload, Rick) { (_, response) =>
               response.status shouldEqual StatusCodes.Created
             }
        _ <- deltaClient.post[Json](s"/resolvers/$id2", resolverPayload, Rick) { (_, response) =>
               response.status shouldEqual StatusCodes.Created
             }
      } yield succeed
    }
  }

  "adding schema" should {
    "create a schema" taggedAs ResourcesTag in {
      val schemaPayload = jsonContentOf("/kg/schemas/simple-schema.json")

      deltaClient.put[Json](s"/schemas/$id1/test-schema", schemaPayload, Rick) { (_, response) =>
        response.status shouldEqual StatusCodes.Created
      }
    }

    "creating a schema with property shape" taggedAs ResourcesTag in {
      val schemaPayload = jsonContentOf("/kg/schemas/simple-schema-prop-shape.json")

      deltaClient.post[Json](s"/schemas/$id1", schemaPayload, Rick) { (_, response) =>
        response.status shouldEqual StatusCodes.Created
      }
    }

    "creating a schema that imports the property shape schema" taggedAs ResourcesTag in {
      val schemaPayload = jsonContentOf("/kg/schemas/simple-schema-imports.json")

      eventually {
        deltaClient.post[Json](s"/schemas/$id1", schemaPayload, Rick) { (_, response) =>
          response.status shouldEqual StatusCodes.Created
        }
      }
    }
  }

  "creating a resource" should {
    "succeed if the payload is correct" taggedAs ResourcesTag in {
      val payload =
        jsonContentOf(
          "/kg/resources/simple-resource.json",
          "priority"   -> "5",
          "resourceId" -> "1"
        )

      val id2      = URLEncoder.encode("https://dev.nexus.test.com/test-schema-imports", "UTF-8")
      val payload2 = jsonContentOf(
        "/kg/resources/simple-resource.json",
        "priority"   -> "5",
        "resourceId" -> "10"
      )

      for {
        _ <- deltaClient.put[Json](s"/resources/$id1/test-schema/test-resource:1", payload, Rick) { (_, response) =>
               response.status shouldEqual StatusCodes.Created
             }
        _ <- deltaClient.put[Json](s"/resources/$id1/$id2/test-resource:10", payload2, Rick) { (_, response) =>
               response.status shouldEqual StatusCodes.Created
             }
      } yield succeed
    }

    "fetch the payload wih metadata" taggedAs ResourcesTag in {
      deltaClient.get[Json](s"/resources/$id1/test-schema/test-resource:1", Rick) { (json, response) =>
        val expected = jsonContentOf(
          "/kg/resources/simple-resource-response.json",
          replacements(
            Rick,
            "priority"  -> "5",
            "rev"       -> "1",
            "resources" -> s"${config.deltaUri}/resources/$id1",
            "project"   -> s"${config.deltaUri}/projects/$id1"
          ): _*
        )
        response.status shouldEqual StatusCodes.OK
        filterMetadataKeys(json) should equalIgnoreArrayOrder(expected)
      }
    }

    "fetch the original payload" taggedAs ResourcesTag in {
      deltaClient.get[Json](s"/resources/$id1/test-schema/test-resource:1/source", Rick) { (json, response) =>
        val expected =
          jsonContentOf(
            "/kg/resources/simple-resource.json",
            "priority"   -> "5",
            "resourceId" -> "1"
          )
        response.status shouldEqual StatusCodes.OK
        json should equalIgnoreArrayOrder(expected)
      }
    }
  }

  "cross-project resolvers" should {
    val resolverPayload =
      jsonContentOf(
        "/kg/resources/cross-project-resolver.json",
        replacements(Rick, "project" -> id1): _*
      )

    "fail if the schema doesn't exist in the project" taggedAs ResourcesTag in {
      val payload = jsonContentOf(
        "/kg/resources/simple-resource.json",
        "priority"   -> "3",
        "resourceId" -> "1"
      )

      deltaClient.put[Json](s"/resources/$id2/test-schema/test-resource:1", payload, Rick) { (_, response) =>
        response.status shouldEqual StatusCodes.NotFound
      }
    }

    "fail to create a cross-project-resolver for proj2 if identities are missing" taggedAs ResourcesTag in {
      deltaClient.post[Json](s"/resolvers/$id2", filterKey("identities")(resolverPayload), Rick) { (_, response) =>
        response.status shouldEqual StatusCodes.BadRequest
      }
    }

    "create a cross-project-resolver for proj2" taggedAs ResourcesTag in {
      deltaClient.post[Json](s"/resolvers/$id2", resolverPayload, Rick) { (_, response) =>
        response.status shouldEqual StatusCodes.Created
      }
    }

    "update a cross-project-resolver for proj2" taggedAs ResourcesTag in {
      val updated = resolverPayload deepMerge Json.obj("priority" -> Json.fromInt(20))
      eventually {
        deltaClient.put[Json](s"/resolvers/$id2/example-id?rev=1", updated, Rick) { (_, response) =>
          response.status shouldEqual StatusCodes.OK
        }
      }
    }

    "fetch the update" taggedAs ResourcesTag in {
      val expected = jsonContentOf(
        "/kg/resources/cross-project-resolver-updated-resp.json",
        replacements(
          Rick,
          "project"        -> id1,
          "resources"      -> s"${config.deltaUri}/resolvers/$id2",
          "project-parent" -> s"${config.deltaUri}/projects/$id2"
        ): _*
      )

      deltaClient.get[Json](s"/resolvers/$id2/example-id", Rick) { (json, response) =>
        response.status shouldEqual StatusCodes.OK
        filterMetadataKeys(json) should equalIgnoreArrayOrder(expected)
      }
    }

    "wait for the cross-project resolver to be indexed" taggedAs ResourcesTag in {
      val expected = jsonContentOf(
        "/kg/resources/cross-project-resolver-list.json",
        replacements(
          Rick,
          "project_resolver" -> id1,
          "projId"           -> s"$id2",
          "project"          -> s"${config.deltaUri}/projects/$id2"
        ): _*
      )

      eventually {
        deltaClient.get[Json](s"/resolvers/$id2", Rick) { (json, response) =>
          response.status shouldEqual StatusCodes.OK
          filterSearchMetadata(json) shouldEqual expected
        }
      }
    }

    s"fetches a resource in project '$id1' through project '$id2' resolvers" taggedAs ResourcesTag in {
      for {
        _ <- deltaClient.get[Json](s"/schemas/$id1/test-schema", Rick) { (json, response1) =>
               response1.status shouldEqual StatusCodes.OK
               runTask {
                 for {
                   _ <- deltaClient.get[Json](s"/resolvers/$id2/_/test-schema", Rick) { (jsonResolved, response2) =>
                          response2.status shouldEqual StatusCodes.OK
                          jsonResolved should equalIgnoreArrayOrder(json)
                        }
                   _ <- deltaClient.get[Json](s"/resolvers/$id2/example-id/test-schema", Rick) {
                          (jsonResolved, response2) =>
                            response2.status shouldEqual StatusCodes.OK
                            jsonResolved should equalIgnoreArrayOrder(json)
                        }
                 } yield {
                   succeed
                 }
               }
             }
        _ <- deltaClient.get[Json](s"/resolvers/$id2/example-id/test-schema-2", Rick) { (_, response) =>
               response.status shouldEqual StatusCodes.NotFound
             }
        _ <- deltaClient.get[Json](s"/resolvers/$id2/_/test-schema-2", Rick) { (_, response) =>
               response.status shouldEqual StatusCodes.NotFound
             }
      } yield succeed
    }

    "resolve schema from the other project" taggedAs ResourcesTag in {
      val payload = jsonContentOf(
        "/kg/resources/simple-resource.json",
        "priority"   -> "3",
        "resourceId" -> "1"
      )

      eventually {
        deltaClient.put[Json](s"/resources/$id2/test-schema/test-resource:1", payload, Rick) { (_, response) =>
          response.status shouldEqual StatusCodes.Created
        }
      }
    }
  }

  "updating a resource" should {
    "send the update" taggedAs ResourcesTag in {
      val payload = jsonContentOf(
        "/kg/resources/simple-resource.json",
        "priority"   -> "3",
        "resourceId" -> "1"
      )

      deltaClient.put[Json](s"/resources/$id1/test-schema/test-resource:1?rev=1", payload, Rick) { (_, response) =>
        response.status shouldEqual StatusCodes.OK
      }
    }

    "fetch the update" taggedAs ResourcesTag in {
      val expected = jsonContentOf(
        "/kg/resources/simple-resource-response.json",
        replacements(
          Rick,
          "priority"  -> "3",
          "rev"       -> "2",
          "resources" -> s"${config.deltaUri}/resources/$id1",
          "project"   -> s"${config.deltaUri}/projects/$id1"
        ): _*
      )
      List(
        s"/resources/$id1/test-schema/test-resource:1",
        s"/resources/$id1/_/test-resource:1"
      ).traverse { url =>
        deltaClient.get[Json](url, Rick) { (json, response) =>
          response.status shouldEqual StatusCodes.OK
          filterMetadataKeys(json) should equalIgnoreArrayOrder(expected)
        }
      }
    }

    "fetch previous revision" taggedAs ResourcesTag in {
      val expected = jsonContentOf(
        "/kg/resources/simple-resource-response.json",
        replacements(
          Rick,
          "priority"  -> "5",
          "rev"       -> "1",
          "resources" -> s"${config.deltaUri}/resources/$id1",
          "project"   -> s"${config.deltaUri}/projects/$id1"
        ): _*
      )

      List(
        s"/resources/$id1/test-schema/test-resource:1?rev=1",
        s"/resources/$id1/_/test-resource:1?rev=1"
      ).traverse { url =>
        deltaClient.get[Json](url, Rick) { (json, response) =>
          response.status shouldEqual StatusCodes.OK
          filterMetadataKeys(json) should equalIgnoreArrayOrder(expected)
        }
      }
    }
  }

  "tagging a resource" should {
    "create a tag" taggedAs ResourcesTag in {
      val tag1 = jsonContentOf("/kg/resources/tag.json", "tag" -> "v1.0.0", "rev" -> "1")
      val tag2 = jsonContentOf("/kg/resources/tag.json", "tag" -> "v1.0.1", "rev" -> "2")

      for {
        _ <- deltaClient.post[Json](s"/resources/$id1/test-schema/test-resource:1/tags?rev=2", tag1, Rick) {
               (_, response) =>
                 response.status shouldEqual StatusCodes.Created
             }
        _ <- deltaClient.post[Json](s"/resources/$id1/_/test-resource:1/tags?rev=3", tag2, Rick) { (_, response) =>
               response.status shouldEqual StatusCodes.Created
             }
      } yield succeed
    }

    "fetch a tagged value" taggedAs ResourcesTag in {
      val expectedTag1 = jsonContentOf(
        "/kg/resources/simple-resource-response.json",
        replacements(
          Rick,
          "priority"  -> "3",
          "rev"       -> "2",
          "resources" -> s"${config.deltaUri}/resources/$id1",
          "project"   -> s"${config.deltaUri}/projects/$id1"
        ): _*
      )

      val expectedTag2 = jsonContentOf(
        "/kg/resources/simple-resource-response.json",
        replacements(
          Rick,
          "priority"  -> "5",
          "rev"       -> "1",
          "resources" -> s"${config.deltaUri}/resources/$id1",
          "project"   -> s"${config.deltaUri}/projects/$id1"
        ): _*
      )

      for {
        _ <-
          deltaClient.get[Json](s"/resources/$id1/test-schema/test-resource:1?tag=v1.0.1", Rick) { (json, response) =>
            response.status shouldEqual StatusCodes.OK
            filterMetadataKeys(json) should equalIgnoreArrayOrder(expectedTag1)
          }
        _ <- deltaClient.get[Json](s"/resources/$id1/_/test-resource:1?tag=v1.0.0", Rick) { (json, response) =>
               response.status shouldEqual StatusCodes.OK
               filterMetadataKeys(json) should equalIgnoreArrayOrder(expectedTag2)
             }
      } yield succeed
    }
  }

  "listing resources" should {

    "list default resources" taggedAs ResourcesTag in {
      val mapping = replacements(
        Rick,
        "project-label" -> id1,
        "project"       -> s"${config.deltaUri}/projects/$id1"
      )

      val resources = List(
        "resolvers" -> jsonContentOf("/kg/listings/default-resolver.json", mapping: _*),
        "views"     -> jsonContentOf("/kg/listings/default-view.json", mapping: _*),
        "storages"  -> jsonContentOf("/kg/listings/default-storage.json", mapping: _*)
      )

      resources.traverse { case (segment, expected) =>
        deltaClient.get[Json](s"/$segment/$id1", Rick) { (json, response) =>
          response.status shouldEqual StatusCodes.OK
          filterSearchMetadata(json) shouldEqual expected
        }
      }
    }

    "add more resource to the project" taggedAs ResourcesTag in {
      (2 to 5).toList.traverse { resourceId =>
        val payload = jsonContentOf(
          "/kg/resources/simple-resource.json",
          "priority"   -> "3",
          "resourceId" -> s"$resourceId"
        )
        deltaClient.put[Json](s"/resources/$id1/test-schema/test-resource:$resourceId", payload, Rick) {
          (_, response) =>
            response.status shouldEqual StatusCodes.Created
        }
      }
    }

    "list the resources" taggedAs ResourcesTag in {
      val expected = jsonContentOf(
        "/kg/listings/response.json",
        replacements(
          Rick,
          "resources" -> s"${config.deltaUri}/resources/$id1",
          "project"   -> s"${config.deltaUri}/projects/$id1"
        ): _*
      )

      eventually {
        deltaClient.get[Json](s"/resources/$id1/test-schema", Rick) { (json, response) =>
          response.status shouldEqual StatusCodes.OK
          filterSearchMetadata(json) shouldEqual expected
        }
      }
    }

    "return 400 when using both from and after" taggedAs ResourcesTag in {
      deltaClient.get[Json](s"/resources/$id1/test-schema?from=10&after=%5B%22test%22%5D", Rick) { (json, response) =>
        response.status shouldEqual StatusCodes.BadRequest
        json shouldEqual jsonContentOf("/kg/listings/from-and-after-error.json")
      }
    }

    "return 400 when from is bigger than limit" taggedAs ResourcesTag in {
      deltaClient.get[Json](s"/resources/$id1/test-schema?from=10001", Rick) { (json, response) =>
        response.status shouldEqual StatusCodes.BadRequest
        json shouldEqual jsonContentOf("/kg/listings/from-over-limit-error.json")
      }
    }

    "list responses using after" taggedAs ResourcesTag in {
      // Building the next results, replace the public url by the one used by the tests
      def next(json: Json) =
        resources._next.getOption(json).map { url =>
          url.replace(config.deltaUri.toString(), "")
        }

      // Get results though a lens and filtering out some fields
      def lens(json: Json) =
        resources._results
          .getOption(json)
          .fold(Vector.empty[Json]) { _.map(filterMetadataKeys) }

      val result = deltaClient
        .stream(
          s"/resources/$id1/test-schema?size=2",
          next,
          lens,
          Rick
        )
        .compile
        .toList

      val expected = resources._results
        .getOption(
          jsonContentOf(
            "/kg/listings/response.json",
            replacements(
              Rick,
              "resources" -> s"${config.deltaUri}/resources/$id1",
              "project"   -> s"${config.deltaUri}/projects/$id1"
            ): _*
          )
        )
        .value

      result.flatMap { l =>
        Task.pure(l.flatten shouldEqual expected)
      }
    }
  }

  "create context" taggedAs ResourcesTag in {
    val payload = jsonContentOf("/kg/resources/simple-context.json")

    deltaClient.put[Json](s"/resources/$id1/_/test-resource:mycontext", payload, Rick) { (_, response) =>
      response.status shouldEqual StatusCodes.Created
    }
  }

  "create resource using the created context" taggedAs ResourcesTag in {
    val payload = jsonContentOf("/kg/resources/simple-resource-context.json")

    deltaClient.post[Json](s"/resources/$id1/", payload, Rick) { (_, response) =>
      response.status shouldEqual StatusCodes.Created
    }
  }

}
