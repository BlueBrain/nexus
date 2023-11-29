package ch.epfl.bluebrain.nexus.tests.kg

import akka.http.scaladsl.model.MediaTypes.`text/html`
import akka.http.scaladsl.model.headers.{Accept, Location, RawHeader}
import akka.http.scaladsl.model.{HttpResponse, MediaRange, StatusCodes}
import akka.http.scaladsl.unmarshalling.PredefinedFromEntityUnmarshallers
import cats.effect.IO

import ch.epfl.bluebrain.nexus.delta.kernel.utils.UrlUtils
import ch.epfl.bluebrain.nexus.tests.Identity.Anonymous
import ch.epfl.bluebrain.nexus.tests.Identity.resources.{Morty, Rick}
import ch.epfl.bluebrain.nexus.tests.Optics.admin.{_constrainedBy, _deprecated}
import ch.epfl.bluebrain.nexus.tests.Optics.listing._total
import ch.epfl.bluebrain.nexus.tests.Optics.{_rev, filterKey, filterMetadataKeys}
import ch.epfl.bluebrain.nexus.tests.iam.types.Permission.Resources
import ch.epfl.bluebrain.nexus.tests.resources.SimpleResource
import ch.epfl.bluebrain.nexus.tests.{BaseIntegrationSpec, Optics, SchemaPayload}
import io.circe.{Json, JsonObject}
import io.circe.optics.JsonPath.root
import monocle.Optional
import org.scalatest.Assertion
import org.scalatest.matchers.{HavePropertyMatchResult, HavePropertyMatcher}
import org.testcontainers.utility.Base58.randomString

import java.net.URLEncoder
import cats.implicits._

class ResourcesSpec extends BaseIntegrationSpec {

  private val orgId    = genId()
  private val projId1  = genId()
  private val projId2  = genId()
  private val projId3  = genId()
  private val project1 = s"$orgId/$projId1"
  private val project2 = s"$orgId/$projId2"
  private val project3 = s"$orgId/$projId3"

  private val IdLens: Optional[Json, String]   = root.`@id`.string
  private val TypeLens: Optional[Json, String] = root.`@type`.string

  private val varyHeader = RawHeader("Vary", "Accept,Accept-Encoding")

  private val resource1Id                                = "https://dev.nexus.test.com/simplified-resource/1"
  private def resource1Response(rev: Int, priority: Int) =
    SimpleResource.fetchResponse(Rick, project1, resource1Id, rev, priority)

