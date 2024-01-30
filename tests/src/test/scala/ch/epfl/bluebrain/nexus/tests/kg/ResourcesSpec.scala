package ch.epfl.bluebrain.nexus.tests.kg

import akka.http.scaladsl.model.MediaTypes.`text/html`
import akka.http.scaladsl.model.headers.{Accept, Location, RawHeader}
import akka.http.scaladsl.model.{HttpResponse, MediaRange, StatusCodes}
import akka.http.scaladsl.unmarshalling.PredefinedFromEntityUnmarshallers
import cats.effect.IO
import cats.implicits._
import ch.epfl.bluebrain.nexus.delta.kernel.utils.UrlUtils
import ch.epfl.bluebrain.nexus.testkit.scalatest.ResourceMatchers.deprecated
import ch.epfl.bluebrain.nexus.tests.Identity.{Anonymous, ServiceAccount}
import ch.epfl.bluebrain.nexus.tests.Identity.resources.{Morty, Rick}
import ch.epfl.bluebrain.nexus.tests.Optics.admin._constrainedBy
import ch.epfl.bluebrain.nexus.tests.Optics.listing._total
import ch.epfl.bluebrain.nexus.tests.Optics.{_rev, filterKey, filterMetadataKeys}
import ch.epfl.bluebrain.nexus.tests.admin.ProjectPayload
import ch.epfl.bluebrain.nexus.tests.iam.types.Permission.Resources
import ch.epfl.bluebrain.nexus.tests.resources.SimpleResource
import ch.epfl.bluebrain.nexus.tests.{BaseIntegrationSpec, Optics, SchemaPayload}
import io.circe.syntax.{EncoderOps, KeyOps}
import io.circe.{Json, JsonObject}
import org.scalatest.Assertion
import org.scalatest.matchers.{HavePropertyMatchResult, HavePropertyMatcher}
import org.testcontainers.utility.Base58.randomString

import java.net.URLEncoder

class ResourcesSpec extends BaseIntegrationSpec {

  private val orgId                 = genId()
  private val projForcedSchemaId    = genId()
  private val projectForcedSchema   = s"$orgId/$projForcedSchemaId"
  private val projOptionalSchemaId  = genId()
  private val projectOptionalSchema = s"$orgId/$projOptionalSchemaId"

  private val varyHeader = RawHeader("Vary", "Accept,Accept-Encoding")

  private val resource1Id = "https://dev.nexus.test.com/simplified-resource/1"

  private val unconstrainedSchema        = "https://bluebrain.github.io/nexus/schemas/unconstrained.json"
  private val unconstrainedSchemaEncoded = UrlUtils.encode(unconstrainedSchema)

  private def resource1Response(rev: Int, priority: Int) =
    SimpleResource.fetchResponse(Rick, projectForcedSchema, resource1Id, rev, priority)

  private def resource1AnnotatedSource(rev: Int, priority: Int) =
    SimpleResource.annotatedResource(Rick, projectForcedSchema, resource1Id, rev, priority)

  private def `@id`(expectedId: String) = HavePropertyMatcher[Json, String] { json =>
    val actualId = Optics.`@id`.getOption(json)
    HavePropertyMatchResult(
      actualId.contains(expectedId),
      "@id",
      expectedId,
      actualId.orNull
    )
  }

  private def `@type`(expectedType: String) = HavePropertyMatcher[Json, String] { json =>
    val actualType = Optics.`@type`.getOption(json)
    HavePropertyMatchResult(
      actualType.contains(expectedType),
      "@type",
      expectedType,
      actualType.orNull
    )
  }

  override def beforeAll(): Unit = {
    super.beforeAll()
    val setup = for {
      _                    <- createOrg(Rick, orgId)
      forcedSchemaPayload   = ProjectPayload.generate(projectForcedSchema, enforceSchema = true)
      _                    <- adminDsl.createProject(orgId, projForcedSchemaId, forcedSchemaPayload, Rick)
      optionalSchemaPayload = ProjectPayload.generate(projectOptionalSchema, enforceSchema = false)
      _                    <- adminDsl.createProject(orgId, projOptionalSchemaId, optionalSchemaPayload, Rick)
      _                    <- aclDsl.addPermission(s"/$projectForcedSchema", Morty, Resources.Read)
    } yield ()
    setup.accepted
  }

