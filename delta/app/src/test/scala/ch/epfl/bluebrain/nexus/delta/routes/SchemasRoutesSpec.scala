package ch.epfl.bluebrain.nexus.delta.routes

import akka.http.scaladsl.model.MediaTypes.`text/event-stream`
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.model.headers.{`Last-Event-ID`, OAuth2BearerToken}
import akka.http.scaladsl.server.Route
import ch.epfl.bluebrain.nexus.delta.kernel.utils.UrlUtils
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.{contexts, nxv}
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.JsonLdContext.keywords
import ch.epfl.bluebrain.nexus.delta.sdk.Permissions.{events, resources, schemas}
import ch.epfl.bluebrain.nexus.delta.sdk.SchemaImports
import ch.epfl.bluebrain.nexus.delta.sdk.generators.ProjectGen
import ch.epfl.bluebrain.nexus.delta.sdk.model.Label
import ch.epfl.bluebrain.nexus.delta.sdk.model.acls.{Acl, AclAddress}
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Identity.{Anonymous, Authenticated, Group, Subject}
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.{AuthToken, Caller, Identity}
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.ApiMappings
import ch.epfl.bluebrain.nexus.delta.sdk.model.resolvers.{ResolverContextResolution, ResourceResolutionReport}
import ch.epfl.bluebrain.nexus.delta.sdk.testkit._
import ch.epfl.bluebrain.nexus.delta.sdk.utils.{RouteHelpers, UUIDF}
import ch.epfl.bluebrain.nexus.delta.sdk.syntax._
import ch.epfl.bluebrain.nexus.delta.utils.RouteFixtures
import ch.epfl.bluebrain.nexus.testkit._
import monix.bio.IO
import org.scalatest.matchers.should.Matchers
import org.scalatest.{CancelAfterFailure, Inspectors, OptionValues}

import java.util.UUID

class SchemasRoutesSpec
    extends RouteHelpers
    with Matchers
    with CirceLiteral
    with CirceEq
    with IOFixedClock
    with IOValues
    with OptionValues
    with TestMatchers
    with Inspectors
    with CancelAfterFailure
    with RouteFixtures {

  private val uuid                  = UUID.randomUUID()
  implicit private val uuidF: UUIDF = UUIDF.fixed(uuid)

  implicit private val subject: Subject = Identity.Anonymous

  private val caller = Caller(alice, Set(alice, Anonymous, Authenticated(realm), Group("group", realm)))

  private val identities = IdentitiesDummy(Map(AuthToken("alice") -> caller))

  private val asAlice = addCredentials(OAuth2BearerToken("alice"))

  private val org        = Label.unsafe("myorg")
  private val am         = ApiMappings(Map("nxv" -> nxv.base))
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

  private val (orgs, projs) =
    ProjectSetup.init(orgsToCreate = List(org), projectsToCreate = List(project.value)).accepted

  private val schemaImports = new SchemaImports(
    (_, _, _) => IO.raiseError(ResourceResolutionReport()),
    (_, _, _) => IO.raiseError(ResourceResolutionReport())
  )

  val resolverContextResolution: ResolverContextResolution = new ResolverContextResolution(
    rcr,
    (_, _, _) => IO.raiseError(ResourceResolutionReport())
  )

  private val acls = AclsDummy(PermissionsDummy(Set(schemas.write, schemas.read, events.read))).accepted

  private val routes =
    Route.seal(
      SchemasRoutes(
        identities,
        acls,
        orgs,
        projs,
        SchemasDummy(orgs, projs, schemaImports, resolverContextResolution).accepted
      )
    )

  "A schema route" should {

    "fail to create a schema without schemas/write permission" in {
      acls.append(Acl(AclAddress.Root, Anonymous -> Set(events.read)), 0L).accepted
      Post("/v1/schemas/myorg/myproject", payload.toEntity) ~> routes ~> check {
        response.status shouldEqual StatusCodes.Forbidden
        response.asJson shouldEqual jsonContentOf("errors/authorization-failed.json")
      }
    }

    "create a schema" in {
      acls
        .append(Acl(AclAddress.Root, Anonymous -> Set(schemas.write), caller.subject -> Set(schemas.write)), 1L)
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
        response.asJson shouldEqual jsonContentOf("/schemas/errors/already-exists.json", "id" -> myId)
      }
    }

    "fail to update a schema without schemas/write permission" in {
      acls.subtract(Acl(AclAddress.Root, Anonymous -> Set(schemas.write)), 2L).accepted
      Put(s"/v1/schemas/myorg/myproject/myid?rev=1", payload.toEntity) ~> routes ~> check {
        response.status shouldEqual StatusCodes.Forbidden
        response.asJson shouldEqual jsonContentOf("errors/authorization-failed.json")
      }
    }

    "update a schema" in {
      acls.append(Acl(AclAddress.Root, Anonymous -> Set(schemas.write)), 3L).accepted
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
      acls.subtract(Acl(AclAddress.Root, Anonymous -> Set(schemas.write)), 4L).accepted
      Delete("/v1/schemas/myorg/myproject/myid?rev=3") ~> routes ~> check {
        response.status shouldEqual StatusCodes.Forbidden
        response.asJson shouldEqual jsonContentOf("errors/authorization-failed.json")
      }
    }

    "deprecate a schema" in {
      acls.append(Acl(AclAddress.Root, Anonymous -> Set(schemas.write)), 5L).accepted
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
      acls.append(Acl(AclAddress.Root, Anonymous -> Set(resources.read)), 6L).accepted
      Get("/v1/schemas/myorg/myproject/myid") ~> routes ~> check {
        status shouldEqual StatusCodes.OK
        response.asJson shouldEqual jsonContentOf("schemas/schema-updated-response.json", "id" -> "nxv:myid")
      }
    }

    "fetch a schema by rev and tag" in {
      val endpoints = List(
        s"/v1/schemas/$uuid/$uuid/myid2",
        "/v1/schemas/myorg/myproject/myid2",
        s"/v1/schemas/myorg/myproject/$myId2Encoded"
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
        "/v1/schemas/myorg/myproject/myid2/source",
        s"/v1/schemas/myorg/myproject/$myId2Encoded/source"
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
        "/v1/schemas/myorg/myproject/myid2/source",
        s"/v1/schemas/myorg/myproject/$myId2Encoded/source"
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
      Get("/v1/schemas/myorg/myproject/myid2/tags") ~> routes ~> check {
        status shouldEqual StatusCodes.OK
        response.asJson shouldEqual json"""{"tags": [{"rev": 1, "tag": "mytag"}]}""".addContext(contexts.tags)
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
      acls.subtract(Acl(AclAddress.Root, Anonymous -> Set(events.read)), 7L).accepted
      forAll(List("/v1/schemas/events", "/v1/schemas/myorg/events", "/v1/schemas/myorg/myproject/events")) { endpoint =>
        Get(endpoint) ~> `Last-Event-ID`("2") ~> routes ~> check {
          response.status shouldEqual StatusCodes.Forbidden
          response.asJson shouldEqual jsonContentOf("errors/authorization-failed.json")
        }
      }
    }

    "get the events stream with an offset" in {
      acls.append(Acl(AclAddress.Root, Anonymous -> Set(events.read)), 8L).accepted
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
          response.asString.strip shouldEqual contentOf("/schemas/eventstream-2-6.txt").strip
        }
      }
    }
  }
}
