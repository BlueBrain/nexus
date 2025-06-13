package ai.senscience.nexus.delta.routes

import akka.http.scaladsl.model.MediaTypes.`text/html`
import akka.http.scaladsl.model.headers.{Accept, Location}
import akka.http.scaladsl.model.{StatusCodes, Uri}
import akka.http.scaladsl.server.Route
import ch.epfl.bluebrain.nexus.delta.kernel.utils.UUIDF
import ch.epfl.bluebrain.nexus.delta.kernel.utils.UrlUtils.encodeUriPath
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.{contexts, nxv}
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.JsonLdContext.keywords
import ch.epfl.bluebrain.nexus.delta.rdf.shacl.ValidateShacl
import ch.epfl.bluebrain.nexus.delta.sdk.acls.AclSimpleCheck
import ch.epfl.bluebrain.nexus.delta.sdk.acls.model.AclAddress
import ch.epfl.bluebrain.nexus.delta.sdk.directives.DeltaSchemeDirectives
import ch.epfl.bluebrain.nexus.delta.sdk.generators.ProjectGen
import ch.epfl.bluebrain.nexus.delta.sdk.identities.IdentitiesDummy
import ch.epfl.bluebrain.nexus.delta.sdk.implicits.*
import ch.epfl.bluebrain.nexus.delta.sdk.model.ResourceAccess
import ch.epfl.bluebrain.nexus.delta.sdk.permissions.Permissions.schemas
import ch.epfl.bluebrain.nexus.delta.sdk.projects.FetchContextDummy
import ch.epfl.bluebrain.nexus.delta.sdk.projects.model.ApiMappings
import ch.epfl.bluebrain.nexus.delta.sdk.resolvers.ResolverContextResolution
import ch.epfl.bluebrain.nexus.delta.sdk.schemas.*
import ch.epfl.bluebrain.nexus.delta.sdk.utils.BaseRouteSpec
import ch.epfl.bluebrain.nexus.delta.sourcing.ScopedEventLog
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.{Subject, User}
import ch.epfl.bluebrain.nexus.delta.sourcing.model.ProjectRef
import ch.epfl.bluebrain.nexus.testkit.ce.IOFromMap
import ch.epfl.bluebrain.nexus.testkit.scalatest.ce.CatsIOValues
import io.circe.Json
import org.scalatest.Assertion

import java.util.UUID

class SchemasRoutesSpec extends BaseRouteSpec with IOFromMap with CatsIOValues {

  private val uuid                  = UUID.randomUUID()
  implicit private val uuidF: UUIDF = UUIDF.fixed(uuid)

  private val reader = User("reader", realm)
  private val writer = User("writer", realm)

  private val identities = IdentitiesDummy.fromUsers(reader, writer)

  private val am         = ApiMappings("nxv" -> nxv.base, "schema" -> Vocabulary.schemas.shacl)
  private val projBase   = nxv.base
  private val project    = ProjectGen.resourceFor(
    ProjectGen.project("myorg", "myproject", uuid = uuid, orgUuid = uuid, base = projBase, mappings = am)
  )
  private val projectRef = project.value.ref

  private val myId           = nxv + "myid"
  private val myIdEncoded    = encodeUriPath(myId.toString)
  private val myId2          = nxv + "myid2"
  private val myId2Encoded   = encodeUriPath(myId2.toString)
  private val payload        = jsonContentOf("resources/schema.json") deepMerge json"""{"@id": "$myId"}"""
  private val payloadNoId    = payload.removeKeys(keywords.id)
  private val payloadUpdated = payloadNoId.replace("datatype" -> "xsd:integer", "xsd:double")

  private val schemaImports = SchemaImports.alwaysFail

  private val resolverContextResolution: ResolverContextResolution = ResolverContextResolution(rcr)

  private lazy val aclCheck = AclSimpleCheck().accepted

  private val fetchContext    = FetchContextDummy(List(project.value))
  private val groupDirectives = DeltaSchemeDirectives(fetchContext)

  private val schemaDef      = Schemas.definition(ValidateSchema(ValidateShacl(rcr).accepted), clock)
  private lazy val schemaLog = ScopedEventLog(schemaDef, eventLogConfig, xas)

  private lazy val routes =
    Route.seal(
      SchemasRoutes(
        identities,
        aclCheck,
        SchemasImpl(schemaLog, fetchContext, schemaImports, resolverContextResolution),
        groupDirectives
      )
    )

