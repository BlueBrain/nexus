package ch.epfl.bluebrain.nexus.tests.kg

import akka.http.scaladsl.model.MediaTypes.`text/html`
import akka.http.scaladsl.model.headers.{Accept, Location}
import akka.http.scaladsl.model.{MediaRange, StatusCodes}
import akka.http.scaladsl.unmarshalling.PredefinedFromEntityUnmarshallers
import cats.implicits._
import ch.epfl.bluebrain.nexus.delta.kernel.utils.UrlUtils
import ch.epfl.bluebrain.nexus.testkit.{CirceEq, EitherValuable}
import ch.epfl.bluebrain.nexus.tests.Identity.resources.{Morty, Rick}
import ch.epfl.bluebrain.nexus.tests.Optics.{filterKey, filterMetadataKeys, filterSearchMetadata}
import ch.epfl.bluebrain.nexus.testkit.matchers.JsonMatchers._
import ch.epfl.bluebrain.nexus.tests.iam.types.Permission.Organizations
import ch.epfl.bluebrain.nexus.tests.{BaseSpec, Optics}
import io.circe.Json
import monix.bio.Task
import monix.execution.Scheduler.Implicits.global

import java.net.URLEncoder
import scala.concurrent.duration._

class ResourcesSpec extends BaseSpec with EitherValuable with CirceEq {

  private val orgId   = genId()
  private val projId1 = genId()
  private val projId2 = genId()
  private val projId3 = genId()
  private val id1     = s"$orgId/$projId1"
  private val id2     = s"$orgId/$projId2"
  private val id3     = s"$orgId/$projId3"

  "creating projects" should {

    "add necessary permissions for user" in {
      aclDsl.addPermission(
        "/",
        Rick,
        Organizations.Create
      )
    }

    "succeed if payload is correct" in {
      for {
        _ <- adminDsl.createOrganization(orgId, orgId, Rick)
        _ <- adminDsl.createProject(orgId, projId1, kgDsl.projectJson(name = id1), Rick)
        _ <- adminDsl.createProject(orgId, projId2, kgDsl.projectJson(name = id2), Rick)
      } yield succeed
    }
  }

  "adding schema" should {
    "create a schema" in {
      val schemaPayload = jsonContentOf("/kg/schemas/simple-schema.json")

      deltaClient.put[Json](s"/schemas/$id1/test-schema", schemaPayload, Rick) { (_, response) =>
        response.status shouldEqual StatusCodes.Created
      }
    }

    "creating a schema with property shape" in {
      val schemaPayload = jsonContentOf("/kg/schemas/simple-schema-prop-shape.json")

      deltaClient.post[Json](s"/schemas/$id1", schemaPayload, Rick) { (_, response) =>
        response.status shouldEqual StatusCodes.Created
      }
    }

    "creating a schema that imports the property shape schema" in {
      val schemaPayload = jsonContentOf("/kg/schemas/simple-schema-imports.json")

      eventually {
        deltaClient.post[Json](s"/schemas/$id1", schemaPayload, Rick) { (_, response) =>
          response.status shouldEqual StatusCodes.Created
        }
      }
    }
  }

  "creating a resource" should {

    "fail if created with the same id as the created schema" in {
      deltaClient.put[Json](s"/resources/$id1/_/test-schema", Json.obj(), Rick) { (json, response) =>
        response.status shouldEqual StatusCodes.Conflict
        json shouldEqual jsonContentOf(
          "/kg/resources/resource-already-exists-rejection.json",
          "id"      -> "https://dev.nexus.test.com/test-schema",
          "project" -> id1
        )
      }
    }
    "succeed if the payload is correct" in {
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
        "resourceId" -> "a"
      )

      for {
        _ <- deltaClient.put[Json](s"/resources/$id1/test-schema/test-resource:1", payload, Rick) { (_, response) =>
               response.status shouldEqual StatusCodes.Created
             }
        _ <- deltaClient.put[Json](s"/resources/$id1/$id2/test-resource:a", payload2, Rick) { (_, response) =>
               response.status shouldEqual StatusCodes.Created
             }
      } yield succeed
    }