  "Adding schema" should {
    "create a schema" in {
      val schemaPayload = SchemaPayload.loadSimple().accepted
      deltaClient.put[Json](s"/schemas/$projectForcedSchema/test-schema", schemaPayload, Rick) { expectCreated }
    }

    "creating a schema with property shape" in {
      val schemaPayload = jsonContentOf("kg/schemas/simple-schema-prop-shape.json")
      deltaClient.post[Json](s"/schemas/$projectForcedSchema", schemaPayload, Rick) { expectCreated }
    }

    "creating a schema that imports the property shape schema" in {
      val schemaPayload = jsonContentOf("kg/schemas/simple-schema-imports.json")
      deltaClient.post[Json](s"/schemas/$projectForcedSchema", schemaPayload, Rick) { expectCreated }
    }
  }

  "Creating a resource" should {

    "fail if the user does not have write access" in {
      for {
        payload <- SimpleResource.sourcePayload(resource1Id, 5)
        _       <- deltaClient.post[Json](s"/resources/$projectForcedSchema/test-schema/", payload, Anonymous) {
                     expectForbidden
                   }
      } yield succeed
    }

    "fail if provided with the same id as the created schema" in {
      for {
        payload <- SimpleResource.sourcePayload("https://dev.nexus.test.com/test-schema", 5)
        _       <- deltaClient.put[Json](s"/resources/$projectForcedSchema/test-schema/test-schema", payload, Rick) {
                     (json, response) =>
                       response.status shouldEqual StatusCodes.Conflict
                       json shouldEqual jsonContentOf(
                         "kg/resources/resource-already-exists-rejection.json",
                         "id"      -> "https://dev.nexus.test.com/test-schema",
                         "project" -> projectForcedSchema
                       )
                   }
      } yield succeed
    }

    "fail if created without a schema on a project prohibiting it" in {
      for {
        payload <- SimpleResource.sourcePayload(resource1Id, 5)
        _       <- deltaClient.put[Json](s"/resources/$projectForcedSchema/_/test-resource:1", payload, Rick) {
                     (json, response) =>
                       response.status shouldEqual StatusCodes.BadRequest
                       json should have(`@type`("SchemaIsMandatory"))
                   }
      } yield succeed
    }

    "fail if the resource can not be validated by the schema" in {
      for {
        _ <- deltaClient.put[Json](s"/resources/$projectForcedSchema/test-schema/test-resource:1", Json.obj(), Rick) {
               (json, response) =>
                 response.status shouldEqual StatusCodes.BadRequest
                 json should have(`@type`("InvalidResource"))
             }
      } yield succeed
    }

    "fail if the schema doesn't exist in the project" in {
      val payload = SimpleResource.sourcePayload(resource1Id, 3).accepted

      deltaClient.put[Json](s"/resources/$projectOptionalSchema/test-schema/test-resource:1", payload, Rick) {
        (_, response) =>
          response.status shouldEqual StatusCodes.NotFound
          response.headers should not contain varyHeader
      }
    }

    "fail if the payload contains nexus metadata fields (underscore fields)" in {
      val payload = SimpleResource
        .sourcePayload("1", 3)
        .accepted
        .deepMerge(json"""{"_self":  "http://delta/resources/path"}""")

      deltaClient.put[Json](s"/resources/$projectOptionalSchema/_/test-resource:1", payload, Rick) { (_, response) =>
        response.status shouldEqual StatusCodes.BadRequest
        response.headers should not contain varyHeader
      }
    }

    "succeed if created without a schema on a project allowing it" in {
      for {
        payload <- SimpleResource.sourcePayload(resource1Id, 5)
        _       <- deltaClient.put[Json](s"/resources/$projectOptionalSchema/_/test-resource:1", payload, Rick) {
                     expectCreated
                   }
      } yield succeed
    }

    "succeed if the payload can be validated by the schema" in {
      for {
        payload            <- SimpleResource.sourcePayload(resource1Id, 5)
        _                  <- deltaClient.put[Json](s"/resources/$projectForcedSchema/test-schema/test-resource:1", payload, Rick) {
                                expectCreated
                              }
        schemaWithImportsId = URLEncoder.encode("https://dev.nexus.test.com/test-schema-imports", "UTF-8")
        payload2           <- SimpleResource.sourcePayload("https://dev.nexus.test.com/simplified-resource/a", 5)
        _                  <- deltaClient
                                .put[Json](s"/resources/$projectForcedSchema/$schemaWithImportsId/test-resource:a", payload2, Rick) {
                                  expectCreated
                                }
      } yield succeed
    }
  }

