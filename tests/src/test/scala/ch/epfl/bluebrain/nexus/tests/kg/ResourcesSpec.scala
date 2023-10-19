package ch.epfl.bluebrain.nexus.tests.kg

import akka.http.scaladsl.model.MediaTypes.`text/html`
import akka.http.scaladsl.model.headers.{Accept, Location, RawHeader}
import akka.http.scaladsl.model.{MediaRange, StatusCodes}
import akka.http.scaladsl.unmarshalling.PredefinedFromEntityUnmarshallers
import cats.implicits._
import ch.epfl.bluebrain.nexus.delta.kernel.utils.UrlUtils
import ch.epfl.bluebrain.nexus.testkit.{CirceEq, EitherValuable}
import ch.epfl.bluebrain.nexus.tests.Identity.Anonymous
import ch.epfl.bluebrain.nexus.tests.Identity.resources.{Morty, Rick}
import ch.epfl.bluebrain.nexus.tests.Optics.admin.{_constrainedBy, _rev}
import ch.epfl.bluebrain.nexus.tests.Optics.listing._total
import ch.epfl.bluebrain.nexus.tests.Optics.{filterKey, filterMetadataKeys}
import ch.epfl.bluebrain.nexus.tests.iam.types.Permission.Resources
import ch.epfl.bluebrain.nexus.tests.resources.SimpleResource
import ch.epfl.bluebrain.nexus.tests.{BaseSpec, Optics, SchemaPayload}
import io.circe.Json
import io.circe.optics.JsonPath.root
import monocle.Optional
import org.scalatest.matchers.{HavePropertyMatchResult, HavePropertyMatcher}

import java.net.URLEncoder

class ResourcesSpec extends BaseSpec with EitherValuable with CirceEq {

  private val orgId   = genId()
  private val projId1 = genId()
  private val projId2 = genId()
  private val projId3 = genId()
  private val id1     = s"$orgId/$projId1"
  private val id2     = s"$orgId/$projId2"
  private val id3     = s"$orgId/$projId3"

  private val IdLens: Optional[Json, String]   = root.`@id`.string
  private val TypeLens: Optional[Json, String] = root.`@type`.string

  private val varyHeader = RawHeader("Vary", "Accept,Accept-Encoding")

  private val resource1Id                                = "https://dev.nexus.test.com/simplified-resource/1"
  private def resource1Response(rev: Int, priority: Int) =
    SimpleResource.fetchResponse(Rick, id1, resource1Id, rev, priority)

  private def resource1AnnotatedSource(rev: Int, priority: Int) =
    SimpleResource.annotatedResource(Rick, id1, resource1Id, rev, priority)

  private def `@id`(expectedId: String) = HavePropertyMatcher[Json, String] { json =>
    val actualId = IdLens.getOption(json)
    HavePropertyMatchResult(
      actualId.contains(expectedId),
      "@id",
      expectedId,
      actualId.orNull
    )
  }

  private def `@type`(expectedType: String) = HavePropertyMatcher[Json, String] { json =>
    val actualType = TypeLens.getOption(json)
    HavePropertyMatchResult(
      actualType.contains(expectedType),
      "@type",
      expectedType,
      actualType.orNull
    )
  }

  override def beforeAll(): Unit = {
    super.beforeAll()
    val setup =
      for {
        _ <- createProjects(Rick, orgId, projId1, projId2)
        _ <- aclDsl.addPermission(s"/$id1", Morty, Resources.Read)
      } yield ()
    setup.accepted
  }