  override def beforeAll(): Unit = {
    super.beforeAll()
    aclCheck.append(AclAddress.Root, reader -> Set(schemas.read)).accepted
    aclCheck.append(AclAddress.Root, writer -> Set(schemas.write)).accepted
  }

  "A schema route" should {

    "fail to create a schema without schemas/write permission" in {
      Post("/v1/schemas/myorg/myproject", payload.toEntity) ~> as(reader) ~> routes ~> check {
        response.shouldBeForbidden
      }
    }

    "create a schema" in {
      Post("/v1/schemas/myorg/myproject", payload.toEntity) ~> as(writer) ~> routes ~> check {
        status shouldEqual StatusCodes.Created
        response.asJson shouldEqual schemaMetadata(projectRef, myId)
      }
    }

    "create a schema with an authenticated user and provided id" in {
      Put("/v1/schemas/myorg/myproject/myid2", payloadNoId.toEntity) ~> as(writer) ~> routes ~> check {
        status shouldEqual StatusCodes.Created
        response.asJson shouldEqual schemaMetadata(projectRef, myId2)
      }
    }

    "fail to list schemas" in {
      Get("/v1/schemas/myorg/myproject") ~> routes ~> check {
        status shouldEqual StatusCodes.Forbidden
      }
    }

    "list schemas in the project with the appropriate permission" in {
      Get("/v1/schemas/myorg/myproject") ~> as(reader) ~> routes ~> check {
        status shouldEqual StatusCodes.OK
        response.asJson.asObject.value("_total").value shouldEqual Json.fromLong(2L)
      }
    }

    "reject the creation of a schema which already exists" in {
      Put("/v1/schemas/myorg/myproject/myid", payload.toEntity) ~> as(writer) ~> routes ~> check {
        status shouldEqual StatusCodes.Conflict
        response.asJson shouldEqual
          jsonContentOf("schemas/errors/already-exists.json", "id" -> myId, "project" -> "myorg/myproject")
      }
    }

    "fail to create a schema that does not validate against the SHACL schema" in {
      Put(
        s"/v1/schemas/myorg/myproject/myidwrong",
        payload.removeKeys(keywords.id).replaceKeyWithValue("nodeKind", 42).toEntity
      ) ~> as(writer) ~> routes ~> check {
        response.status shouldEqual StatusCodes.BadRequest
        response.asJson shouldEqual jsonContentOf("schemas/errors/invalid-schema.json")
      }
    }

    "fail to update a schema without schemas/write permission" in {
      Put(s"/v1/schemas/myorg/myproject/myid?rev=1", payload.toEntity) ~> as(reader) ~> routes ~> check {
        response.shouldBeForbidden
      }
    }

    "update a schema" in {
      val endpoints = List(
        "/v1/schemas/myorg/myproject/myid",
        s"/v1/schemas/myorg/myproject/$myIdEncoded"
      )
      forAll(endpoints.zipWithIndex) { case (endpoint, idx) =>
        Put(s"$endpoint?rev=${idx + 1}", payloadUpdated.toEntity) ~> as(writer) ~> routes ~> check {
          status shouldEqual StatusCodes.OK
          response.asJson shouldEqual schemaMetadata(projectRef, myId, rev = idx + 2)
        }
      }
    }

    "reject the update of a non-existent schema" in {
      val payload = payloadUpdated.removeKeys(keywords.id)
      Put("/v1/schemas/myorg/myproject/myid10?rev=1", payload.toEntity) ~> as(writer) ~> routes ~> check {
        status shouldEqual StatusCodes.NotFound
        response.asJson shouldEqual
          jsonContentOf("schemas/errors/not-found.json", "id" -> (nxv + "myid10"), "proj" -> "myorg/myproject")
      }
    }

    "reject the update of a schema at a non-existent revision" in {
      Put("/v1/schemas/myorg/myproject/myid?rev=10", payloadUpdated.toEntity) ~> as(writer) ~> routes ~> check {
        status shouldEqual StatusCodes.Conflict
        response.asJson shouldEqual
          jsonContentOf("schemas/errors/incorrect-rev.json", "provided" -> 10, "expected" -> 3)
      }
    }

    "fail to refresh a schema without schemas/write permission" in {
      Put(s"/v1/schemas/myorg/myproject/myid/refresh", payload.toEntity) ~> as(reader) ~> routes ~> check {
        response.shouldBeForbidden
      }
    }

    "refresh a schema" in {
      val endpoints = List(
        "/v1/schemas/myorg/myproject/myid",
        s"/v1/schemas/myorg/myproject/$myIdEncoded"
      )
      forAll(endpoints.zipWithIndex) { case (endpoint, idx) =>
        Put(s"$endpoint/refresh") ~> as(writer) ~> routes ~> check {
          status shouldEqual StatusCodes.OK
          response.asJson shouldEqual schemaMetadata(projectRef, myId, rev = idx + 4)
        }
      }
    }

    "reject the refresh of a non-existent schema" in {
      val payload = payloadUpdated.removeKeys(keywords.id)
      Put("/v1/schemas/myorg/myproject/myid10/refresh", payload.toEntity) ~> as(writer) ~> routes ~> check {
        status shouldEqual StatusCodes.NotFound
        response.asJson shouldEqual
          jsonContentOf("schemas/errors/not-found.json", "id" -> (nxv + "myid10"), "proj" -> "myorg/myproject")
      }
    }

    "fail to deprecate a schema without schemas/write permission" in {
      Delete("/v1/schemas/myorg/myproject/myid?rev=3") ~> as(reader) ~> routes ~> check {
        response.shouldBeForbidden
      }
    }

    "deprecate a schema" in {
      Delete("/v1/schemas/myorg/myproject/myid?rev=5") ~> as(writer) ~> routes ~> check {
        status shouldEqual StatusCodes.OK
        response.asJson shouldEqual schemaMetadata(projectRef, myId, rev = 6, deprecated = true)
      }
    }

    "reject the deprecation of a schema without rev" in {
      Delete("/v1/schemas/myorg/myproject/myid") ~> as(writer) ~> routes ~> check {
        status shouldEqual StatusCodes.BadRequest
        response.asJson shouldEqual jsonContentOf("errors/missing-query-param.json", "field" -> "rev")
      }
    }

    "reject the deprecation of a already deprecated schema" in {
      Delete(s"/v1/schemas/myorg/myproject/myid?rev=6") ~> as(writer) ~> routes ~> check {
        status shouldEqual StatusCodes.BadRequest
        response.asJson shouldEqual jsonContentOf("schemas/errors/schema-deprecated.json", "id" -> myId)
      }
    }

    "fail to undeprecate a schema without schemas/write permission" in {
      givenADeprecatedSchema { schema =>
        Put(s"/v1/schemas/myorg/myproject/$schema/undeprecate?rev=2") ~> as(reader) ~> routes ~> check {
          response.shouldBeForbidden
        }
      }
    }

    "undeprecate a deprecated schema" in {
      givenADeprecatedSchema { schema =>
        Put(s"/v1/schemas/myorg/myproject/$schema/undeprecate?rev=2") ~> as(writer) ~> routes ~> check {
          status shouldEqual StatusCodes.OK
          response.asJson shouldEqual schemaMetadata(projectRef, nxv + schema, rev = 3)
        }
      }
    }

    "reject the undeprecation of a schema without rev" in {
      givenADeprecatedSchema { schema =>
        Put(s"/v1/schemas/myorg/myproject/$schema/undeprecate") ~> as(writer) ~> routes ~> check {
          status shouldEqual StatusCodes.BadRequest
          response.asJson shouldEqual jsonContentOf("errors/missing-query-param.json", "field" -> "rev")
        }
      }
    }

    "reject the undeprecation of a schema that is not deprecated" in {
      givenASchema { schema =>
        Put(s"/v1/schemas/myorg/myproject/$schema/undeprecate?rev=1") ~> as(writer) ~> routes ~> check {
          status shouldEqual StatusCodes.BadRequest
          response.asJson shouldEqual jsonContentOf("schemas/errors/schema-not-deprecated.json", "id" -> (nxv + schema))
        }
      }
    }

    "tag a schema" in {
      val payload = json"""{"tag": "mytag", "rev": 1}"""
      Post("/v1/schemas/myorg/myproject/myid2/tags?rev=1", payload.toEntity) ~> as(writer) ~> routes ~> check {
        status shouldEqual StatusCodes.Created
        response.asJson shouldEqual schemaMetadata(projectRef, myId2, rev = 2)
      }
    }

    "fail to fetch a schema without resources/read permission" in {
      val endpoints = List(
        "/v1/resources/myorg/myproject/_/myid2",
        "/v1/resources/myorg/myproject/schema/myid2",
        "/v1/schemas/myorg/myproject/myid2",
        "/v1/schemas/myorg/myproject/myid2/tags"
      )
      forAll(endpoints) { endpoint =>
        forAll(List("", "?rev=1", "?tags=mytag")) { suffix =>
          Get(s"$endpoint$suffix") ~> routes ~> check {
            response.shouldBeForbidden
          }
        }
      }
    }

    "fetch a schema" in {
      val endpoints = List("/v1/schemas/myorg/myproject/myid", "/v1/resources/myorg/myproject/_/myid")
      forAll(endpoints) { endpoint =>
        Get(endpoint) ~> as(reader) ~> routes ~> check {
          status shouldEqual StatusCodes.OK
          response.asJson shouldEqual jsonContentOf(
            "schemas/schema-updated-response.json",
            "id"   -> "nxv:myid",
            "self" -> ResourceAccess.schema(projectRef, myId).uri
          )
        }
      }
    }

    "fetch a schema by rev and tag" in {
      val endpoints = List(
        "/v1/schemas/myorg/myproject/myid2",
        "/v1/resources/myorg/myproject/_/myid2",
        "/v1/resources/myorg/myproject/schema/myid2",
        s"/v1/schemas/myorg/myproject/$myId2Encoded",
        s"/v1/resources/myorg/myproject/_/$myId2Encoded",
        s"/v1/resources/myorg/myproject/schema/$myId2Encoded"
      )
      forAll(endpoints) { endpoint =>
        forAll(List("rev=1", "tag=mytag")) { param =>
          Get(s"$endpoint?$param") ~> as(reader) ~> routes ~> check {
            status shouldEqual StatusCodes.OK
            response.asJson shouldEqual jsonContentOf(
              "schemas/schema-created-response.json",
              "id"   -> "nxv:myid2",
              "self" -> ResourceAccess.schema(projectRef, myId2).uri
            )
            response.expectConditionalCacheHeaders
          }
        }
      }
    }

    "fetch a schema original payload" in {
      val endpoints = List(
        "/v1/schemas/myorg/myproject/myid2/source",
        "/v1/resources/myorg/myproject/_/myid2/source",
        "/v1/resources/myorg/myproject/schema/myid2/source",
        s"/v1/schemas/myorg/myproject/$myId2Encoded/source",
        s"/v1/resources/myorg/myproject/_/$myId2Encoded/source",
        s"/v1/resources/myorg/myproject/schema/$myId2Encoded/source"
      )
      forAll(endpoints) { endpoint =>
        Get(endpoint) ~> as(reader) ~> routes ~> check {
          status shouldEqual StatusCodes.OK
          response.asJson shouldEqual payloadNoId
          response.expectConditionalCacheHeaders
        }
      }
    }

    "fetch a schema original payload by rev or tag" in {
      val endpoints = List(
        "/v1/schemas/myorg/myproject/myid2/source",
        "/v1/resources/myorg/myproject/_/myid2/source",
        "/v1/resources/myorg/myproject/schema/myid2/source",
        s"/v1/schemas/myorg/myproject/$myId2Encoded/source",
        s"/v1/resources/myorg/myproject/_/$myId2Encoded/source",
        s"/v1/resources/myorg/myproject/schema/$myId2Encoded/source"
      )
      forAll(endpoints) { endpoint =>
        forAll(List("rev=1", "tag=mytag")) { param =>
          Get(s"$endpoint?$param") ~> as(reader) ~> routes ~> check {
            status shouldEqual StatusCodes.OK
            response.asJson shouldEqual payloadNoId
          }
        }
      }
    }

    "fetch an annotated schema original payload" in {

      Get("/v1/schemas/myorg/myproject/myid2/source?annotate=true") ~> as(reader) ~> routes ~> check {
        status shouldEqual StatusCodes.OK
        response.asJson shouldEqual jsonContentOf(
          "schemas/schema-payload-with-metadata.json",
          "id"   -> myId2,
          "self" -> ResourceAccess.schema(projectRef, myId2).uri,
          "rev"  -> 2
        )
      }
    }

    "fetch an annotated schema original payload by rev or tag" in {
      val endpoints = List(
        "/v1/schemas/myorg/myproject/myid2/source?rev=1&annotate=true",
        "/v1/schemas/myorg/myproject/myid2/source?tag=mytag&annotate=true"
      )

      forAll(endpoints) { endpoint =>
        Get(endpoint) ~> as(reader) ~> routes ~> check {
          status shouldEqual StatusCodes.OK
          response.asJson shouldEqual jsonContentOf(
            "schemas/schema-payload-with-metadata.json",
            "id"   -> myId2,
            "self" -> ResourceAccess.schema(projectRef, myId2).uri,
            "rev"  -> 1
          )
        }
      }
    }

    "fetch the schema tags" in {
      Get("/v1/schemas/myorg/myproject/myid2/tags?rev=1") ~> as(reader) ~> routes ~> check {
        status shouldEqual StatusCodes.OK
        response.asJson shouldEqual json"""{"tags": []}""".addContext(contexts.tags)
      }
      Get("/v1/resources/myorg/myproject/_/myid2/tags") ~> as(reader) ~> routes ~> check {
        status shouldEqual StatusCodes.OK
        response.asJson shouldEqual json"""{"tags": [{"rev": 1, "tag": "mytag"}]}""".addContext(contexts.tags)
      }
    }

    "delete a tag on schema" in {
      Delete("/v1/schemas/myorg/myproject/myid2/tags/mytag?rev=2") ~> as(writer) ~> routes ~> check {
        status shouldEqual StatusCodes.OK
        response.asJson shouldEqual schemaMetadata(projectRef, myId2, rev = 3)
      }
    }

    "not return the deleted tag" in {
      Get("/v1/schemas/myorg/myproject/myid2/tags") ~> as(reader) ~> routes ~> check {
        status shouldEqual StatusCodes.OK
        response.asJson shouldEqual json"""{"tags": []}""".addContext(contexts.tags)
      }
    }

    "fail to fetch resource by the deleted tag" in {
      Get("/v1/schemas/myorg/myproject/myid2?tag=mytag") ~> as(reader) ~> routes ~> check {
        status shouldEqual StatusCodes.NotFound
        response.asJson shouldEqual jsonContentOf("errors/tag-not-found.json", "tag" -> "mytag")
      }
    }

    "return not found if tag not found" in {
      Get("/v1/schemas/myorg/myproject/myid2?tag=myother") ~> as(reader) ~> routes ~> check {
        status shouldEqual StatusCodes.NotFound
        response.asJson shouldEqual jsonContentOf("errors/tag-not-found.json", "tag" -> "myother")
      }
    }

    "reject if provided rev and tag simultaneously" in {
      Get("/v1/schemas/myorg/myproject/myid2?tag=mytag&rev=1") ~> as(reader) ~> routes ~> check {
        status shouldEqual StatusCodes.BadRequest
        response.asJson shouldEqual jsonContentOf("errors/tag-and-rev-error.json")
      }
    }

    "redirect to fusion with a given rev if the Accept header is set to text/html" in {
      Get("/v1/schemas/myorg/myproject/myid2?rev=5") ~> Accept(`text/html`) ~> as(reader) ~> routes ~> check {
        response.status shouldEqual StatusCodes.SeeOther
        response.header[Location].value.uri shouldEqual Uri(
          "https://bbp.epfl.ch/nexus/web/myorg/myproject/resources/myid2"
        ).withQuery(Uri.Query("rev" -> "5"))
      }
    }

    def givenASchema(test: String => Assertion): Assertion = {
      val id      = genString()
      val payload = jsonContentOf("resources/schema.json") deepMerge json"""{"@id": "${nxv + id}"}"""
      Put(s"/v1/schemas/myorg/myproject/$id", payload.toEntity) ~> as(writer) ~> routes ~> check {
        status shouldEqual StatusCodes.Created
      }
      test(id)
    }

    def givenADeprecatedSchema(test: String => Assertion): Assertion = {
      givenASchema { schema =>
        Delete(s"/v1/schemas/myorg/myproject/$schema?rev=1") ~> as(writer) ~> routes ~> check {
          status shouldEqual StatusCodes.OK
        }
        test(schema)
      }
    }
  }

  private def schemaMetadata(
      project: ProjectRef,
      id: Iri,
      rev: Int = 1,
      deprecated: Boolean = false,
      createdBy: Subject = writer,
      updatedBy: Subject = writer
  ): Json    =
    jsonContentOf(
      "schemas/schema-route-metadata-response.json",
      "project"    -> project,
      "id"         -> id,
      "rev"        -> rev,
      "deprecated" -> deprecated,
      "createdBy"  -> createdBy.asIri,
      "updatedBy"  -> updatedBy.asIri,
      "self"       -> ResourceAccess.schema(project, id).uri
    )
}