  "Fetching a resource" should {

    "fail to fetch when the project does not exist" in {
      deltaClient.get[Json](s"/resources/xxx/xxx/_/xxx", ServiceAccount) { (json, response) =>
        response.status shouldEqual StatusCodes.NotFound
        json should have(`@type`("ProjectNotFound"))
      }
    }

    "fail to fetch the resource when the user does not have access" in {
      deltaClient.get[Json](s"/resources/$projectForcedSchema/test-schema/test-resource:1", Anonymous) {
        (_, response) =>
          expectForbidden
          response.headers should not contain varyHeader
      }
    }

    "fail to fetch the original payload when the user does not have access" in {
      deltaClient.get[Json](s"/resources/$projectForcedSchema/test-schema/test-resource:1/source", Anonymous) {
        (_, response) =>
          expectForbidden
          response.headers should not contain varyHeader
      }
    }

    "fail to fetch the annotated original payload when the user does not have access" in {
      deltaClient
        .get[Json](s"/resources/$projectForcedSchema/test-schema/test-resource:1/source?annotate=true", Anonymous) {
          (_, response) =>
            expectForbidden
            response.headers should not contain varyHeader
        }
    }

    "fetch the resource wih metadata" in {
      val expected = resource1Response(1, 5).accepted

      deltaClient.get[Json](s"/resources/$projectForcedSchema/test-schema/test-resource:1", Morty) { (json, response) =>
        response.status shouldEqual StatusCodes.OK
        filterMetadataKeys(json) should equalIgnoreArrayOrder(expected)
        response.headers should contain(varyHeader)
      }
    }

    "fetch the original payload" in {
      val expected = SimpleResource.sourcePayload(resource1Id, 5).accepted

      deltaClient.get[Json](s"/resources/$projectForcedSchema/test-schema/test-resource:1/source", Morty) {
        (json, response) =>
          response.status shouldEqual StatusCodes.OK
          json should equalIgnoreArrayOrder(expected)
          response.headers should contain(varyHeader)
      }
    }

    "fetch the original payload through a resolver" in {
      val expected = SimpleResource.sourcePayload(resource1Id, 5).accepted
      deltaClient.get[Json](s"/resolvers/$projectForcedSchema/_/test-resource:1/source", Morty) { (json, response) =>
        response.status shouldEqual StatusCodes.OK
        json should equalIgnoreArrayOrder(expected)
      }
    }

    "fetch the original payload with metadata" in {
      val expected = resource1AnnotatedSource(1, 5).accepted
      deltaClient
        .get[Json](s"/resources/$projectForcedSchema/test-schema/test-resource:1/source?annotate=true", Morty) {
          (json, response) =>
            response.status shouldEqual StatusCodes.OK
            filterMetadataKeys(json) should equalIgnoreArrayOrder(expected)
            response.headers should contain(varyHeader)
        }
    }

    "fetch the original payload with unexpanded id with metadata" in {
      val payload = SimpleResource.sourcePayload("42", 5).accepted

      for {
        _ <- deltaClient.post[Json](s"/resources/$projectOptionalSchema/_/", payload, Rick) { expectCreated }
        _ <- deltaClient.get[Json](s"/resources/$projectOptionalSchema/_/42/source?annotate=true", Rick) {
               (json, response) =>
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
        _ <- deltaClient.post[Json](s"/resources/$projectOptionalSchema/_/", payload, Rick) { (json, response) =>
               response.status shouldEqual StatusCodes.Created
               generatedId = Optics.`@id`.getOption(json).getOrElse(fail("could not find @id of created resource"))
               succeed
             }
        _ <- deltaClient
               .get[Json](
                 s"/resources/$projectOptionalSchema/_/${UrlUtils.encode(generatedId)}/source?annotate=true",
                 Rick
               ) { (json, response) =>
                 response.status shouldEqual StatusCodes.OK
                 response.headers should contain(varyHeader)
                 json should have(`@id`(generatedId))
               }
      } yield succeed
    }

    "return not found if a resource is missing" in {
      deltaClient
        .get[Json](
          s"/resources/$projectForcedSchema/test-schema/does-not-exist-resource:1/source?annotate=true",
          Morty
        ) { (_, response) =>
          response.status shouldEqual StatusCodes.NotFound
          response.headers should not contain varyHeader
        }
    }
  }

  "Creating resources using cross-project resolvers" should {
    val resolverPayload =
      jsonContentOf(
        "kg/resources/cross-project-resolver.json",
        replacements(Rick, "project" -> projectForcedSchema): _*
      )

    "fail to create a cross-project-resolver for proj2 if identities are missing" in {
      deltaClient.post[Json](s"/resolvers/$projectOptionalSchema", filterKey("identities")(resolverPayload), Rick) {
        expectBadRequest
      }
    }

    "create a cross-project-resolver for proj2" in {
      deltaClient.post[Json](s"/resolvers/$projectOptionalSchema", resolverPayload, Rick) { expectCreated }
    }

    "update a cross-project-resolver for proj2" in {
      val updated = resolverPayload deepMerge Json.obj("priority" -> Json.fromInt(20))
      deltaClient.put[Json](s"/resolvers/$projectOptionalSchema/test-resolver?rev=1", updated, Rick) { (_, response) =>
        response.status shouldEqual StatusCodes.OK
      }
    }

    "fetch the update" in {
      val expected = jsonContentOf(
        "kg/resources/cross-project-resolver-updated-resp.json",
        replacements(
          Rick,
          "project"        -> projectForcedSchema,
          "self"           -> resolverSelf(projectOptionalSchema, "http://localhost/resolver"),
          "project-parent" -> s"${config.deltaUri}/projects/$projectOptionalSchema"
        ): _*
      )

      deltaClient.get[Json](s"/resolvers/$projectOptionalSchema/test-resolver", Rick) { (json, response) =>
        response.status shouldEqual StatusCodes.OK
        filterMetadataKeys(json) should equalIgnoreArrayOrder(expected)
      }
    }

    "wait for the cross-project resolver to be indexed" in {
      eventually {
        deltaClient.get[Json](s"/resolvers/$projectOptionalSchema", Rick) { (json, response) =>
          response.status shouldEqual StatusCodes.OK
          _total.getOption(json).value shouldEqual 2L
        }
      }
    }

    s"resolve a resource in project '$projectForcedSchema' through project '$projectOptionalSchema' resolvers" in {
      for {
        _ <- deltaClient.get[Json](s"/schemas/$projectForcedSchema/test-schema", Rick) { (json, response1) =>
               response1.status shouldEqual StatusCodes.OK
               runIO {
                 for {
                   _ <-
                     deltaClient.get[Json](s"/resolvers/$projectOptionalSchema/_/test-schema", Rick) {
                       (jsonResolved, response2) =>
                         response2.status shouldEqual StatusCodes.OK
                         jsonResolved should equalIgnoreArrayOrder(json)
                     }
                   _ <- deltaClient.get[Json](s"/resolvers/$projectOptionalSchema/test-resolver/test-schema", Rick) {
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

    s"return not found when attempting to resolve a non-existing resource in project '$projectForcedSchema' through project '$projectOptionalSchema' resolvers" in {
      for {
        _ <- deltaClient.get[Json](s"/resolvers/$projectOptionalSchema/test-resolver/test-schema-2", Rick) {
               (_, response) =>
                 response.status shouldEqual StatusCodes.NotFound
             }
        _ <- deltaClient.get[Json](s"/resolvers/$projectOptionalSchema/_/test-schema-2", Rick) { (_, response) =>
               response.status shouldEqual StatusCodes.NotFound
             }
      } yield succeed
    }

    "resolve schema from the other project" in {
      val payload = SimpleResource.sourcePayload("https://dev.nexus.test.com/simplified-resource/2", 3).accepted
      deltaClient.post[Json](s"/resources/$projectOptionalSchema/test-schema/", payload, Rick) {
        expectCreated
      }
    }
  }

  "Updating a resource" should {

    "fail if the user does not have write access" in {
      for {
        payload <- SimpleResource.sourcePayload(resource1Id, 3)
        _       <-
          deltaClient.put[Json](s"/resources/$projectForcedSchema/_/test-resource:1?rev=1", payload, Anonymous) {
            expectForbidden
          }
      } yield succeed
    }

    "fail when setting the schema to unconstrained on a project prohibiting it" in {
      for {
        payload <- SimpleResource.sourcePayload(resource1Id, 3)
        _       <-
          deltaClient.put[Json](
            s"/resources/$projectForcedSchema/$unconstrainedSchemaEncoded/test-resource:1?rev=1",
            payload,
            Rick
          ) { (json, response) =>
            response.status shouldEqual StatusCodes.BadRequest
            json should have(`@type`("SchemaIsMandatory"))
          }
      } yield succeed
    }

    "fail when setting the schema to unconstrained on a project allowing it" in {
      for {
        payload <- SimpleResource.sourcePayload(resource1Id, 3)
        _       <-
          deltaClient.put[Json](
            s"/resources/$projectOptionalSchema/$unconstrainedSchemaEncoded/test-resource:1?rev=1",
            payload,
            Rick
          ) {
            expectOk
          }
      } yield succeed
    }

    "succeed" in {
      for {
        payload <- SimpleResource.sourcePayload(resource1Id, 3)
        _       <-
          deltaClient.put[Json](s"/resources/$projectForcedSchema/test-schema/test-resource:1?rev=1", payload, Rick) {
            (json, response) =>
              response.status shouldEqual StatusCodes.OK
              _rev.getOption(json).value shouldEqual 2
          }
        // Sending the same update should not create a new revision
        _       <- deltaClient.put[Json](s"/resources/$projectForcedSchema/_/test-resource:1?rev=2", payload, Rick) {
                     (json, response) =>
                       response.status shouldEqual StatusCodes.OK
                       _rev.getOption(json).value shouldEqual 2
                   }
      } yield succeed
    }

    "fetch the update" in {
      val expected = resource1Response(2, 3).accepted

      List(
        s"/resources/$projectForcedSchema/test-schema/test-resource:1",
        s"/resources/$projectForcedSchema/_/test-resource:1"
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
        s"/resources/$projectForcedSchema/test-schema/test-resource:1?rev=1",
        s"/resources/$projectForcedSchema/_/test-resource:1?rev=1"
      ).parTraverse { url =>
        deltaClient.get[Json](url, Rick) { (json, response) =>
          response.status shouldEqual StatusCodes.OK
          filterMetadataKeys(json) should equalIgnoreArrayOrder(expected)
        }
      }
    }

    "fetch previous revision original payload with metadata" in {
      val expected = resource1AnnotatedSource(1, 5).accepted
      deltaClient
        .get[Json](s"/resources/$projectForcedSchema/test-schema/test-resource:1/source?rev=1&annotate=true", Rick) {
          (json, response) =>
            response.status shouldEqual StatusCodes.OK
            filterMetadataKeys(json) should equalIgnoreArrayOrder(expected)
        }
    }

    "allow to change the schema" in {
      val payload = SimpleResource.sourcePayload(4).accepted

      def updateResourceAndSchema: (String, String) => ((Json, HttpResponse) => Assertion) => IO[Assertion] =
        (id, schema) =>
          deltaClient.put[Json](s"/resources/$projectForcedSchema/$schema/$id?rev=1", payload, Rick) {
            expectOk
          } >>
            deltaClient.get[Json](s"/resources/$projectForcedSchema/$schema/$id?rev=2", Rick)(_)

      givenASchemaIn(projectForcedSchema) { firstSchema =>
        givenASchemaIn(projectForcedSchema) { newSchema =>
          givenAResourceWithSchema(projectForcedSchema, firstSchema) { id =>
            val expectedSchema = "http://delta:8080/v1/resources/" + projectForcedSchema + s"/_/$newSchema"
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

      givenASchemaIn(projectForcedSchema) { schema =>
        givenAResourceWithSchema(projectForcedSchema, schema) { id =>
          val updateWithTag =
            deltaClient.put[Json](s"/resources/$projectForcedSchema/$schema/$id?rev=1&tag=$tag", payload, Rick)(
              expectOk
            )
          val fetchByTag    =
            deltaClient.get[Json](s"/resources/$projectForcedSchema/$schema/$id?tag=$tag", Rick)(expectOk)

          (updateWithTag >> fetchByTag).accepted
        }
      }
    }
  }

  "Tagging a resource" should {
    "create a tag" in {
      for {
        _ <- deltaClient
               .post[Json](
                 s"/resources/$projectForcedSchema/test-schema/test-resource:1/tags?rev=2&indexing=sync",
                 tag("v1.0.0", 1),
                 Rick
               ) { (_, response) =>
                 response.status shouldEqual StatusCodes.Created
               }
        _ <-
          deltaClient
            .post[Json](
              s"/resources/$projectForcedSchema/_/test-resource:1/tags?rev=3&indexing=sync",
              tag("v1.0.1", 2),
              Rick
            ) { (_, response) =>
              response.status shouldEqual StatusCodes.Created
            }
        _ <- deltaClient
               .delete[Json](s"/resources/$projectForcedSchema/_/test-resource:1?rev=4&indexing=sync", Rick) {
                 (_, response) =>
                   response.status shouldEqual StatusCodes.OK
               }
        _ <-
          deltaClient
            .post[Json](
              s"/resources/$projectForcedSchema/_/test-resource:1/tags?rev=5&indexing=sync",
              tag("v1.0.2", 5),
              Rick
            ) { (_, response) =>
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
          deltaClient.get[Json](s"/resources/$projectForcedSchema/test-schema/test-resource:1?tag=v1.0.1", Morty) {
            (json, response) =>
              response.status shouldEqual StatusCodes.OK
              filterMetadataKeys(json) should equalIgnoreArrayOrder(expectedTag1)
          }
        _ <- deltaClient.get[Json](s"/resources/$projectForcedSchema/_/test-resource:1?tag=v1.0.0", Morty) {
               (json, response) =>
                 response.status shouldEqual StatusCodes.OK
                 filterMetadataKeys(json) should equalIgnoreArrayOrder(expectedTag2)
             }
        _ <- deltaClient.get[Json](s"/resources/$projectForcedSchema/_/test-resource:1?tag=v1.0.2", Morty) {
               (json, response) =>
                 response.status shouldEqual StatusCodes.OK
                 filterMetadataKeys(json) should equalIgnoreArrayOrder(expectedTag3)
             }
      } yield succeed
    }

    "fetch tagged original payload with metadata" in {
      deltaClient
        .get[Json](
          s"/resources/$projectForcedSchema/test-schema/test-resource:1/source?tag=v1.0.1&annotate=true",
          Rick
        ) { (json, response) =>
          val expected = resource1AnnotatedSource(2, 3).accepted
          response.status shouldEqual StatusCodes.OK
          filterMetadataKeys(json) should equalIgnoreArrayOrder(expected)
        }
    }

    "delete a tag" in {
      deltaClient
        .delete[Json](s"/resources/$projectForcedSchema/_/test-resource:1/tags/v1.0.1?rev=6", Rick) { (_, response) =>
          response.status shouldEqual StatusCodes.OK
        }
    }
  }

  "Deprecating a resource" should {

    "fail without authorization" in {
      givenAResource(projectOptionalSchema) { id =>
        deltaClient
          .delete[Json](s"/resources/$projectOptionalSchema/_/$id?rev=1", Anonymous) { expectForbidden }
          .accepted
      }
    }

    "succeed" in {
      givenAResource(projectOptionalSchema) { id =>
        val deprecate       = deltaClient.delete(s"/resources/$projectOptionalSchema/_/$id?rev=1", Rick) { expectOk }
        val fetchDeprecated = deltaClient.get[Json](s"/resources/$projectOptionalSchema/_/$id", Rick) { (json, _) =>
          json should be(deprecated)
        }
        (deprecate >> fetchDeprecated).accepted
      }
    }

    "lead to an empty resource listing" in {
      givenAResource(projectOptionalSchema) { id =>
        val deprecate         = deltaClient.delete(s"/resources/$projectOptionalSchema/_/$id?rev=1", Rick) { expectOk }
        val fetchEmptyListing =
          deltaClient.get[Json](s"/resources/$projectOptionalSchema?locate=$id", Rick) { (json, _) =>
            _total.getOption(json) should contain(0)
          }
        deprecate.accepted
        eventually { fetchEmptyListing }
      }
    }

  }

  "Undeprecating a resource" should {

    "fail without authorization" in {
      givenADeprecatedResource(projectOptionalSchema) { id =>
        deltaClient
          .put(s"/resources/$projectOptionalSchema/_/$id/undeprecate?rev=2", JsonObject.empty.toJson, Anonymous) {
            expectForbidden
          }
          .accepted
      }
    }

    "succeed" in {
      givenADeprecatedResource(projectOptionalSchema) { id =>
        val undeprecate       =
          deltaClient.put(s"/resources/$projectOptionalSchema/_/$id/undeprecate?rev=2", JsonObject.empty.toJson, Rick) {
            expectOk
          }
        val fetchUndeprecated = deltaClient.get[Json](s"/resources/$projectOptionalSchema/_/$id", Rick) {
          case (json, _) =>
            json should not(be(deprecated))
        }
        (undeprecate >> fetchUndeprecated).accepted
      }
    }

    "allow finding an undeprecated resource in the listing" in {
      givenADeprecatedResource(projectOptionalSchema) { id =>
        val undeprecate  =
          deltaClient.put(s"/resources/$projectOptionalSchema/_/$id/undeprecate?rev=2", JsonObject.empty.toJson, Rick) {
            expectOk
          }
        val fetchListing =
          deltaClient.get[Json](s"/resources/$projectOptionalSchema?locate=$id", Rick) { (json, _) =>
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
            .put[Json](
              s"/resources/$projectForcedSchema/test-schema/test-resource:$resourceId?indexing=sync",
              payload,
              Rick
            ) {
              expectCreated
            }
        _ <- deltaClient.get[Json](s"/resources/$projectForcedSchema/test-schema", Rick) { (json, response) =>
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
      _ <- deltaClient.put[Json](s"/resources/$projectOptionalSchema/_/test-resource:mycontext", contextPayload, Rick) {
             expectCreated
           }
      _ <- deltaClient.post[Json](s"/resources/$projectOptionalSchema/", resourcePayload, Rick) { expectCreated }
      // No refresh should be performed as nothing changed
      _ <-
        deltaClient.put[Json](s"/resources/$projectOptionalSchema/_/myid/refresh", Json.Null, Rick) {
          (json, response) =>
            response.status shouldEqual StatusCodes.OK
            _rev.getOption(json).value shouldEqual 1
        }
      _ <-
        deltaClient
          .put[Json](
            s"/resources/$projectOptionalSchema/_/test-resource:mycontext?rev=1",
            contextPayloadUpdated,
            Rick
          ) { (json, response) =>
            response.status shouldEqual StatusCodes.OK
            _rev.getOption(json).value shouldEqual 2
          }
      _ <-
        deltaClient.put[Json](s"/resources/$projectOptionalSchema/_/myid/refresh", Json.Null, Rick) {
          (json, response) =>
            response.status shouldEqual StatusCodes.OK
            _rev.getOption(json).value shouldEqual 2
        }
    } yield succeed
  }

  "fetch remote contexts for the created resource" in {
    deltaClient.get[Json](s"/resources/$projectOptionalSchema/_/myid/remote-contexts", Rick) { (json, response) =>
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
                "project": "$projectOptionalSchema",
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
      s"/resources/$projectForcedSchema/_/test-resource:1",
      Morty,
      extraHeaders = List(Accept(MediaRange.One(`text/html`, 1f)))
    ) { (_, response) =>
      response.status shouldEqual StatusCodes.SeeOther
      response
        .header[Location]
        .value
        .uri
        .toString() shouldEqual s"https://bbp.epfl.ch/nexus/web/$projectForcedSchema/resources/test-resource:1"
    }(PredefinedFromEntityUnmarshallers.stringUnmarshaller)
  }

  "refreshing a resource" should {

    val projId3  = genId()
    val project3 = s"$orgId/$projId3"

    val originalBase = "http://my-original-base.com/"
    val newBase      = "http://my-new-base.com/"
    val vocab        = s"${config.deltaUri}/vocabs/$project3/"

    val noContextId        = s"${originalBase}no-context"
    val noContextIdEncoded = UrlUtils.encode(noContextId)

    val noBaseId        = s"${originalBase}no-base"
    val noBaseIdEncoded = UrlUtils.encode(noBaseId)

    val tpe             = "my-type"
    val expandedType    = s"$originalBase$tpe"
    val newExpandedType = s"$newBase$tpe"

    def contextWithBase(base: String) =
      json"""
      [
        "https://bluebrain.github.io/nexus/contexts/metadata.json",
        { "@base" : "$base", "@vocab" : "$vocab" }
      ]"""

    "create a project" in {
      val payload = ProjectPayload.generateWithCustomBase(project3, originalBase)
      adminDsl.createProject(orgId, projId3, payload, Rick)
    }

    "create resource without a context" in {
      val payload = json"""{ "@id": "$noContextId", "@type": "$tpe" }"""
      deltaClient.post[Json](s"/resources/$project3/", payload, Rick) { expectCreated }
    }

    "fetch the resource with a context injected from the project configuration" in {
      deltaClient.get[Json](s"/resources/$project3/_/$noContextIdEncoded", Rick) { (json, response) =>
        response.status shouldEqual StatusCodes.OK
        json should have(`@type`(tpe))
        Optics.context.getOption(json).value shouldEqual contextWithBase(originalBase)
      }
    }

    "create resource with a context without a base" in {
      val payload = json"""{ "@context": { "name":  "https://schema.org/name"}, "@id": "$noBaseId", "@type": "$tpe" }"""
      deltaClient.post[Json](s"/resources/$project3/", payload, Rick) {
        expectCreated
      }
    }

    "fetch the resource with without a define base" in {
      deltaClient.get[Json](s"/resources/$project3/_/$noBaseIdEncoded", Rick) { (json, response) =>
        response.status shouldEqual StatusCodes.OK
        json should have(`@type`(expandedType))
      }
    }

    "update the project base" in {
      val newProjectPayload = ProjectPayload.generateWithCustomBase(project3, newBase)
      adminDsl.updateProject(orgId, projId3, newProjectPayload, Rick, 1)
    }

    "fetch the resource with a context injected from the new project configuration after a refresh" in {
      for {
        _ <- deltaClient.put[Json](s"/resources/$project3/_/$noContextIdEncoded/refresh?rev=1", Json.Null, Rick) {
               expectOk
             }
        _ <- deltaClient.get[Json](s"/resources/$project3/_/$noContextIdEncoded", Rick) { (json, response) =>
               response.status shouldEqual StatusCodes.OK
               json should have(`@type`(tpe))
               Optics.context.getOption(json).value shouldEqual contextWithBase(newBase)
               Optics._rev.getOption(json).value shouldEqual 2
             }
        _ <- deltaClient.put[Json](s"/resources/$project3/_/$noContextIdEncoded/refresh?rev=2", Json.Null, Rick) {
               (json, response) =>
                 response.status shouldEqual StatusCodes.OK
                 Optics._rev.getOption(json).value shouldEqual 2
             }
      } yield succeed
    }

    "fetch the resource with a defined base from the new project configuration after a refresh" in {
      for {
        _ <- deltaClient.put[Json](s"/resources/$project3/_/$noBaseIdEncoded/refresh?rev=1", Json.Null, Rick) {
               expectOk
             }
        _ <- deltaClient.get[Json](s"/resources/$project3/_/$noBaseIdEncoded", Rick) { (json, response) =>
               response.status shouldEqual StatusCodes.OK
               json should have(`@type`(newExpandedType))
               Optics._rev.getOption(json).value shouldEqual 2
             }
        _ <- deltaClient.put[Json](s"/resources/$project3/_/$noBaseIdEncoded/refresh?rev=2", Json.Null, Rick) {
               (json, response) =>
                 response.status shouldEqual StatusCodes.OK
                 Optics._rev.getOption(json).value shouldEqual 2
             }
      } yield succeed
    }
  }

  "Updating the schema of a resource" should {

    "fail when attempting to update it to unconstrained when the project prohibits it" in {
      givenASchemaIn(projectForcedSchema) { firstSchema =>
        givenAResourceWithSchema(projectForcedSchema, firstSchema) { id =>
          deltaClient
            .put[Json](
              s"/resources/$projectForcedSchema/$unconstrainedSchemaEncoded/$id/update-schema",
              Json.Null,
              Rick
            ) { (json, response) =>
              response.status shouldEqual StatusCodes.BadRequest
              json should have(`@type`("SchemaIsMandatory"))
            }
            .accepted
        }
      }
    }

    "succeed when attempting to update it to unconstrained when the project allows it" in {
      givenASchemaIn(projectOptionalSchema) { firstSchema =>
        givenAResourceWithSchema(projectOptionalSchema, firstSchema) { id =>
          deltaClient
            .put[Json](
              s"/resources/$projectOptionalSchema/$unconstrainedSchemaEncoded/$id/update-schema",
              Json.Null,
              Rick
            ) {
              expectOk
            }
            .accepted
        }
      }
    }

    "succeed" in {
      givenASchemaIn(projectForcedSchema) { firstSchema =>
        givenASchemaIn(projectForcedSchema) { newSchema =>
          givenAResourceWithSchema(projectForcedSchema, firstSchema) { id =>
            deltaClient
              .put[Json](s"/resources/$projectForcedSchema/$newSchema/$id/update-schema", Json.Null, Rick) {
                (_, response) =>
                  response.status shouldEqual StatusCodes.OK
              }
              .accepted

            val newSchemaId = "http://delta:8080/v1/resources/" + projectForcedSchema + s"/_/$newSchema"

            deltaClient
              .get[Json](s"/resources/$projectForcedSchema/$newSchema/$id", Rick) { (json, _) =>
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
      deltaClient.post[Json](s"/resources/$projectForcedSchema/", payload, Rick) { (json, response) =>
        response.status shouldEqual StatusCodes.PayloadTooLarge
        Optics.`@type`.getOption(json) shouldEqual Some("PayloadTooLarge")
      }
    }

  }

  "checking for update changes for a large resource" should {
    "succeed" in {
      val id                 = "large"
      val tpe                = "Random"
      val largeRandomPayload = {
        val entry = Json.obj(
          "array"  := (1 to 100).toList,
          "string" := "some-value"
        )
        (1 to 500).foldLeft(JsonObject("@type" := tpe)) { case (acc, index) =>
          acc.add(s"prop$index", entry)
        }
      }.asJson

      for {
        _ <- deltaClient.put[Json](s"/resources/$projectOptionalSchema/_/test-resource:$id", largeRandomPayload, Rick) {
               expectCreated
             }
        _ <- deltaClient
               .put[Json](s"/resources/$projectOptionalSchema/_/test-resource:$id?rev=1", largeRandomPayload, Rick) {
                 (json, response) =>
                   response.status shouldEqual StatusCodes.OK
                   Optics._rev.getOption(json).value shouldEqual 1
               }
      } yield succeed
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
          json should be(deprecated)
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