    "fetch the payload wih metadata" in {
      deltaClient.get[Json](s"/resources/$id1/test-schema/test-resource:1", Rick) { (json, response) =>
        val expected = jsonContentOf(
          "/kg/resources/simple-resource-response.json",
          replacements(
            Rick,
            "priority"   -> "5",
            "rev"        -> "1",
            "resources"  -> s"${config.deltaUri}/resources/$id1",
            "project"    -> s"${config.deltaUri}/projects/$id1",
            "resourceId" -> "1"
          ): _*
        )
        response.status shouldEqual StatusCodes.OK
        filterMetadataKeys(json) should equalIgnoreArrayOrder(expected)
      }
    }

    "fetch the original payload" in {
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

    "fetch the original payload with metadata" in {
      deltaClient.get[Json](s"/resources/$id1/test-schema/test-resource:1/source?annotate=true", Rick) {
        (json, response) =>
          val expected = jsonContentOf(
            "/kg/resources/simple-resource-with-metadata.json",
            replacements(
              Rick,
              "rev"        -> "1",
              "resources"  -> s"${config.deltaUri}/resources/$id1",
              "project"    -> s"${config.deltaUri}/projects/$id1",
              "resourceId" -> "1",
              "priority"   -> "5"
            ): _*
          )
          response.status shouldEqual StatusCodes.OK
          filterMetadataKeys(json) should equalIgnoreArrayOrder(expected)
      }
    }

    "fetch the original payload with unexpanded id with metadata" in {
      val payload =
        jsonContentOf(
          "/kg/resources/simple-resource-with-id.json",
          "priority"   -> "5",
          "resourceId" -> "42"
        )

      for {
        _ <- deltaClient.post[Json](s"/resources/$id1/_/", payload, Rick) { (_, response) =>
               response.status shouldEqual StatusCodes.Created
             }
        _ <- deltaClient.get[Json](s"/resources/$id1/_/42/source?annotate=true", Rick) { (json, response) =>
               response.status shouldEqual StatusCodes.OK
               json should have(`@id`(s"42"))
             }
      } yield succeed
    }

    "fetch the original payload with generated id with metadata" in {
      val payload =
        jsonContentOf(
          "/kg/resources/simple-resource-with-id.json",
          "priority" -> "5"
        )

      var generatedId: String = ""

      for {
        _ <- deltaClient.post[Json](s"/resources/$id1/_/", payload, Rick) { (json, response) =>
               response.status shouldEqual StatusCodes.Created
               generatedId = Optics.`@id`.getOption(json).getOrElse(fail("could not find @id of created resource"))
               succeed
             }
        _ <- deltaClient.get[Json](s"/resources/$id1/_/${UrlUtils.encode(generatedId)}/source?annotate=true", Rick) {
               (json, response) =>
                 response.status shouldEqual StatusCodes.OK
                 json should have(`@id`(generatedId))
             }
      } yield succeed
    }

    "return not found if a resource is missing" in {
      deltaClient.get[Json](s"/resources/$id1/test-schema/does-not-exist-resource:1/source?annotate=true", Rick) {
        (_, response) =>
          response.status shouldEqual StatusCodes.NotFound
      }
    }

    "return forbidden if the user does not have access to the resource" in {
      deltaClient.get[Json](s"/resources/$id1/test-schema/test-resource:1/source?annotate=true", Morty) {
        (_, response) =>
          response.status shouldEqual StatusCodes.Forbidden
      }
    }

    "fail if the schema doesn't exist in the project" in {
      val payload = jsonContentOf(
        "/kg/resources/simple-resource.json",
        "priority"   -> "3",
        "resourceId" -> "1"
      )

      deltaClient.put[Json](s"/resources/$id2/test-schema/test-resource:1", payload, Rick) { (_, response) =>
        response.status shouldEqual StatusCodes.NotFound
      }
    }
  }