  "adding schema" should {
    "create a schema" in {
      val schemaPayload = SchemaPayload.loadSimple()

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
      val payload = SimpleResource.sourcePayload(resource1Id, 5)

      val id2      = URLEncoder.encode("https://dev.nexus.test.com/test-schema-imports", "UTF-8")
      val payload2 = SimpleResource.sourcePayload("https://dev.nexus.test.com/simplified-resource/a", 5)

      for {
        _ <- deltaClient.put[Json](s"/resources/$id1/test-schema/test-resource:1", payload, Rick) { (_, response) =>
               response.status shouldEqual StatusCodes.Created
             }
        _ <- deltaClient.put[Json](s"/resources/$id1/$id2/test-resource:a", payload2, Rick) { (_, response) =>
               response.status shouldEqual StatusCodes.Created
             }
      } yield succeed
    }

    "fail to fetch the resource when the user does not have access" in {
      deltaClient.get[Json](s"/resources/$id1/test-schema/test-resource:1", Anonymous) { (_, response) =>
        expectForbidden
        response.headers should not contain varyHeader
      }
    }

    "fail to fetch the original payload when the user does not have access" in {
      deltaClient.get[Json](s"/resources/$id1/test-schema/test-resource:1/source", Anonymous) { (_, response) =>
        expectForbidden
        response.headers should not contain varyHeader
      }
    }

    "fail to fetch the annotated original payload when the user does not have access" in {
      deltaClient.get[Json](s"/resources/$id1/test-schema/test-resource:1/source?annotate=true", Anonymous) {
        (_, response) =>
          expectForbidden
          response.headers should not contain varyHeader
      }
    }

    "fetch the resource wih metadata" in {
      deltaClient.get[Json](s"/resources/$id1/test-schema/test-resource:1", Morty) { (json, response) =>
        val expected = resource1Response(1, 5)
        response.status shouldEqual StatusCodes.OK
        filterMetadataKeys(json) should equalIgnoreArrayOrder(expected)
        response.headers should contain(varyHeader)
      }
    }

    "fetch the original payload" in {
      deltaClient.get[Json](s"/resources/$id1/test-schema/test-resource:1/source", Morty) { (json, response) =>
        val expected = SimpleResource.sourcePayload(resource1Id, 5)
        response.status shouldEqual StatusCodes.OK
        json should equalIgnoreArrayOrder(expected)
        response.headers should contain(varyHeader)
      }
    }

    "fetch the original payload with metadata" in {
      deltaClient.get[Json](s"/resources/$id1/test-schema/test-resource:1/source?annotate=true", Morty) {
        (json, response) =>
          response.status shouldEqual StatusCodes.OK
          val expected = resource1AnnotatedSource(1, 5)
          filterMetadataKeys(json) should equalIgnoreArrayOrder(expected)
          response.headers should contain(varyHeader)
      }
    }

    "fetch the original payload with unexpanded id with metadata" in {
      val payload = SimpleResource.sourcePayload("42", 5)

      for {
        _ <- deltaClient.post[Json](s"/resources/$id1/_/", payload, Rick) { (_, response) =>
               response.status shouldEqual StatusCodes.Created
             }
        _ <- deltaClient.get[Json](s"/resources/$id1/_/42/source?annotate=true", Morty) { (json, response) =>
               response.status shouldEqual StatusCodes.OK
               response.headers should contain(varyHeader)
               json should have(`@id`(s"42"))
             }
      } yield succeed
    }

    "fetch the original payload with generated id with metadata" in {
      val payload = SimpleResource.sourcePayload(5)

      var generatedId: String = ""

      for {
        _ <- deltaClient.post[Json](s"/resources/$id1/_/", payload, Rick) { (json, response) =>
               response.status shouldEqual StatusCodes.Created
               generatedId = Optics.`@id`.getOption(json).getOrElse(fail("could not find @id of created resource"))
               succeed
             }
        _ <- deltaClient.get[Json](s"/resources/$id1/_/${UrlUtils.encode(generatedId)}/source?annotate=true", Morty) {
               (json, response) =>
                 response.status shouldEqual StatusCodes.OK
                 response.headers should contain(varyHeader)
                 json should have(`@id`(generatedId))
             }
      } yield succeed
    }

    "return not found if a resource is missing" in {
      deltaClient.get[Json](s"/resources/$id1/test-schema/does-not-exist-resource:1/source?annotate=true", Morty) {
        (_, response) =>
          response.status shouldEqual StatusCodes.NotFound
          response.headers should not contain varyHeader
      }
    }

    "fail if the schema doesn't exist in the project" in {
      val payload = SimpleResource.sourcePayload(resource1Id, 3)

      deltaClient.put[Json](s"/resources/$id2/test-schema/test-resource:1", payload, Rick) { (_, response) =>
        response.status shouldEqual StatusCodes.NotFound
        response.headers should not contain varyHeader
      }
    }

    "fail if the payload contains nexus metadata fields (underscore fields)" in {
      val payload = SimpleResource
        .sourcePayload("1", 3)
        .deepMerge(json"""{"_self":  "http://delta/resources/path"}""")

      deltaClient.put[Json](s"/resources/$id2/_/test-resource:1", payload, Rick) { (_, response) =>
        response.status shouldEqual StatusCodes.BadRequest
        response.headers should not contain varyHeader
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
          "self"           -> resolverSelf(id2, "http://localhost/resolver"),
          "project-parent" -> s"${config.deltaUri}/projects/$id2"
        ): _*
      )

      deltaClient.get[Json](s"/resolvers/$id2/test-resolver", Rick) { (json, response) =>
        response.status shouldEqual StatusCodes.OK
        filterMetadataKeys(json) should equalIgnoreArrayOrder(expected)
      }
    }

    "wait for the cross-project resolver to be indexed" in {
      eventually {
        deltaClient.get[Json](s"/resolvers/$id2", Rick) { (json, response) =>
          response.status shouldEqual StatusCodes.OK
          _total.getOption(json).value shouldEqual 2L
        }
      }
    }

