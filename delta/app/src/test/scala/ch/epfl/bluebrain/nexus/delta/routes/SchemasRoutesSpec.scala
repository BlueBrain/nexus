package ch.epfl.bluebrain.nexus.delta.routes

import java.util.UUID

import akka.http.scaladsl.model.MediaTypes.`text/event-stream`
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.model.headers.{`Last-Event-ID`, OAuth2BearerToken}
import akka.http.scaladsl.server.Route
import ch.epfl.bluebrain.nexus.delta.kernel.utils.UrlUtils
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.{contexts, nxv}
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.JsonLdContext.keywords
import ch.epfl.bluebrain.nexus.delta.sdk.Permissions.{events, resources, schemas}
import ch.epfl.bluebrain.nexus.delta.sdk.generators.{ProjectGen, SchemaGen}
import ch.epfl.bluebrain.nexus.delta.sdk.model.Label
import ch.epfl.bluebrain.nexus.delta.sdk.model.acls.{Acl, AclAddress}
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Identity.{Anonymous, Authenticated, Group, Subject}
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.{AuthToken, Caller, Identity}
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.ApiMappings
import ch.epfl.bluebrain.nexus.delta.sdk.testkit._
import ch.epfl.bluebrain.nexus.delta.sdk.utils.UUIDF
import ch.epfl.bluebrain.nexus.delta.syntax._
import ch.epfl.bluebrain.nexus.delta.utils.{RouteFixtures, RouteHelpers}
import ch.epfl.bluebrain.nexus.testkit._
import org.scalatest.matchers.should.Matchers
import org.scalatest.{CancelAfterFailure, Inspectors, OptionValues}

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

  private val myId        = nxv + "myid"
  private val myIdEncoded = UrlUtils.encode(myId.toString)
  val payload             = jsonContentOf("resources/schema.json") deepMerge json"""{"@id": "$myId"}"""
  val payloadNoId         = payload.removeKeys(keywords.id)
  val schema              = SchemaGen.schema(myId, projectRef, payload)
  val current             = SchemaGen.currentState(schema, subject = subject)
  val payloadUpdated      = payloadNoId.replace("datatype" -> "xsd:integer", "xsd:double")
  val schemaUpdated       = SchemaGen.schema(myId, projectRef, payloadUpdated)

  private val (orgs, projs) =
    ProjectSetup.init(orgsToCreate = List(org), projectsToCreate = List(project.value)).accepted

  private val acls = AclsDummy(PermissionsDummy(Set(schemas.write, schemas.read, events.read))).accepted

  private val routes =
    Route.seal(SchemasRoutes(identities, acls, orgs, projs, SchemasDummy(orgs, projs).accepted))

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
        response.asJson shouldEqual schemaResourceUnit(projectRef, myId, am = am)
      }
    }

    "create a schema with an authenticated user and provided id" in {
      val endpoints = List(
        ("/v1/schemas/myorg/myproject/myid2", nxv + "myid2", payloadNoId),
        ("/v1/resources/myorg/myproject/schema/myid3", nxv + "myid3", payloadNoId)
      )
      forAll(endpoints) { case (endpoint, id, payload) =>
        println(endpoint)
        Put(endpoint, payload.toEntity) ~> asAlice ~> routes ~> check {
          status shouldEqual StatusCodes.Created
          response.asJson shouldEqual schemaResourceUnit(projectRef, id, am = am, createdBy = alice, updatedBy = alice)
        }
      }
    }

    "reject the creation of a schema using the underscore route" in {
      Put("/v1/resources/myorg/myproject/_/myother", payload.toEntity) ~> routes ~> check {
        response.asJson shouldEqual jsonContentOf("/errors/default-not-found.json")
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
      val endpoints = List(
        "/v1/resources/myorg/myproject/_/myid",
        "/v1/resources/myorg/myproject/schema/myid",
        "/v1/schemas/myorg/myproject/myid"
      )
      forAll(endpoints) { endpoint =>
        Put(s"$endpoint?rev=1", payload.toEntity) ~> routes ~> check {
          response.status shouldEqual StatusCodes.Forbidden
          response.asJson shouldEqual jsonContentOf("errors/authorization-failed.json")
        }
      }

    }

    "update a schema" in {
      acls.append(Acl(AclAddress.Root, Anonymous -> Set(schemas.write)), 3L).accepted
      val encodedSchema = UrlUtils.encode(Vocabulary.schemas.shacl.toString)
      val endpoints     = List(
        "/v1/resources/myorg/myproject/_/myid",
        s"/v1/resources/myorg/myproject/_/$myIdEncoded",
        "/v1/resources/myorg/myproject/schema/myid",
        s"/v1/resources/myorg/myproject/$encodedSchema/myid",
        s"/v1/schemas/myorg/myproject/myid",
        s"/v1/schemas/myorg/myproject/$myIdEncoded"
      )
      forAll(endpoints.zipWithIndex) { case (endpoint, idx) =>
        Put(s"$endpoint?rev=${idx + 1}", payloadUpdated.toEntity) ~> routes ~> check {
          status shouldEqual StatusCodes.OK
          response.asJson shouldEqual schemaResourceUnit(projectRef, myId, rev = idx + 2L, am = am)
        }
      }
    }

    "reject the update of a non-existent schema" in {
      val endpoints = List(
        "/v1/resources/myorg/myproject/_/myid10",
        "/v1/resources/myorg/myproject/schema/myid10",
        s"/v1/schemas/myorg/myproject/myid10"
      )
      val payload   = payloadUpdated.removeKeys(keywords.id)
      forAll(endpoints) { endpoint =>
        Put(s"$endpoint?rev=1", payload.toEntity) ~> routes ~> check {
          status shouldEqual StatusCodes.NotFound
          response.asJson shouldEqual jsonContentOf("/schemas/errors/not-found.json", "id" -> (nxv + "myid10"))
        }
      }

    }

    "reject the update of a schema at a non-existent revision" in {
      val endpoints = List(
        "/v1/resources/myorg/myproject/_/myid",
        "/v1/resources/myorg/myproject/schema/myid",
        s"/v1/schemas/myorg/myproject/myid"
      )
      forAll(endpoints) { endpoint =>
        Put(s"$endpoint?rev=10", payloadUpdated.toEntity) ~> routes ~> check {
          status shouldEqual StatusCodes.Conflict
          response.asJson shouldEqual
            jsonContentOf("/schemas/errors/incorrect-rev.json", "provided" -> 10L, "expected" -> 7L)
        }
      }

    }

    "fail to deprecate a schema without schemas/write permission" in {
      acls.subtract(Acl(AclAddress.Root, Anonymous -> Set(schemas.write)), 4L).accepted
      val endpoints = List(
        "/v1/resources/myorg/myproject/_/myid",
        "/v1/resources/myorg/myproject/schema/myid",
        "/v1/schemas/myorg/myproject/myid"
      )
      forAll(endpoints) { endpoint =>
        Delete(s"$endpoint?rev=7") ~> routes ~> check {
          response.status shouldEqual StatusCodes.Forbidden
          response.asJson shouldEqual jsonContentOf("errors/authorization-failed.json")
        }
      }
    }

    "deprecate a schema" in {
      acls.append(Acl(AclAddress.Root, Anonymous -> Set(schemas.write)), 5L).accepted
      Delete("/v1/schemas/myorg/myproject/myid?rev=7") ~> routes ~> check {
        status shouldEqual StatusCodes.OK
        response.asJson shouldEqual schemaResourceUnit(projectRef, myId, rev = 8L, am = am, deprecated = true)
      }
    }

    "reject the deprecation of a schema without rev" in {
      Delete("/v1/schemas/myorg/myproject/myid") ~> routes ~> check {
        status shouldEqual StatusCodes.BadRequest
        response.asJson shouldEqual jsonContentOf("/errors/missing-query-param.json", "field" -> "rev")
      }
    }

    "reject the deprecation of a already deprecated schema" in {
      val endpoints = List(
        "/v1/resources/myorg/myproject/_/myid",
        "/v1/resources/myorg/myproject/schema/myid",
        "/v1/schemas/myorg/myproject/myid"
      )
      forAll(endpoints) { endpoint =>
        Delete(s"$endpoint?rev=8") ~> routes ~> check {
          status shouldEqual StatusCodes.BadRequest
          response.asJson shouldEqual jsonContentOf("/schemas/errors/schema-deprecated.json", "id" -> myId)
        }
      }

    }

    "tag a schema" in {
      val payload = json"""{"tag": "mytag", "rev": 1}"""
      Post("/v1/schemas/myorg/myproject/myid2/tags?rev=1", payload.toEntity) ~> routes ~> check {
        status shouldEqual StatusCodes.OK
        response.asJson shouldEqual schemaResourceUnit(projectRef, nxv + "myid2", rev = 2, am = am, createdBy = alice)
      }
    }

    "fail to fetch a schema without schemas/read permission" in {
      val endpoints = List(
        "/v1/resources/myorg/myproject/_/myid",
        s"/v1/resources/myorg/myproject/schema/$myIdEncoded",
        "/v1/resources/myorg/myproject/_/myid?rev=3",
        "/v1/resources/myorg/myproject/_/myid2?tag=mytag",
        "/v1/resources/myorg/myproject/_/myid",
        s"/v1/resources/myorg/myproject/schema/$myIdEncoded/source",
        "/v1/resources/myorg/myproject/_/myid/source?rev=3",
        "/v1/resources/myorg/myproject/_/myid2/source?tag=mytag",
        "/v1/schemas/myorg/myproject/myid2?tag=mytag",
        "/v1/schemas/myorg/myproject/myid2?rev=1",
        "/v1/schemas/myorg/myproject/myid2"
      )
      forAll(endpoints) { endpoint =>
        Get(endpoint) ~> routes ~> check {
          response.status shouldEqual StatusCodes.Forbidden
          response.asJson shouldEqual jsonContentOf("errors/authorization-failed.json")
        }
      }
    }

    val resourceCtx = payload.topContextValueOrEmpty.contextObj.addContext(contexts.metadata)
    "fetch a schema" in {
      acls.append(Acl(AclAddress.Root, Anonymous -> Set(resources.read)), 6L).accepted
      Get("/v1/schemas/myorg/myproject/myid") ~> routes ~> check {
        status shouldEqual StatusCodes.OK
        val meta = schemaResourceUnit(projectRef, myId, deprecated = true, rev = 8L, am = am)
        response.asJson shouldEqual payloadUpdated.deepMerge(meta).deepMerge(resourceCtx)
      }
    }

    "fetch a schema by rev and tag" in {
      val endpoints = List(
        "/v1/resources/myorg/myproject/_/myid2",
        "/v1/resources/myorg/myproject/schema/myid2",
        s"/v1/resources/$uuid/$uuid/_/myid2",
        s"/v1/resources/$uuid/$uuid/schema/myid2",
        s"/v1/schemas/$uuid/$uuid/myid2",
        "/v1/schemas/myorg/myproject/myid2"
      )
      val meta      = schemaResourceUnit(projectRef, nxv + "myid2", am = am, createdBy = alice, updatedBy = alice)
      forAll(endpoints) { endpoint =>
        forAll(List("rev=1", "tag=mytag")) { param =>
          Get(s"$endpoint?$param") ~> routes ~> check {
            status shouldEqual StatusCodes.OK
            response.asJson shouldEqual payload.deepMerge(meta).deepMerge(resourceCtx)
          }
        }
      }
    }

    "fetch a schema original payload" in {
      val endpoints = List(
        "/v1/resources/myorg/myproject/_/myid2/source",
        "/v1/resources/myorg/myproject/schema/myid2/source",
        s"/v1/resources/$uuid/$uuid/_/myid2/source",
        s"/v1/resources/$uuid/$uuid/schema/myid2/source",
        s"/v1/schemas/$uuid/$uuid/myid2/source",
        "/v1/schemas/myorg/myproject/myid2/source"
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
        "/v1/resources/myorg/myproject/_/myid2/source",
        "/v1/resources/myorg/myproject/schema/myid2/source",
        s"/v1/resources/$uuid/$uuid/_/myid2/source",
        s"/v1/resources/$uuid/$uuid/schema/myid2/source",
        s"/v1/schemas/$uuid/$uuid/myid2/source",
        "/v1/schemas/myorg/myproject/myid2/source"
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
        Get(endpoint) ~> `Last-Event-ID`("8") ~> routes ~> check {
          mediaType shouldBe `text/event-stream`
          response.asString.strip shouldEqual contentOf("/schemas/eventstream-9-11.txt").strip
        }
      }
    }
  }
}