  private def resource1AnnotatedSource(rev: Int, priority: Int) =
    SimpleResource.annotatedResource(Rick, project1, resource1Id, rev, priority)

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
    (createProjects(Rick, orgId, projId1, projId2) >>
      aclDsl.addPermission(s"/$project1", Morty, Resources.Read)).accepted
    ()
  }

  "adding schema" should {
    "create a schema" in {
      val schemaPayload = SchemaPayload.loadSimple().accepted

      deltaClient.put[Json](s"/schemas/$project1/test-schema", schemaPayload, Rick) { (_, response) =>
        response.status shouldEqual StatusCodes.Created
      }
    }

    "creating a schema with property shape" in {
      val schemaPayload = jsonContentOf("kg/schemas/simple-schema-prop-shape.json")

      deltaClient.post[Json](s"/schemas/$project1", schemaPayload, Rick) { (_, response) =>
        response.status shouldEqual StatusCodes.Created
      }
    }

    "creating a schema that imports the property shape schema" in {
      val schemaPayload = jsonContentOf("kg/schemas/simple-schema-imports.json")

      eventually {
        deltaClient.post[Json](s"/schemas/$project1", schemaPayload, Rick) { (_, response) =>
          response.status shouldEqual StatusCodes.Created
        }
      }
    }
  }

  "creating a resource" should {

    "fail if created with the same id as the created schema" in {
      deltaClient.put[Json](s"/resources/$project1/_/test-schema", Json.obj(), Rick) { (json, response) =>
        response.status shouldEqual StatusCodes.Conflict
        json shouldEqual jsonContentOf(
          "kg/resources/resource-already-exists-rejection.json",
          "id"      -> "https://dev.nexus.test.com/test-schema",
          "project" -> project1
        )
      }
    }

    "succeed if the payload is correct" in {
      val payload = SimpleResource.sourcePayload(resource1Id, 5).accepted

      val id2      = URLEncoder.encode("https://dev.nexus.test.com/test-schema-imports", "UTF-8")
      val payload2 = SimpleResource.sourcePayload("https://dev.nexus.test.com/simplified-resource/a", 5).accepted

      for {
        _ <-
          deltaClient.put[Json](s"/resources/$project1/test-schema/test-resource:1", payload, Rick) { (_, response) =>
            response.status shouldEqual StatusCodes.Created
          }
        _ <- deltaClient.put[Json](s"/resources/$project1/$id2/test-resource:a", payload2, Rick) { (_, response) =>
               response.status shouldEqual StatusCodes.Created
             }
      } yield succeed
    }

    "fail to fetch the resource when the user does not have access" in {
      deltaClient.get[Json](s"/resources/$project1/test-schema/test-resource:1", Anonymous) { (_, response) =>
        expectForbidden
        response.headers should not contain varyHeader
      }
    }

    "fail to fetch the original payload when the user does not have access" in {
      deltaClient.get[Json](s"/resources/$project1/test-schema/test-resource:1/source", Anonymous) { (_, response) =>
        expectForbidden
        response.headers should not contain varyHeader
      }
    }

    "fail to fetch the annotated original payload when the user does not have access" in {
      deltaClient.get[Json](s"/resources/$project1/test-schema/test-resource:1/source?annotate=true", Anonymous) {
        (_, response) =>
          expectForbidden
          response.headers should not contain varyHeader
      }
    }

    "fetch the resource wih metadata" in {
      val expected = resource1Response(1, 5).accepted

      deltaClient.get[Json](s"/resources/$project1/test-schema/test-resource:1", Morty) { (json, response) =>
        response.status shouldEqual StatusCodes.OK
        filterMetadataKeys(json) should equalIgnoreArrayOrder(expected)
        response.headers should contain(varyHeader)
      }
    }

    "fetch the original payload" in {
      val expected = SimpleResource.sourcePayload(resource1Id, 5).accepted

      deltaClient.get[Json](s"/resources/$project1/test-schema/test-resource:1/source", Morty) { (json, response) =>
        response.status shouldEqual StatusCodes.OK
        json should equalIgnoreArrayOrder(expected)
        response.headers should contain(varyHeader)
      }
    }

    "fetch the original payload through a resolver" in {
      val expected = SimpleResource.sourcePayload(resource1Id, 5).accepted
      deltaClient.get[Json](s"/resolvers/$project1/_/test-resource:1/source", Morty) { (json, response) =>
        response.status shouldEqual StatusCodes.OK
        json should equalIgnoreArrayOrder(expected)
      }
    }

    "fetch the original payload with metadata" in {
      val expected = resource1AnnotatedSource(1, 5).accepted
      deltaClient.get[Json](s"/resources/$project1/test-schema/test-resource:1/source?annotate=true", Morty) {
        (json, response) =>
          response.status shouldEqual StatusCodes.OK
          filterMetadataKeys(json) should equalIgnoreArrayOrder(expected)
          response.headers should contain(varyHeader)
      }
    }

    "fetch the original payload with unexpanded id with metadata" in {
      val payload = SimpleResource.sourcePayload("42", 5).accepted

      for {
        _ <- deltaClient.post[Json](s"/resources/$project1/_/", payload, Rick) { (_, response) =>
               response.status shouldEqual StatusCodes.Created
             }
        _ <- deltaClient.get[Json](s"/resources/$project1/_/42/source?annotate=true", Morty) { (json, response) =>
               response.status shouldEqual StatusCodes.OK
               response.headers should contain(varyHeader)
               json should have(`@id`(s"42"))
             }
      } yield succeed
    }

    "fetch the original payload with generated id with metadata" in {
      val payload = SimpleResource.sourcePayload(5).accepted

      var generatedId: String = ""

      for {
        _ <- deltaClient.post[Json](s"/resources/$project1/_/", payload, Rick) { (json, response) =>
               response.status shouldEqual StatusCodes.Created
               generatedId = Optics.`@id`.getOption(json).getOrElse(fail("could not find @id of created resource"))
               succeed
             }
        _ <- deltaClient
               .get[Json](s"/resources/$project1/_/${UrlUtils.encode(generatedId)}/source?annotate=true", Morty) {
                 (json, response) =>
                   response.status shouldEqual StatusCodes.OK
                   response.headers should contain(varyHeader)
                   json should have(`@id`(generatedId))
               }
      } yield succeed
    }

    "return not found if a resource is missing" in {
      deltaClient
        .get[Json](s"/resources/$project1/test-schema/does-not-exist-resource:1/source?annotate=true", Morty) {
          (_, response) =>
            response.status shouldEqual StatusCodes.NotFound
            response.headers should not contain varyHeader
        }
    }

    "fail if the schema doesn't exist in the project" in {
      val payload = SimpleResource.sourcePayload(resource1Id, 3).accepted

      deltaClient.put[Json](s"/resources/$project2/test-schema/test-resource:1", payload, Rick) { (_, response) =>
        response.status shouldEqual StatusCodes.NotFound
        response.headers should not contain varyHeader
      }
    }

    "fail if the payload contains nexus metadata fields (underscore fields)" in {
      val payload = SimpleResource
        .sourcePayload("1", 3)
        .accepted
        .deepMerge(json"""{"_self":  "http://delta/resources/path"}""")

      deltaClient.put[Json](s"/resources/$project2/_/test-resource:1", payload, Rick) { (_, response) =>
        response.status shouldEqual StatusCodes.BadRequest
        response.headers should not contain varyHeader
      }
    }
  }

  "cross-project resolvers" should {
    val resolverPayload =
      jsonContentOf(
        "kg/resources/cross-project-resolver.json",
        replacements(Rick, "project" -> project1): _*
      )

    "fail to create a cross-project-resolver for proj2 if identities are missing" in {
      deltaClient.post[Json](s"/resolvers/$project2", filterKey("identities")(resolverPayload), Rick) { (_, response) =>
        response.status shouldEqual StatusCodes.BadRequest
      }
    }

    "create a cross-project-resolver for proj2" in {
      deltaClient.post[Json](s"/resolvers/$project2", resolverPayload, Rick) { (_, response) =>
        response.status shouldEqual StatusCodes.Created
      }
    }

    "update a cross-project-resolver for proj2" in {
      val updated = resolverPayload deepMerge Json.obj("priority" -> Json.fromInt(20))
      eventually {
        deltaClient.put[Json](s"/resolvers/$project2/test-resolver?rev=1", updated, Rick) { (_, response) =>
          response.status shouldEqual StatusCodes.OK
        }
      }
    }

    "fetch the update" in {
      val expected = jsonContentOf(
        "kg/resources/cross-project-resolver-updated-resp.json",
        replacements(
          Rick,
          "project"        -> project1,
          "self"           -> resolverSelf(project2, "http://localhost/resolver"),
          "project-parent" -> s"${config.deltaUri}/projects/$project2"
        ): _*
      )

      deltaClient.get[Json](s"/resolvers/$project2/test-resolver", Rick) { (json, response) =>
        response.status shouldEqual StatusCodes.OK
        filterMetadataKeys(json) should equalIgnoreArrayOrder(expected)
      }
    }

    "wait for the cross-project resolver to be indexed" in {
      eventually {
        deltaClient.get[Json](s"/resolvers/$project2", Rick) { (json, response) =>
          response.status shouldEqual StatusCodes.OK
          _total.getOption(json).value shouldEqual 2L
        }
      }
    }

    s"resolves a resource in project '$project1' through project '$project2' resolvers" in {
      for {
        _ <- deltaClient.get[Json](s"/schemas/$project1/test-schema", Rick) { (json, response1) =>
               response1.status shouldEqual StatusCodes.OK
               runIO {
                 for {
                   _ <-
                     deltaClient.get[Json](s"/resolvers/$project2/_/test-schema", Rick) { (jsonResolved, response2) =>
                       response2.status shouldEqual StatusCodes.OK
                       jsonResolved should equalIgnoreArrayOrder(json)
                     }
                   _ <- deltaClient.get[Json](s"/resolvers/$project2/test-resolver/test-schema", Rick) {
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

    s"return not found when attempting to resolve a non-existing resource in project '$project1' through project '$project2' resolvers" in {
      for {
        _ <- deltaClient.get[Json](s"/resolvers/$project2/test-resolver/test-schema-2", Rick) { (_, response) =>
               response.status shouldEqual StatusCodes.NotFound
             }
        _ <- deltaClient.get[Json](s"/resolvers/$project2/_/test-schema-2", Rick) { (_, response) =>
               response.status shouldEqual StatusCodes.NotFound
             }
      } yield succeed
    }

    "resolve schema from the other project" in {
      val payload = SimpleResource.sourcePayload(resource1Id, 3).accepted
      deltaClient.put[Json](s"/resources/$project2/test-schema/test-resource:1", payload, Rick) { expectCreated }
    }
  }

  "updating a resource" should {
    "send the update" in {
      for {
        payload <- SimpleResource.sourcePayload(resource1Id, 3)
        _       <- deltaClient.put[Json](s"/resources/$project1/test-schema/test-resource:1?rev=1", payload, Rick) {
                     (json, response) =>
                       response.status shouldEqual StatusCodes.OK
                       _rev.getOption(json).value shouldEqual 2
                   }
        // Sending the same update should not create a new revision
        _       <- deltaClient.put[Json](s"/resources/$project1/_/test-resource:1?rev=2", payload, Rick) { (json, response) =>
                     response.status shouldEqual StatusCodes.OK
                     _rev.getOption(json).value shouldEqual 2
                   }
      } yield succeed
    }

    "fetch the update" in {
      val expected = resource1Response(2, 3).accepted

      List(
        s"/resources/$project1/test-schema/test-resource:1",
        s"/resources/$project1/_/test-resource:1"
      ).parTraverse { url =>
        deltaClient.get[Json](url, Rick) { (json, response) =>
          response.status shouldEqual StatusCodes.OK
          filterMetadataKeys(json) should equalIgnoreArrayOrder(expected)
        }
      }
    }

    "fetch previous revision" in {
      val expected = resource1Response(1, 5).accepted

      List(
        s"/resources/$project1/test-schema/test-resource:1?rev=1",
        s"/resources/$project1/_/test-resource:1?rev=1"
      ).parTraverse { url =>
        deltaClient.get[Json](url, Rick) { (json, response) =>
          response.status shouldEqual StatusCodes.OK
          filterMetadataKeys(json) should equalIgnoreArrayOrder(expected)
        }
      }
    }

    "fetch previous revision original payload with metadata" in {
      val expected = resource1AnnotatedSource(1, 5).accepted
      deltaClient.get[Json](s"/resources/$project1/test-schema/test-resource:1/source?rev=1&annotate=true", Rick) {
        (json, response) =>
          response.status shouldEqual StatusCodes.OK
          filterMetadataKeys(json) should equalIgnoreArrayOrder(expected)
      }
    }

    "allow to change the schema" in {
      val payload = SimpleResource.sourcePayload(4).accepted

      def updateResourceAndSchema: (String, String) => ((Json, HttpResponse) => Assertion) => IO[Assertion] =
        (id, schema) =>
          deltaClient.put[Json](s"/resources/$project1/$schema/$id?rev=1", payload, Rick) {
            expectOk
          } >>
            deltaClient.get[Json](s"/resources/$project1/$schema/$id?rev=2", Rick)(_)

      givenASchemaIn(project1) { firstSchema =>
        givenASchemaIn(project1) { newSchema =>
          givenAResourceWithSchema(project1, firstSchema) { id =>
            val expectedSchema = "http://delta:8080/v1/resources/" + project1 + s"/_/$newSchema"
            updateResourceAndSchema(id, newSchema) { (json, response) =>
              response.status shouldEqual StatusCodes.OK
              _constrainedBy.getOption(json) should contain(expectedSchema)
            }.accepted
          }
        }
      }
    }

    "allow updating with a tag" in {
      val payload = SimpleResource.sourcePayload(4).accepted
      val tag     = genString()

      givenASchemaIn(project1) { schema =>
        givenAResourceWithSchema(project1, schema) { id =>
          val updateWithTag =
            deltaClient.put[Json](s"/resources/$project1/$schema/$id?rev=1&tag=$tag", payload, Rick)(expectOk)
          val fetchByTag    = deltaClient.get[Json](s"/resources/$project1/$schema/$id?tag=$tag", Rick)(expectOk)

          (updateWithTag >> fetchByTag).accepted
        }
      }
    }
  }

  "tagging a resource" should {
    "create a tag" in {
      for {
        _ <- deltaClient
               .post[Json](
                 s"/resources/$project1/test-schema/test-resource:1/tags?rev=2&indexing=sync",
                 tag("v1.0.0", 1),
                 Rick
               ) { (_, response) =>
                 response.status shouldEqual StatusCodes.Created
               }
        _ <-
          deltaClient
            .post[Json](s"/resources/$project1/_/test-resource:1/tags?rev=3&indexing=sync", tag("v1.0.1", 2), Rick) {
              (_, response) =>
                response.status shouldEqual StatusCodes.Created
            }
        _ <- deltaClient
               .delete[Json](s"/resources/$project1/_/test-resource:1?rev=4&indexing=sync", Rick) { (_, response) =>
                 response.status shouldEqual StatusCodes.OK
               }
        _ <-
          deltaClient
            .post[Json](s"/resources/$project1/_/test-resource:1/tags?rev=5&indexing=sync", tag("v1.0.2", 5), Rick) {
              (_, response) =>
                response.status shouldEqual StatusCodes.Created
            }
      } yield succeed
    }

    "fetch a tagged value" in {
      val expectedTag1 = resource1Response(2, 3).accepted
      val expectedTag2 = resource1Response(1, 5).accepted
      val expectedTag3 = resource1Response(5, 3).accepted deepMerge Json.obj("_deprecated" -> Json.True)

      for {
        _ <-
          deltaClient.get[Json](s"/resources/$project1/test-schema/test-resource:1?tag=v1.0.1", Morty) {
            (json, response) =>
              response.status shouldEqual StatusCodes.OK
              filterMetadataKeys(json) should equalIgnoreArrayOrder(expectedTag1)
          }
        _ <- deltaClient.get[Json](s"/resources/$project1/_/test-resource:1?tag=v1.0.0", Morty) { (json, response) =>
               response.status shouldEqual StatusCodes.OK
               filterMetadataKeys(json) should equalIgnoreArrayOrder(expectedTag2)
             }
        _ <- deltaClient.get[Json](s"/resources/$project1/_/test-resource:1?tag=v1.0.2", Morty) { (json, response) =>
               response.status shouldEqual StatusCodes.OK
               filterMetadataKeys(json) should equalIgnoreArrayOrder(expectedTag3)
             }
      } yield succeed
    }

    "fetch tagged original payload with metadata" in {
      deltaClient
        .get[Json](s"/resources/$project1/test-schema/test-resource:1/source?tag=v1.0.1&annotate=true", Rick) {
          (json, response) =>
            val expected = resource1AnnotatedSource(2, 3).accepted
            response.status shouldEqual StatusCodes.OK
            filterMetadataKeys(json) should equalIgnoreArrayOrder(expected)
        }
    }

    "delete a tag" in {
      deltaClient
        .delete[Json](s"/resources/$project1/_/test-resource:1/tags/v1.0.1?rev=6", Rick) { (_, response) =>
          response.status shouldEqual StatusCodes.OK
        }
    }
  }

  "deprecating a resource" should {

    "fail without authorization" in {
      givenAResource(project1) { id =>
        deltaClient.delete[Json](s"/resources/$project1/_/$id?rev=1", Anonymous) { expectForbidden }.accepted
      }
    }

    "succeed" in {
      givenAResource(project1) { id =>
        val deprecate       = deltaClient.delete(s"/resources/$project1/_/$id?rev=1", Rick) { expectOk }
        val fetchDeprecated = deltaClient.get[Json](s"/resources/$project1/_/$id", Rick) { (json, _) =>
          _deprecated.getOption(json) should contain(true)
        }
        (deprecate >> fetchDeprecated).accepted
      }
    }

    "lead to an empty resource listing" in {
      givenAResource(project1) { id =>
        val deprecate         = deltaClient.delete(s"/resources/$project1/_/$id?rev=1", Rick) { expectOk }
        val fetchEmptyListing =
          deltaClient.get[Json](s"/resources/$project1?locate=$id", Rick) { (json, _) =>
            _total.getOption(json) should contain(0)
          }
        deprecate.accepted
        eventually { fetchEmptyListing }
      }
    }

  }

  "undeprecating a resource" should {

    "fail without authorization" in {
      givenADeprecatedResource(project1) { id =>
        deltaClient
          .put(s"/resources/$project1/_/$id/undeprecate?rev=2", JsonObject.empty.toJson, Anonymous) {
            expectForbidden
          }
          .accepted
      }
    }

    "succeed" in {
      givenADeprecatedResource(project1) { id =>
        val undeprecate       =
          deltaClient.put(s"/resources/$project1/_/$id/undeprecate?rev=2", JsonObject.empty.toJson, Rick) { expectOk }
        val fetchUndeprecated = deltaClient.get[Json](s"/resources/$project1/_/$id", Rick) { case (json, _) =>
          _deprecated.getOption(json) should contain(false)
        }
        (undeprecate >> fetchUndeprecated).accepted
      }
    }

    "allow finding an undeprecated resource in the listing" in {
      givenADeprecatedResource(project1) { id =>
        val undeprecate  =
          deltaClient.put(s"/resources/$project1/_/$id/undeprecate?rev=2", JsonObject.empty.toJson, Rick) { expectOk }
        val fetchListing =
          deltaClient.get[Json](s"/resources/$project1?locate=$id", Rick) { (json, _) =>
            _total.getOption(json) should contain(1)
          }
        undeprecate.accepted
        eventually { fetchListing }
      }
    }

  }

  "check consistency of responses" in {
    (2 to 100).toList.traverse { resourceId =>
      val payload =
        SimpleResource.sourcePayload(s"https://dev.nexus.test.com/simplified-resource/$resourceId", 3).accepted
      for {
        _ <-
          deltaClient
            .put[Json](s"/resources/$project1/test-schema/test-resource:$resourceId?indexing=sync", payload, Rick) {
              expectCreated
            }
        _ <- deltaClient.get[Json](s"/resources/$project1/test-schema", Rick) { (json, response) =>
               response.status shouldEqual StatusCodes.OK
               val received = json.asObject.value("_total").value.asNumber.value.toInt.value
               val expected = resourceId
               received shouldEqual expected
             }
      } yield succeed
    }
  }

  "create a resource with context" in {
    val contextId             = "https://dev.nexus.test.com/simplified-resource/mycontext"
    val contextPayload        = json"""{ "@context": { "@base": "http://example.com/base/" } }"""
    val contextPayloadUpdated =
      json"""{ "@context": { "@base": "http://example.com/base/", "prefix": "https://bbp.epfl.ch/prefix" } }"""

    val resourcePayload = json"""{"@context": "$contextId",
                                   "@id": "myid",
                                   "@type": "http://example.com/type"
                                  }"""

    for {
      _ <- deltaClient.put[Json](s"/resources/$project1/_/test-resource:mycontext", contextPayload, Rick) {
             expectCreated
           }
      _ <- deltaClient.post[Json](s"/resources/$project1/", resourcePayload, Rick) { expectCreated }
      // No refresh should be performed as nothing changed
      _ <- deltaClient.put[Json](s"/resources/$project1/_/myid/refresh", Json.Null, Rick) { (json, response) =>
             response.status shouldEqual StatusCodes.OK
             _rev.getOption(json).value shouldEqual 1
           }
      _ <- deltaClient.put[Json](s"/resources/$project1/_/test-resource:mycontext?rev=1", contextPayloadUpdated, Rick) {
             (json, response) =>
               response.status shouldEqual StatusCodes.OK
               _rev.getOption(json).value shouldEqual 2
           }
      _ <- deltaClient.put[Json](s"/resources/$project1/_/myid/refresh", Json.Null, Rick) { (json, response) =>
             response.status shouldEqual StatusCodes.OK
             _rev.getOption(json).value shouldEqual 2
           }
    } yield succeed
  }

  "fetch remote contexts for the created resource" in {
    deltaClient.get[Json](s"/resources/$project1/_/myid/remote-contexts", Morty) { (json, response) =>
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
                "project": "$project1",
                "rev": 2
              }
            }
          ]
        }"""

      json shouldEqual expected
    }
  }

  "get a redirect to fusion if a `text/html` header is provided" in {

    deltaClient.get[String](
      s"/resources/$project1/_/test-resource:1",
      Morty,
      extraHeaders = List(Accept(MediaRange.One(`text/html`, 1f)))
    ) { (_, response) =>
      response.status shouldEqual StatusCodes.SeeOther
      response
        .header[Location]
        .value
        .uri
        .toString() shouldEqual s"https://bbp.epfl.ch/nexus/web/$project1/resources/test-resource:1"
    }(PredefinedFromEntityUnmarshallers.stringUnmarshaller)
  }

  "refreshing a resource" should {

    val Base    = "http://my-original-base.com/"
    val NewBase = "http://my-new-base.com/"

    val ResourceId     = "resource-with-type"
    val FullResourceId = s"$Base/$ResourceId"
    val idEncoded      = UrlUtils.encode(FullResourceId)

    val ResourceType        = "my-type"
    val FullResourceType    = s"$Base$ResourceType"
    val NewFullResourceType = s"$NewBase$ResourceType"

    "create a project" in {
      val payload = kgDsl.projectJsonWithCustomBase(name = project3, base = Base).accepted
      adminDsl.createProject(orgId, projId3, payload, Rick)
    }

    "create resource using the created project" in {
      val payload =
        jsonContentOf("kg/resources/simple-resource-with-type.json", "id" -> FullResourceId, "type" -> ResourceType)
      deltaClient.post[Json](s"/resources/$project3/", payload, Rick) { expectCreated }
    }

    "type should be expanded" in {
      deltaClient.get[Json](s"/resources/$project3/_/$idEncoded", Rick) { (json, response) =>
        response.status shouldEqual StatusCodes.OK
        json should have(`@type`(FullResourceType))
      }
    }

    "update a project" in {
      for {
        project <- kgDsl.projectJsonWithCustomBase(name = project3, base = NewBase)
        _       <-
          adminDsl.updateProject(
            orgId,
            projId3,
            project,
            Rick,
            1
          )
      } yield succeed
    }

    "do a refresh" in {
      deltaClient
        .put[Json](s"/resources/$project3/_/$idEncoded/refresh?rev=1", Json.Null, Rick) { expectOk }
    }

    "type should be updated" in {
      deltaClient.get[Json](s"/resources/$project3/_/$idEncoded", Rick) { (json, response) =>
        response.status shouldEqual StatusCodes.OK
        json should have(`@type`(NewFullResourceType))
      }
    }
  }

  "updating the schema of a resource" should {

    "succeed" in {
      givenASchemaIn(project1) { firstSchema =>
        givenASchemaIn(project1) { newSchema =>
          givenAResourceWithSchema(project1, firstSchema) { id =>
            deltaClient
              .put[Json](s"/resources/$project1/$newSchema/$id/update-schema", Json.Null, Rick) { (_, response) =>
                response.status shouldEqual StatusCodes.OK
              }
              .accepted

            val newSchemaId = "http://delta:8080/v1/resources/" + project1 + s"/_/$newSchema"

            deltaClient
              .get[Json](s"/resources/$project1/$newSchema/$id", Rick) { (json, _) =>
                _constrainedBy.getOption(json) should contain(newSchemaId)
              }
              .accepted
          }
        }
      }
    }
  }

  "uploading a payload too large" should {

    "fail with the appropriate message" in {
      val value   = randomString(270000)
      val payload = json"""{ "value": "$value" }"""
      deltaClient.post[Json](s"/resources/$project1/", payload, Rick) { (json, response) =>
        response.status shouldEqual StatusCodes.PayloadTooLarge
        Optics.`@type`.getOption(json) shouldEqual Some("PayloadTooLarge")
      }
    }

  }

  private def givenAResourceWithSchemaAndTag(projectRef: String, schema: Option[String], tag: Option[String])(
      assertion: String => Assertion
  ): Assertion = {
    val resourceName  = genString()
    val payload       = SimpleResource
      .sourcePayload(5)
      .accepted
      .deepMerge(json"""{"@id": "$resourceName"}""")
    val schemaSegment = schema.getOrElse("_")
    val tagParameter  = tag.map(t => s"?tag=$t").getOrElse("")

    deltaClient
      .post[Json](s"/resources/$projectRef/$schemaSegment$tagParameter", payload, Rick) { (_, response) =>
        response.status shouldEqual StatusCodes.Created
      }
      .accepted

    assertion(resourceName)
  }

  private def givenAResourceWithSchema(projectRef: String, schema: String)(
      assertion: String => Assertion
  ): Assertion =
    givenAResourceWithSchemaAndTag(projectRef, schema.some, none) { assertion }

  private def givenAResource(projectRef: String)(assertion: String => Assertion): Assertion =
    givenAResourceWithSchemaAndTag(projectRef, none, none) { assertion }

  private def givenADeprecatedResource(projectRef: String)(assertion: String => Assertion): Assertion =
    givenAResource(projectRef) { id =>
      deltaClient
        .delete[Json](s"/resources/$projectRef/_/$id?rev=1", Rick) { (json, response) =>
          response.status shouldEqual StatusCodes.OK
          _deprecated.getOption(json) should contain(true)
        }
        .accepted
      assertion(id)
    }

  private def givenASchemaIn(projectRef: String)(assertion: String => Assertion) = {
    val schemaName    = genString()
    val schemaPayload = SchemaPayload.loadSimpleNoId().accepted

    deltaClient
      .put[Json](s"/schemas/$projectRef/$schemaName", schemaPayload, Rick) { (_, response) =>
        response.status shouldEqual StatusCodes.Created
      }
      .accepted

    assertion(schemaName)
  }

}