    s"resolves a resource in project '$id1' through project '$id2' resolvers" in {
      for {
        _ <- deltaClient.get[Json](s"/schemas/$id1/test-schema", Rick) { (json, response1) =>
               response1.status shouldEqual StatusCodes.OK
               runIO {
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
      val payload = SimpleResource.sourcePayload(resource1Id, 3)
      deltaClient.put[Json](s"/resources/$id2/test-schema/test-resource:1", payload, Rick) { expectCreated }
    }
  }

  "updating a resource" should {
    "send the update" in {
      val payload = SimpleResource.sourcePayload(resource1Id, 3)
      deltaClient.put[Json](s"/resources/$id1/test-schema/test-resource:1?rev=1", payload, Rick) { expectOk }
    }

    "fetch the update" in {
      val expected = resource1Response(2, 3)

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
      val expected = resource1Response(1, 5)

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
          val expected = resource1AnnotatedSource(1, 5)
          response.status shouldEqual StatusCodes.OK
          filterMetadataKeys(json) should equalIgnoreArrayOrder(expected)
      }
    }

    "allow to change the schema" in {
      val resourceId       = "schemaChange"
      val payload          = SimpleResource.sourcePayload(resourceId, 4)
      val newSchemaPayload = SchemaPayload.loadSimpleNoId()

      val postResourceAndNewSchema =
        deltaClient.put[Json](s"/schemas/$id1/new-schema", newSchemaPayload, Rick) { expectCreated } >>
          deltaClient.put[Json](s"/resources/$id1/test-schema/$resourceId", payload, Rick) { expectCreated } >>
          deltaClient.get[Json](s"/resources/$id1/test-schema/$resourceId", Rick) { expectOk }

      val updateResourceAndSchema =
        deltaClient.put[Json](s"/resources/$id1/new-schema/$resourceId?rev=1", payload, Rick)(_)

      postResourceAndNewSchema >>
        updateResourceAndSchema { (json, response) =>
          response.status shouldEqual StatusCodes.OK
          _rev.getOption(json) should contain(2)
          _constrainedBy.getOption(json) should contain("http://delta:8080/v1/resources/" + id1 + "/_/new-schema")
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
      val expectedTag1 = resource1Response(2, 3)
      val expectedTag2 = resource1Response(1, 5)
      val expectedTag3 = resource1Response(5, 3) deepMerge Json.obj("_deprecated" -> Json.True)

      for {
        _ <-
          deltaClient.get[Json](s"/resources/$id1/test-schema/test-resource:1?tag=v1.0.1", Morty) { (json, response) =>
            response.status shouldEqual StatusCodes.OK
            filterMetadataKeys(json) should equalIgnoreArrayOrder(expectedTag1)
          }
        _ <- deltaClient.get[Json](s"/resources/$id1/_/test-resource:1?tag=v1.0.0", Morty) { (json, response) =>
               response.status shouldEqual StatusCodes.OK
               filterMetadataKeys(json) should equalIgnoreArrayOrder(expectedTag2)
             }
        _ <- deltaClient.get[Json](s"/resources/$id1/_/test-resource:1?tag=v1.0.2", Morty) { (json, response) =>
               response.status shouldEqual StatusCodes.OK
               filterMetadataKeys(json) should equalIgnoreArrayOrder(expectedTag3)
             }
      } yield succeed
    }

    "fetch tagged original payload with metadata" in {
      deltaClient.get[Json](s"/resources/$id1/test-schema/test-resource:1/source?tag=v1.0.1&annotate=true", Rick) {
        (json, response) =>
          val expected = resource1AnnotatedSource(2, 3)
          response.status shouldEqual StatusCodes.OK
          filterMetadataKeys(json) should equalIgnoreArrayOrder(expected)
      }
    }

    "delete a tag" in {
      deltaClient
        .delete[Json](s"/resources/$id1/_/test-resource:1/tags/v1.0.1?rev=6", Rick) { (_, response) =>
          response.status shouldEqual StatusCodes.OK
        }
    }
  }

  "check consistency of responses" in {
    (2 to 100).toList.traverse { resourceId =>
      val payload = SimpleResource.sourcePayload(s"https://dev.nexus.test.com/simplified-resource/$resourceId", 3)
      for {
        _ <- deltaClient
               .put[Json](s"/resources/$id1/test-schema/test-resource:$resourceId?indexing=sync", payload, Rick) {
                 expectCreated
               }
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

  "fetch remote contexts for the created resource" in {
    deltaClient.get[Json](s"/resources/$id1/_/myid/remote-contexts", Morty) { (json, response) =>
      response.status shouldEqual StatusCodes.OK
      val expected =
        json"""
          {
          "@context": "https://bluebrain.github.io/nexus/contexts/remote-contexts.json",
          "remoteContexts": [
            {
              "@type": "ProjectRemoteContextRef",
              "iri": "https://dev.nexus.test.com/simplified-resource/mycontext",
              "resource": {
                "id": "https://dev.nexus.test.com/simplified-resource/mycontext",
                "project": "$id1",
                "rev": 1
              }
            }
          ]
        }"""

      json shouldEqual expected
    }
  }

  "get a redirect to fusion if a `text/html` header is provided" in {

    deltaClient.get[String](
      s"/resources/$id1/_/test-resource:1",
      Morty,
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