  "cross-project resolvers" should {
    val resolverPayload =
      jsonContentOf(
        "/kg/resources/cross-project-resolver.json",
        replacements(Rick, "project" -> id1): _*
      )

    "fail to create a cross-project-resolver for proj2 if identities are missing" in {
      deltaClient.post[Json](s"/resolvers/$id2", filterKey("identities")(resolverPayload), Rick) { (_, response) =>
        response.status shouldEqual StatusCodes.BadRequest
      }
    }

    "create a cross-project-resolver for proj2" in {
      deltaClient.post[Json](s"/resolvers/$id2", resolverPayload, Rick) { (_, response) =>
        response.status shouldEqual StatusCodes.Created
      }
    }

    "update a cross-project-resolver for proj2" in {
      val updated = resolverPayload deepMerge Json.obj("priority" -> Json.fromInt(20))
      eventually {
        deltaClient.put[Json](s"/resolvers/$id2/test-resolver?rev=1", updated, Rick) { (_, response) =>
          response.status shouldEqual StatusCodes.OK
        }
      }
    }

    "fetch the update" in {
      val expected = jsonContentOf(
        "/kg/resources/cross-project-resolver-updated-resp.json",
        replacements(
          Rick,
          "project"        -> id1,
          "resources"      -> s"${config.deltaUri}/resolvers/$id2",
          "project-parent" -> s"${config.deltaUri}/projects/$id2"
        ): _*
      )

      deltaClient.get[Json](s"/resolvers/$id2/test-resolver", Rick) { (json, response) =>
        response.status shouldEqual StatusCodes.OK
        filterMetadataKeys(json) should equalIgnoreArrayOrder(expected)
      }
    }

    "wait for the cross-project resolver to be indexed" in {
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
          filterSearchMetadata(json) should equalIgnoreArrayOrder(expected)
        }
      }
    }

    s"resolves a resource in project '$id1' through project '$id2' resolvers" in {
      for {
        _ <- deltaClient.get[Json](s"/schemas/$id1/test-schema", Rick) { (json, response1) =>
               response1.status shouldEqual StatusCodes.OK
               runTask {
                 for {
                   _ <- deltaClient.get[Json](s"/resolvers/$id2/_/test-schema", Rick) { (jsonResolved, response2) =>
                          response2.status shouldEqual StatusCodes.OK
                          jsonResolved should equalIgnoreArrayOrder(json)
                        }
                   _ <- deltaClient.get[Json](s"/resolvers/$id2/test-resolver/test-schema", Rick) {
                          (jsonResolved, response2) =>
                            response2.status shouldEqual StatusCodes.OK
                            jsonResolved should equalIgnoreArrayOrder(json)
                        }
                 } yield {
                   succeed
                 }
               }
             }
      } yield succeed
    }

    s"return not found when attempting to resolve a non-existing resource in project '$id1' through project '$id2' resolvers" in {
      for {
        _ <- deltaClient.get[Json](s"/resolvers/$id2/test-resolver/test-schema-2", Rick) { (_, response) =>
               response.status shouldEqual StatusCodes.NotFound
             }
        _ <- deltaClient.get[Json](s"/resolvers/$id2/_/test-schema-2", Rick) { (_, response) =>
               response.status shouldEqual StatusCodes.NotFound
             }
      } yield succeed
    }

    "resolve schema from the other project" in {
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
    "send the update" in {
      val payload = jsonContentOf(
        "/kg/resources/simple-resource.json",
        "priority"   -> "3",
        "resourceId" -> "1"
      )

      deltaClient.put[Json](s"/resources/$id1/test-schema/test-resource:1?rev=1", payload, Rick) { (_, response) =>
        response.status shouldEqual StatusCodes.OK
      }
    }

    "fetch the update" in {
      val expected = jsonContentOf(
        "/kg/resources/simple-resource-response.json",
        replacements(
          Rick,
          "priority"   -> "3",
          "rev"        -> "2",
          "resources"  -> s"${config.deltaUri}/resources/$id1",
          "project"    -> s"${config.deltaUri}/projects/$id1",
          "resourceId" -> "1"
        ): _*
      )
      List(
        s"/resources/$id1/test-schema/test-resource:1",
        s"/resources/$id1/_/test-resource:1"
      ).parTraverse { url =>
        deltaClient.get[Json](url, Rick) { (json, response) =>
          response.status shouldEqual StatusCodes.OK
          filterMetadataKeys(json) should equalIgnoreArrayOrder(expected)
        }
      }
    }

    "fetch previous revision" in {
      val expected = jsonContentOf(
        "/kg/resources/simple-resource-response.json",
        replacements(
          Rick,
          "priority"   -> "5",
          "rev"        -> "1",
          "resources"  -> s"${config.deltaUri}/resources/$id1",
          "project"    -> s"${config.deltaUri}/projects/$id1",
          "resourceId" -> "1"
        ): _*
      )

      List(
        s"/resources/$id1/test-schema/test-resource:1?rev=1",
        s"/resources/$id1/_/test-resource:1?rev=1"
      ).parTraverse { url =>
        deltaClient.get[Json](url, Rick) { (json, response) =>
          response.status shouldEqual StatusCodes.OK
          filterMetadataKeys(json) should equalIgnoreArrayOrder(expected)
        }
      }
    }

    "fetch previous revision original payload with metadata" in {
      deltaClient.get[Json](s"/resources/$id1/test-schema/test-resource:1/source?rev=1&annotate=true", Rick) {
        (json, response) =>
          val expected = jsonContentOf(
            "/kg/resources/simple-resource-with-metadata.json",
            replacements(
              Rick,
              "rev"        -> "1",
              "resources"  -> s"${config.deltaUri}/resources/$id1",
              "project"    -> s"${config.deltaUri}/projects/$id1",
              "resourceId" -> "1",
              "priority"   -> "5"
            ): _*
          )
          response.status shouldEqual StatusCodes.OK
          filterMetadataKeys(json) should equalIgnoreArrayOrder(expected)
      }
    }
  }

  "tagging a resource" should {
    "create a tag" in {
      for {
        _ <- deltaClient
               .post[Json](
                 s"/resources/$id1/test-schema/test-resource:1/tags?rev=2&indexing=sync",
                 tag("v1.0.0", 1),
                 Rick
               ) { (_, response) =>
                 response.status shouldEqual StatusCodes.Created
               }
        _ <- deltaClient
               .post[Json](s"/resources/$id1/_/test-resource:1/tags?rev=3&indexing=sync", tag("v1.0.1", 2), Rick) {
                 (_, response) =>
                   response.status shouldEqual StatusCodes.Created
               }
        _ <- deltaClient
               .delete[Json](s"/resources/$id1/_/test-resource:1?rev=4&indexing=sync", Rick) { (_, response) =>
                 response.status shouldEqual StatusCodes.OK
               }
        _ <- deltaClient
               .post[Json](s"/resources/$id1/_/test-resource:1/tags?rev=5&indexing=sync", tag("v1.0.2", 5), Rick) {
                 (_, response) =>
                   response.status shouldEqual StatusCodes.Created
               }
      } yield succeed
    }

    "fetch a tagged value" in {
      val expectedTag1 = jsonContentOf(
        "/kg/resources/simple-resource-response.json",
        replacements(
          Rick,
          "priority"   -> "3",
          "rev"        -> "2",
          "resources"  -> s"${config.deltaUri}/resources/$id1",
          "project"    -> s"${config.deltaUri}/projects/$id1",
          "resourceId" -> "1"
        ): _*
      )

      val expectedTag2 = jsonContentOf(
        "/kg/resources/simple-resource-response.json",
        replacements(
          Rick,
          "priority"   -> "5",
          "rev"        -> "1",
          "resources"  -> s"${config.deltaUri}/resources/$id1",
          "project"    -> s"${config.deltaUri}/projects/$id1",
          "resourceId" -> "1"
        ): _*
      )
      val expectedTag3 = jsonContentOf(
        "/kg/resources/simple-resource-response.json",
        replacements(
          Rick,
          "priority"   -> "3",
          "rev"        -> "5",
          "resources"  -> s"${config.deltaUri}/resources/$id1",
          "project"    -> s"${config.deltaUri}/projects/$id1",
          "resourceId" -> "1"
        ): _*
      ) deepMerge Json.obj("_deprecated" -> Json.fromBoolean(true))

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
        _ <- deltaClient.get[Json](s"/resources/$id1/_/test-resource:1?tag=v1.0.2", Rick) { (json, response) =>
               response.status shouldEqual StatusCodes.OK
               filterMetadataKeys(json) should equalIgnoreArrayOrder(expectedTag3)
             }
      } yield succeed
    }

    "fetch tagged original payload with metadata" in {
      deltaClient.get[Json](s"/resources/$id1/test-schema/test-resource:1/source?tag=v1.0.1&annotate=true", Rick) {
        (json, response) =>
          val expected = jsonContentOf(
            "/kg/resources/simple-resource-with-metadata.json",
            replacements(
              Rick,
              "rev"        -> "2",
              "resources"  -> s"${config.deltaUri}/resources/$id1",
              "project"    -> s"${config.deltaUri}/projects/$id1",
              "resourceId" -> "1",
              "priority"   -> "3"
            ): _*
          )
          response.status shouldEqual StatusCodes.OK
          filterMetadataKeys(json) should equalIgnoreArrayOrder(expected)
      }
    }
  }

  "check consistency of responses" in {
    (2 to 100).toList.traverse { resourceId =>
      val payload = jsonContentOf(
        "/kg/resources/simple-resource.json",
        "priority"   -> "3",
        "resourceId" -> s"$resourceId"
      )
      for {
        _ <- deltaClient
               .put[Json](s"/resources/$id1/test-schema/test-resource:$resourceId?indexing=sync", payload, Rick) {
                 (_, response) =>
                   response.status shouldEqual StatusCodes.Created
               }
        _ <- Task.sleep(100.millis)
        _ <- deltaClient.get[Json](s"/resources/$id1/test-schema", Rick) { (json, response) =>
               response.status shouldEqual StatusCodes.OK
               val received = json.asObject.value("_total").value.asNumber.value.toInt.value
               val expected = resourceId
               received shouldEqual expected
             }
      } yield succeed
    }
  }

  "create context" in {
    val payload = jsonContentOf("/kg/resources/simple-context.json")

    deltaClient.put[Json](s"/resources/$id1/_/test-resource:mycontext", payload, Rick) { (_, response) =>
      response.status shouldEqual StatusCodes.Created
    }
  }

  "create resource using the created context" in {
    val payload = jsonContentOf("/kg/resources/simple-resource-context.json")

    deltaClient.post[Json](s"/resources/$id1/", payload, Rick) { (_, response) =>
      response.status shouldEqual StatusCodes.Created
    }
  }

  "get a redirect to fusion if a `text/html` header is provided" in {

    deltaClient.get[String](
      s"/resources/$id1/_/test-resource:1",
      Rick,
      extraHeaders = List(Accept(MediaRange.One(`text/html`, 1f)))
    ) { (_, response) =>
      response.status shouldEqual StatusCodes.SeeOther
      response
        .header[Location]
        .value
        .uri
        .toString() shouldEqual s"https://bbp.epfl.ch/nexus/web/$id1/resources/test-resource:1"
    }(PredefinedFromEntityUnmarshallers.stringUnmarshaller)
  }

  "refreshing a resource" should {

    val Base    = "http://my-original-base.com/"
    val NewBase = "http://my-new-base.com/"

    val ResourceId     = "resource-with-type"
    val FullResourceId = s"$Base/$ResourceId"

    val ResourceType        = "my-type"
    val FullResourceType    = s"$Base$ResourceType"
    val NewFullResourceType = s"$NewBase$ResourceType"

    "create a project" in {
      adminDsl.createProject(orgId, projId3, kgDsl.projectJsonWithCustomBase(name = id3, base = Base), Rick)
    }

    "create resource using the created project" in {
      val payload =
        jsonContentOf("/kg/resources/simple-resource-with-type.json", "id" -> FullResourceId, "type" -> ResourceType)

      deltaClient.post[Json](s"/resources/$id3/", payload, Rick) { (_, response) =>
        response.status shouldEqual StatusCodes.Created
      }
    }

    "type should be expanded" in {
      deltaClient.get[Json](s"/resources/$id3/_/${UrlUtils.encode(FullResourceId)}", Rick) { (json, response) =>
        response.status shouldEqual StatusCodes.OK
        json should have(`@type`(FullResourceType))
      }
    }

    "update a project" in {
      for {
        _ <-
          adminDsl.updateProject(orgId, projId3, kgDsl.projectJsonWithCustomBase(name = id3, base = NewBase), Rick, 1)
      } yield succeed
    }

    "do a refresh" in {
      deltaClient.put[Json](s"/resources/$id3/_/${UrlUtils.encode(FullResourceId)}/refresh?rev=1", Json.Null, Rick) {
        (_, response) =>
          response.status shouldEqual StatusCodes.OK
      }
    }

    "type should be updated" in {
      deltaClient.get[Json](s"/resources/$id3/_/${UrlUtils.encode(FullResourceId)}", Rick) { (json, response) =>
        response.status shouldEqual StatusCodes.OK
        json should have(`@type`(NewFullResourceType))
      }
    }
  }
}
