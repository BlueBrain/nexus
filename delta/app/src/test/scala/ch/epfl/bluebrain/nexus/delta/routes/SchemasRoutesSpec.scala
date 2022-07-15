package ch.epfl.bluebrain.nexus.delta.routes

import akka.http.scaladsl.model.MediaTypes.{`text/event-stream`, `text/html`}
import akka.http.scaladsl.model.headers.{`Last-Event-ID`, Accept, Location, OAuth2BearerToken}
import akka.http.scaladsl.model.{StatusCodes, Uri}
import akka.http.scaladsl.server.Route
import ch.epfl.bluebrain.nexus.delta.kernel.utils.{UUIDF, UrlUtils}
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.{contexts, nxv}
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.JsonLdContext.keywords
import ch.epfl.bluebrain.nexus.delta.sdk.acls.AclSimpleCheck
import ch.epfl.bluebrain.nexus.delta.sdk.acls.model.AclAddress
import ch.epfl.bluebrain.nexus.delta.sdk.directives.DeltaSchemeDirectives
import ch.epfl.bluebrain.nexus.delta.sdk.generators.ProjectGen
import ch.epfl.bluebrain.nexus.delta.sdk.identities.IdentitiesDummy
import ch.epfl.bluebrain.nexus.delta.sdk.identities.model.Caller
import ch.epfl.bluebrain.nexus.delta.sdk.permissions.Permissions.{events, resources, schemas}
import ch.epfl.bluebrain.nexus.delta.sdk.projects.FetchContextDummy
import ch.epfl.bluebrain.nexus.delta.sdk.projects.model.ApiMappings
import ch.epfl.bluebrain.nexus.delta.sdk.resolvers.ResolverContextResolution
import ch.epfl.bluebrain.nexus.delta.sdk.resolvers.model.ResourceResolutionReport
import ch.epfl.bluebrain.nexus.delta.sdk.schemas.model.SchemaRejection.ProjectContextRejection
import ch.epfl.bluebrain.nexus.delta.sdk.schemas.{SchemaImports, SchemasConfig, SchemasImpl}
import ch.epfl.bluebrain.nexus.delta.sdk.syntax._
import ch.epfl.bluebrain.nexus.delta.sdk.testkit._
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.{Anonymous, Authenticated, Group}
import monix.bio.IO

import java.util.UUID

class SchemasRoutesSpec extends BaseRouteSpec {

  private val uuid                  = UUID.randomUUID()
  implicit private val uuidF: UUIDF = UUIDF.fixed(uuid)

  private val caller = Caller(alice, Set(alice, Anonymous, Authenticated(realm), Group("group", realm)))

  private val identities = IdentitiesDummy(caller)

  private val asAlice = addCredentials(OAuth2BearerToken("alice"))

  private val am         = ApiMappings("nxv" -> nxv.base, "schema" -> Vocabulary.schemas.shacl)
  private val projBase   = nxv.base
  private val project    = ProjectGen.resourceFor(
    ProjectGen.project("myorg", "myproject", uuid = uuid, orgUuid = uuid, base = projBase, mappings = am)
  )
  private val projectRef = project.value.ref

  private val myId           = nxv + "myid"
  private val myIdEncoded    = UrlUtils.encode(myId.toString)
  private val myId2          = nxv + "myid2"
  private val myId2Encoded   = UrlUtils.encode(myId2.toString)
  private val payload        = jsonContentOf("resources/schema.json") deepMerge json"""{"@id": "$myId"}"""
  private val payloadNoId    = payload.removeKeys(keywords.id)
  private val payloadUpdated = payloadNoId.replace("datatype" -> "xsd:integer", "xsd:double")

  private val schemaImports = new SchemaImports(
    (_, _, _) => IO.raiseError(ResourceResolutionReport()),
    (_, _, _) => IO.raiseError(ResourceResolutionReport())
  )

  private val resolverContextResolution: ResolverContextResolution = new ResolverContextResolution(
    rcr,
    (_, _, _) => IO.raiseError(ResourceResolutionReport())
  )

  private lazy val aclCheck = AclSimpleCheck().accepted

  private val fetchContext    = FetchContextDummy(List(project.value), ProjectContextRejection)
  private val groupDirectives =
    DeltaSchemeDirectives(fetchContext, ioFromMap(uuid -> projectRef.organization), ioFromMap(uuid -> projectRef))

  private val config          = SchemasConfig(eventLogConfig)

  private lazy val routes =
    Route.seal(
      SchemasRoutes(
        identities,
        aclCheck,
        SchemasImpl(fetchContext, schemaImports, resolverContextResolution, config, xas),
        groupDirectives,
        IndexingActionDummy()
      )
    )

  "A schema route" should {

    "fail to create a schema without schemas/write permission" in {
      aclCheck.append(AclAddress.Root, Anonymous -> Set(events.read)).accepted
      Post("/v1/schemas/myorg/myproject", payload.toEntity) ~> routes ~> check {
        response.status shouldEqual StatusCodes.Forbidden
        response.asJson shouldEqual jsonContentOf("errors/authorization-failed.json")
      }
    }

    "create a schema" in {
      aclCheck
        .append(AclAddress.Root, Anonymous -> Set(schemas.write), caller.subject -> Set(schemas.write))
        .accepted
      Post("/v1/schemas/myorg/myproject", payload.toEntity) ~> routes ~> check {
        status shouldEqual StatusCodes.Created
        response.asJson shouldEqual schemaMetadata(projectRef, myId)
      }
    }

    "create a schema with an authenticated user and provided id" in {
      Put("/v1/schemas/myorg/myproject/myid2", payloadNoId.toEntity) ~> asAlice ~> routes ~> check {
        status shouldEqual StatusCodes.Created
        response.asJson shouldEqual
          schemaMetadata(projectRef, myId2, createdBy = alice, updatedBy = alice)
      }
    }

    "reject the creation of a schema which already exists" in {
      Put("/v1/schemas/myorg/myproject/myid", payload.toEntity) ~> routes ~> check {
        status shouldEqual StatusCodes.Conflict
        response.asJson shouldEqual
          jsonContentOf("/schemas/errors/already-exists.json", "id" -> myId, "project" -> "myorg/myproject")
      }
    }

    "fail to create a schema that does not validate against the SHACL schema" in {
      Put(
        s"/v1/schemas/myorg/myproject/myidwrong",
        payload.removeKeys(keywords.id).replaceKeyWithValue("nodeKind", 42).toEntity
      ) ~> routes ~> check {
        response.status shouldEqual StatusCodes.BadRequest
        response.asJson shouldEqual jsonContentOf("/schemas/errors/invalid-schema.json")
      }
    }

    "fail to update a schema without schemas/write permission" in {
      aclCheck.subtract(AclAddress.Root, Anonymous -> Set(schemas.write)).accepted
      Put(s"/v1/schemas/myorg/myproject/myid?rev=1", payload.toEntity) ~> routes ~> check {
        response.status shouldEqual StatusCodes.Forbidden
        response.asJson shouldEqual jsonContentOf("errors/authorization-failed.json")
      }
    }

    "update a schema" in {
      aclCheck.append(AclAddress.Root, Anonymous -> Set(schemas.write)).accepted
      val endpoints = List(
        "/v1/schemas/myorg/myproject/myid",
        s"/v1/schemas/myorg/myproject/$myIdEncoded"
      )
      forAll(endpoints.zipWithIndex) { case (endpoint, idx) =>
        Put(s"$endpoint?rev=${idx + 1}", payloadUpdated.toEntity) ~> routes ~> check {
          status shouldEqual StatusCodes.OK
          response.asJson shouldEqual schemaMetadata(projectRef, myId, rev = idx + 2L)
        }
      }
    }

    "reject the update of a non-existent schema" in {
      val payload = payloadUpdated.removeKeys(keywords.id)
      Put("/v1/schemas/myorg/myproject/myid10?rev=1", payload.toEntity) ~> routes ~> check {
        status shouldEqual StatusCodes.NotFound
        response.asJson shouldEqual
          jsonContentOf("/schemas/errors/not-found.json", "id" -> (nxv + "myid10"), "proj" -> "myorg/myproject")
      }
    }

    "reject the update of a schema at a non-existent revision" in {
      Put("/v1/schemas/myorg/myproject/myid?rev=10", payloadUpdated.toEntity) ~> routes ~> check {
        status shouldEqual StatusCodes.Conflict
        response.asJson shouldEqual
          jsonContentOf("/schemas/errors/incorrect-rev.json", "provided" -> 10L, "expected" -> 3L)
      }
    }

    "fail to deprecate a schema without schemas/write permission" in {
      aclCheck.subtract(AclAddress.Root, Anonymous -> Set(schemas.write)).accepted
      Delete("/v1/schemas/myorg/myproject/myid?rev=3") ~> routes ~> check {
        response.status shouldEqual StatusCodes.Forbidden
        response.asJson shouldEqual jsonContentOf("errors/authorization-failed.json")
      }
    }

    "deprecate a schema" in {
      aclCheck.append(AclAddress.Root, Anonymous -> Set(schemas.write)).accepted
      Delete("/v1/schemas/myorg/myproject/myid?rev=3") ~> routes ~> check {
        status shouldEqual StatusCodes.OK
        response.asJson shouldEqual schemaMetadata(projectRef, myId, rev = 4L, deprecated = true)
      }
    }

    "reject the deprecation of a schema without rev" in {
      Delete("/v1/schemas/myorg/myproject/myid") ~> routes ~> check {
        status shouldEqual StatusCodes.BadRequest
        response.asJson shouldEqual jsonContentOf("/errors/missing-query-param.json", "field" -> "rev")
      }
    }

    "reject the deprecation of a already deprecated schema" in {
      Delete(s"/v1/schemas/myorg/myproject/myid?rev=4") ~> routes ~> check {
        status shouldEqual StatusCodes.BadRequest
        response.asJson shouldEqual jsonContentOf("/schemas/errors/schema-deprecated.json", "id" -> myId)
      }
    }

    "tag a schema" in {
      val payload = json"""{"tag": "mytag", "rev": 1}"""
      Post("/v1/schemas/myorg/myproject/myid2/tags?rev=1", payload.toEntity) ~> routes ~> check {
        status shouldEqual StatusCodes.Created
        response.asJson shouldEqual schemaMetadata(projectRef, myId2, rev = 2, createdBy = alice)
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
            response.status shouldEqual StatusCodes.Forbidden
            response.asJson shouldEqual jsonContentOf("errors/authorization-failed.json")
          }
        }
      }
    }

    "fetch a schema" in {
      aclCheck.append(AclAddress.Root, Anonymous -> Set(resources.read)).accepted
      val endpoints = List("/v1/schemas/myorg/myproject/myid", "/v1/resources/myorg/myproject/_/myid")
      forAll(endpoints) { endpoint =>
        Get(endpoint) ~> routes ~> check {
          status shouldEqual StatusCodes.OK
          response.asJson shouldEqual jsonContentOf("schemas/schema-updated-response.json", "id" -> "nxv:myid")
        }
      }
    }

    "fetch a schema by rev and tag" in {
      val endpoints = List(
        s"/v1/schemas/$uuid/$uuid/myid2",
        s"/v1/resources/$uuid/$uuid/_/myid2",
        s"/v1/resources/$uuid/$uuid/schema/myid2",
        "/v1/schemas/myorg/myproject/myid2",
        "/v1/resources/myorg/myproject/_/myid2",
        "/v1/resources/myorg/myproject/schema/myid2",
        s"/v1/schemas/myorg/myproject/$myId2Encoded",
        s"/v1/resources/myorg/myproject/_/$myId2Encoded",
        s"/v1/resources/myorg/myproject/schema/$myId2Encoded"
      )
      forAll(endpoints) { endpoint =>
        forAll(List("rev=1", "tag=mytag")) { param =>
          Get(s"$endpoint?$param") ~> routes ~> check {
            status shouldEqual StatusCodes.OK
            response.asJson shouldEqual jsonContentOf("schemas/schema-created-response.json", "id" -> "nxv:myid2")
          }
        }
      }
    }

    "fetch a schema original payload" in {
      val endpoints = List(
        s"/v1/schemas/$uuid/$uuid/myid2/source",
        s"/v1/resources/$uuid/$uuid/_/myid2/source",
        s"/v1/resources/$uuid/$uuid/schema/myid2/source",
        "/v1/schemas/myorg/myproject/myid2/source",
        "/v1/resources/myorg/myproject/_/myid2/source",
        "/v1/resources/myorg/myproject/schema/myid2/source",
        s"/v1/schemas/myorg/myproject/$myId2Encoded/source",
        s"/v1/resources/myorg/myproject/_/$myId2Encoded/source",
        s"/v1/resources/myorg/myproject/schema/$myId2Encoded/source"
      )
      forAll(endpoints) { endpoint =>
        Get(endpoint) ~> routes ~> check {
          status shouldEqual StatusCodes.OK
          response.asJson shouldEqual payloadNoId
        }
      }
    }
    "fetch a schema original payload by rev or tag" in {
      val endpoints = List(
        s"/v1/schemas/$uuid/$uuid/myid2/source",
        s"/v1/resources/$uuid/$uuid/_/myid2/source",
        "/v1/schemas/myorg/myproject/myid2/source",
        "/v1/resources/myorg/myproject/_/myid2/source",
        "/v1/resources/myorg/myproject/schema/myid2/source",
        s"/v1/schemas/myorg/myproject/$myId2Encoded/source",
        s"/v1/resources/myorg/myproject/_/$myId2Encoded/source",
        s"/v1/resources/myorg/myproject/schema/$myId2Encoded/source"
      )
      forAll(endpoints) { endpoint =>
        forAll(List("rev=1", "tag=mytag")) { param =>
          Get(s"$endpoint?$param") ~> routes ~> check {
            status shouldEqual StatusCodes.OK
            response.asJson shouldEqual payloadNoId
          }
        }
      }
    }

    "fetch the schema tags" in {
      Get("/v1/schemas/myorg/myproject/myid2/tags?rev=1") ~> routes ~> check {
        status shouldEqual StatusCodes.OK
        response.asJson shouldEqual json"""{"tags": []}""".addContext(contexts.tags)
      }
      Get("/v1/resources/myorg/myproject/_/myid2/tags") ~> routes ~> check {
        status shouldEqual StatusCodes.OK
        response.asJson shouldEqual json"""{"tags": [{"rev": 1, "tag": "mytag"}]}""".addContext(contexts.tags)
      }
    }

    "delete a tag on schema" in {
      Delete("/v1/schemas/myorg/myproject/myid2/tags/mytag?rev=2") ~> routes ~> check {
        status shouldEqual StatusCodes.OK
        response.asJson shouldEqual schemaMetadata(projectRef, myId2, rev = 3, createdBy = alice)
      }
    }

    "not return the deleted tag" in {
      Get("/v1/schemas/myorg/myproject/myid2/tags") ~> routes ~> check {
        status shouldEqual StatusCodes.OK
        response.asJson shouldEqual json"""{"tags": []}""".addContext(contexts.tags)
      }
    }

    "fail to fetch resource by the deleted tag" in {
      Get("/v1/schemas/myorg/myproject/myid2?tag=mytag") ~> routes ~> check {
        status shouldEqual StatusCodes.NotFound
        response.asJson shouldEqual jsonContentOf("/errors/tag-not-found.json", "tag" -> "mytag")
      }
    }

    "return not found if tag not found" in {
      Get("/v1/schemas/myorg/myproject/myid2?tag=myother") ~> routes ~> check {
        status shouldEqual StatusCodes.NotFound
        response.asJson shouldEqual jsonContentOf("/errors/tag-not-found.json", "tag" -> "myother")
      }
    }

    "reject if provided rev and tag simultaneously" in {
      Get("/v1/schemas/myorg/myproject/myid2?tag=mytag&rev=1") ~> routes ~> check {
        status shouldEqual StatusCodes.BadRequest
        response.asJson shouldEqual jsonContentOf("/errors/tag-and-rev-error.json")
      }
    }

    "fail to get the events stream without events/read permission" in {
      aclCheck.subtract(AclAddress.Root, Anonymous -> Set(events.read)).accepted
      forAll(List("/v1/schemas/events", "/v1/schemas/myorg/events", "/v1/schemas/myorg/myproject/events")) { endpoint =>
        Get(endpoint) ~> `Last-Event-ID`("2") ~> routes ~> check {
          response.status shouldEqual StatusCodes.Forbidden
          response.asJson shouldEqual jsonContentOf("errors/authorization-failed.json")
        }
      }
    }

    "get the events stream with an offset" ignore {
      aclCheck.append(AclAddress.Root, Anonymous -> Set(events.read)).accepted
      forAll(
        List(
          "/v1/schemas/events",
          "/v1/schemas/myorg/events",
          s"/v1/schemas/$uuid/events",
          "/v1/schemas/myorg/myproject/events"
        )
      ) { endpoint =>
        Get(endpoint) ~> `Last-Event-ID`("1") ~> routes ~> check {
          mediaType shouldBe `text/event-stream`
          response.asString.strip shouldEqual contentOf("/schemas/eventstream-2-6.txt", "uuid" -> uuid).strip
        }
      }
    }

    "redirect to fusion with a given rev if the Accept header is set to text/html" in {
      Get("/v1/schemas/myorg/myproject/myid2?rev=5") ~> Accept(`text/html`) ~> routes ~> check {
        response.status shouldEqual StatusCodes.SeeOther
        response.header[Location].value.uri shouldEqual Uri(
          "https://bbp.epfl.ch/nexus/web/myorg/myproject/resources/myid2"
        ).withQuery(Uri.Query("rev" -> "5"))
      }
    }
  }
}
