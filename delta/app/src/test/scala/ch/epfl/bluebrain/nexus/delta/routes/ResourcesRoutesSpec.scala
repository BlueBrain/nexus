package ch.epfl.bluebrain.nexus.delta.routes

import akka.http.scaladsl.model.MediaTypes.{`text/event-stream`, `text/html`}
import akka.http.scaladsl.model.headers.{`Last-Event-ID`, Accept, Location, OAuth2BearerToken}
import akka.http.scaladsl.model.{StatusCodes, Uri}
import akka.http.scaladsl.server.Route
import akka.persistence.query.Sequence
import ch.epfl.bluebrain.nexus.delta.kernel.utils.UrlUtils
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.{contexts, nxv, schema, schemas}
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.JsonLdContext.keywords
import ch.epfl.bluebrain.nexus.delta.sdk.JsonValue
import ch.epfl.bluebrain.nexus.delta.sdk.ResolverResolution.{FetchResource, ResourceResolution}
import ch.epfl.bluebrain.nexus.delta.sdk.acls.AclSimpleCheck
import ch.epfl.bluebrain.nexus.delta.sdk.acls.model.AclAddress
import ch.epfl.bluebrain.nexus.delta.sdk.directives.DeltaSchemeDirectives
import ch.epfl.bluebrain.nexus.delta.sdk.generators.{ProjectGen, ResourceResolutionGen, SchemaGen}
import ch.epfl.bluebrain.nexus.delta.sdk.identities.IdentitiesDummy
import ch.epfl.bluebrain.nexus.delta.sdk.identities.model.Caller
import ch.epfl.bluebrain.nexus.delta.sdk.model._
import ch.epfl.bluebrain.nexus.delta.sdk.model.resolvers.{ResolverContextResolution, ResourceResolutionReport}
import ch.epfl.bluebrain.nexus.delta.sdk.model.resources.ResourceEvent
import ch.epfl.bluebrain.nexus.delta.sdk.model.resources.ResourceEvent.{ResourceDeprecated, ResourceTagAdded}
import ch.epfl.bluebrain.nexus.delta.sdk.model.resources.ResourceRejection.ProjectContextRejection
import ch.epfl.bluebrain.nexus.delta.sdk.model.schemas.Schema
import ch.epfl.bluebrain.nexus.delta.sdk.permissions.Permissions.{events, resources}
import ch.epfl.bluebrain.nexus.delta.sdk.projects.FetchContextDummy
import ch.epfl.bluebrain.nexus.delta.sdk.projects.model.ApiMappings
import ch.epfl.bluebrain.nexus.delta.sdk.syntax._
import ch.epfl.bluebrain.nexus.delta.sdk.testkit._
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.{Anonymous, Authenticated, Group, Subject}
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Tag.UserTag
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{Identity, ProjectRef, ResourceRef}
import io.circe.Printer
import monix.bio.{IO, UIO}

import java.time.Instant
import java.util.UUID

class ResourcesRoutesSpec extends BaseRouteSpec {

  private val uuid = UUID.randomUUID()

  implicit private val subject: Subject = Identity.Anonymous

  implicit private val caller: Caller =
    Caller(alice, Set(alice, Anonymous, Authenticated(realm), Group("group", realm)))

  private val identities = IdentitiesDummy(caller)

  private val asAlice = addCredentials(OAuth2BearerToken("alice"))

  private val am           = ApiMappings("nxv" -> nxv.base, "Person" -> schema.Person)
  private val projBase     = nxv.base
  private val project      = ProjectGen.resourceFor(
    ProjectGen.project("myorg", "myproject", uuid = uuid, orgUuid = uuid, base = projBase, mappings = am)
  )
  private val projectRef   = project.value.ref
  private val schemaSource = jsonContentOf("resources/schema.json").addContext(contexts.shacl, contexts.schemasMetadata)
  private val schema1      = SchemaGen.schema(nxv + "myschema", project.value.ref, schemaSource.removeKeys(keywords.id))
  private val schema2      = SchemaGen.schema(schema.Person, project.value.ref, schemaSource.removeKeys(keywords.id))

  val resolverContextResolution: ResolverContextResolution = new ResolverContextResolution(
    rcr,
    (_, _, _) => IO.raiseError(ResourceResolutionReport())
  )

  private val fetchSchema: (ResourceRef, ProjectRef) => FetchResource[Schema] = {
    case (ref, _) if ref.iri == schema2.id => UIO.some(SchemaGen.resourceFor(schema2, deprecated = true))
    case (ref, _) if ref.iri == schema1.id => UIO.some(SchemaGen.resourceFor(schema1))
    case _                                 => UIO.none
  }

  private val myId         = nxv + "myid"  // Resource created against no schema with id present on the payload
  private val myId2        = nxv + "myid2" // Resource created against schema1 with id present on the payload
  private val myId3        = nxv + "myid3" // Resource created against no schema with id passed and present on the payload
  private val myId4        = nxv + "myid4" // Resource created against schema1 with id passed and present on the payload
  private val myIdEncoded  = UrlUtils.encode(myId.toString)
  private val myId2Encoded = UrlUtils.encode(myId2.toString)
  private val payload      = jsonContentOf("resources/resource.json", "id" -> myId)

  val resourceResolution: ResourceResolution[Schema] =
    ResourceResolutionGen.singleInProject(projectRef, fetchSchema)

  private val aclCheck = AclSimpleCheck().accepted

  private val resourcesDummy = null
  private val sseEventLog    = new SseEventLogDummy(
    List(
      Envelope(
        ResourceTagAdded(myId, projectRef, Set.empty, 1, UserTag.unsafe("mytag"), 1, Instant.EPOCH, subject),
        Sequence(1),
        "p1",
        1
      ),
      Envelope(ResourceDeprecated(myId, projectRef, Set.empty, 1, Instant.EPOCH, subject), Sequence(2), "p1", 2)
    ),
    { case ev: ResourceEvent => JsonValue(ev).asInstanceOf[JsonValue.Aux[Event]] }
  )

  private val fetchContext    = FetchContextDummy(List(project.value), ProjectContextRejection)
  private val groupDirectives =
    DeltaSchemeDirectives(fetchContext, ioFromMap(uuid -> projectRef.organization), ioFromMap(uuid -> projectRef))

  private val routes          =
    Route.seal(
      ResourcesRoutes(identities, aclCheck, resourcesDummy, groupDirectives, sseEventLog, IndexingActionDummy())
    )

  private val payloadUpdated = payload deepMerge json"""{"name": "Alice", "address": null}"""

  "A resource route" should {

    "fail to create a resource without resources/write permission" in {
      aclCheck.append(AclAddress.Root, Anonymous -> Set(events.read)).accepted
      Post("/v1/resources/myorg/myproject", payload.toEntity) ~> routes ~> check {
        response.status shouldEqual StatusCodes.Forbidden
        response.asJson shouldEqual jsonContentOf("errors/authorization-failed.json")
      }
    }

    "create a resource" in {
      aclCheck
        .append(AclAddress.Root, Anonymous -> Set(resources.write), caller.subject -> Set(resources.write))
        .accepted

      val endpoints = List(
        ("/v1/resources/myorg/myproject", myId, schemas.resources),
        ("/v1/resources/myorg/myproject/myschema", myId2, schema1.id)
      )
      forAll(endpoints) { case (endpoint, id, schema) =>
        val payload = jsonContentOf("resources/resource.json", "id" -> id)
        Post(endpoint, payload.toEntity) ~> routes ~> check {
          status shouldEqual StatusCodes.Created
          response.asJson shouldEqual resourceMetadata(projectRef, id, schema, (nxv + "Custom").toString)
        }
      }
    }

    "create a resource with an authenticated user and provided id" in {
      val endpoints = List(
        ("/v1/resources/myorg/myproject/_/myid3", myId3, schemas.resources),
        ("/v1/resources/myorg/myproject/myschema/myid4", myId4, schema1.id)
      )
      forAll(endpoints) { case (endpoint, id, schema) =>
        val payload = jsonContentOf("resources/resource.json", "id" -> id)
        Put(endpoint, payload.toEntity) ~> asAlice ~> routes ~> check {
          status shouldEqual StatusCodes.Created
          response.asJson shouldEqual
            resourceMetadata(projectRef, id, schema, (nxv + "Custom").toString, createdBy = alice, updatedBy = alice)
        }

      }
    }

    "reject the creation of a resource which already exists" in {
      Put("/v1/resources/myorg/myproject/_/myid", payload.toEntity) ~> routes ~> check {
        status shouldEqual StatusCodes.Conflict
        response.asJson shouldEqual
          jsonContentOf("/resources/errors/already-exists.json", "id" -> myId, "project" -> "myorg/myproject")
      }
    }

    "fail to create a resource that does not validate against a schema" in {
      Put(
        "/v1/resources/myorg/myproject/nxv:myschema/wrong",
        payload.removeKeys(keywords.id).replaceKeyWithValue("number", "wrong").toEntity
      ) ~> routes ~> check {
        response.status shouldEqual StatusCodes.BadRequest
        response.asJson shouldEqual jsonContentOf("/resources/errors/invalid-resource.json")
      }
    }

    "fail to update a resource without resources/write permission" in {
      aclCheck.subtract(AclAddress.Root, Anonymous -> Set(resources.write)).accepted
      Put("/v1/resources/myorg/myproject/_/myid?rev=1", payload.toEntity) ~> routes ~> check {
        response.status shouldEqual StatusCodes.Forbidden
        response.asJson shouldEqual jsonContentOf("errors/authorization-failed.json")
      }
    }

    "update a resource" in {
      aclCheck.append(AclAddress.Root, Anonymous -> Set(resources.write)).accepted
      val encodedSchema = UrlUtils.encode(schemas.resources.toString)
      val endpoints     = List(
        "/v1/resources/myorg/myproject/_/myid"               -> 1L,
        s"/v1/resources/myorg/myproject/_/$myIdEncoded"      -> 2L,
        "/v1/resources/myorg/myproject/resource/myid"        -> 3L,
        s"/v1/resources/myorg/myproject/$encodedSchema/myid" -> 4L
      )
      forAll(endpoints) { case (endpoint, rev) =>
        Put(s"$endpoint?rev=$rev", payloadUpdated.toEntity(Printer.noSpaces)) ~> routes ~> check {
          status shouldEqual StatusCodes.OK
          response.asJson shouldEqual
            resourceMetadata(projectRef, myId, schemas.resources, (nxv + "Custom").toString, rev = rev + 1)
        }
      }
    }

    "reject the update of a non-existent resource" in {
      val payload = payloadUpdated.removeKeys(keywords.id)
      Put("/v1/resources/myorg/myproject/_/myid10?rev=1", payload.toEntity) ~> routes ~> check {
        status shouldEqual StatusCodes.NotFound
        response.asJson shouldEqual
          jsonContentOf("/resources/errors/not-found.json", "id" -> (nxv + "myid10"), "proj" -> "myorg/myproject")
      }
    }

    "reject the update of a resource at a non-existent revision" in {
      Put("/v1/resources/myorg/myproject/_/myid?rev=10", payloadUpdated.toEntity) ~> routes ~> check {
        status shouldEqual StatusCodes.Conflict
        response.asJson shouldEqual
          jsonContentOf("/resources/errors/incorrect-rev.json", "provided" -> 10L, "expected" -> 5L)
      }
    }

    "fail to deprecate a resource without resources/write permission" in {
      aclCheck.subtract(AclAddress.Root, Anonymous -> Set(resources.write)).accepted
      Delete("/v1/resources/myorg/myproject/_/myid?rev=4") ~> routes ~> check {
        response.status shouldEqual StatusCodes.Forbidden
        response.asJson shouldEqual jsonContentOf("errors/authorization-failed.json")
      }
    }

    "deprecate a resource" in {
      aclCheck.append(AclAddress.Root, Anonymous -> Set(resources.write)).accepted
      Delete("/v1/resources/myorg/myproject/_/myid?rev=5") ~> routes ~> check {
        status shouldEqual StatusCodes.OK
        response.asJson shouldEqual
          resourceMetadata(projectRef, myId, schemas.resources, (nxv + "Custom").toString, deprecated = true, rev = 6L)
      }
    }

    "reject the deprecation of a resource without rev" in {
      Delete("/v1/resources/myorg/myproject/_/myid") ~> routes ~> check {
        status shouldEqual StatusCodes.BadRequest
        response.asJson shouldEqual jsonContentOf("/errors/missing-query-param.json", "field" -> "rev")
      }
    }

    "reject the deprecation of a already deprecated resource" in {
      Delete("/v1/resources/myorg/myproject/_/myid?rev=6") ~> routes ~> check {
        status shouldEqual StatusCodes.BadRequest
        response.asJson shouldEqual jsonContentOf("/resources/errors/resource-deprecated.json", "id" -> myId)
      }
    }

    "tag a resource" in {
      val payload = json"""{"tag": "mytag", "rev": 1}"""
      Post("/v1/resources/myorg/myproject/_/myid2/tags?rev=1", payload.toEntity) ~> routes ~> check {
        status shouldEqual StatusCodes.Created
        response.asJson shouldEqual resourceMetadata(projectRef, myId2, schema1.id, (nxv + "Custom").toString, rev = 2L)
      }
    }

    "fail fetching a resource without resources/read permission" in {
      val endpoints = List(
        "/v1/resources/myorg/myproject/_/myid2",
        s"/v1/resources/myorg/myproject/myschema/$myId2Encoded"
      )
      forAll(endpoints) { endpoint =>
        forAll(List("", "?rev=1", "?tag=mytag")) { suffix =>
          Get(s"$endpoint$suffix") ~> routes ~> check {
            response.status shouldEqual StatusCodes.Forbidden
            response.asJson shouldEqual jsonContentOf("errors/authorization-failed.json")
          }
        }
      }
    }

    val resourceCtx = json"""{"@context": ["${contexts.metadata}", {"@vocab": "${nxv.base}"}]}"""

    "fetch a resource" in {
      aclCheck.append(AclAddress.Root, Anonymous -> Set(resources.read)).accepted
      Get("/v1/resources/myorg/myproject/_/myid") ~> routes ~> check {
        status shouldEqual StatusCodes.OK
        val meta = resourceMetadata(projectRef, myId, schemas.resources, "Custom", deprecated = true, rev = 6L)
        response.asJson shouldEqual payloadUpdated.dropNullValues.deepMerge(meta).deepMerge(resourceCtx)
      }
    }

    "fetch a resource by rev and tag" in {
      val endpoints = List(
        "/v1/resources/myorg/myproject/myschema/myid2?rev=1",
        "/v1/resources/myorg/myproject/_/myid2?rev=1",
        s"/v1/resources/$uuid/$uuid/_/myid2?rev=1",
        "/v1/resources/myorg/myproject/myschema/myid2?tag=mytag",
        s"/v1/resources/$uuid/$uuid/_/myid2?tag=mytag"
      )
      val payload   = jsonContentOf("resources/resource.json", "id" -> myId2)
      val meta      = resourceMetadata(projectRef, myId2, schema1.id, "Custom", rev = 1L)
      forAll(endpoints) { endpoint =>
        Get(endpoint) ~> routes ~> check {
          status shouldEqual StatusCodes.OK
          response.asJson shouldEqual payload.deepMerge(meta).deepMerge(resourceCtx)
        }
      }
    }

    "fetch a resource original payload" in {
      Get("/v1/resources/myorg/myproject/_/myid/source") ~> routes ~> check {
        status shouldEqual StatusCodes.OK
        response.asJson shouldEqual payloadUpdated
      }
    }

    "fetch a resource original payload by rev and tag" in {
      val endpoints = List(
        "/v1/resources/myorg/myproject/myschema/myid2/source?rev=1",
        "/v1/resources/myorg/myproject/_/myid2/source?rev=1",
        s"/v1/resources/$uuid/$uuid/_/myid2/source?rev=1",
        "/v1/resources/myorg/myproject/myschema/myid2/source?tag=mytag",
        s"/v1/resources/$uuid/$uuid/_/myid2/source?tag=mytag"
      )
      val payload   = jsonContentOf("resources/resource.json", "id" -> myId2)
      forAll(endpoints) { endpoint =>
        Get(endpoint) ~> routes ~> check {
          status shouldEqual StatusCodes.OK
          response.asJson shouldEqual payload
        }
      }
    }

    "fetch the resource tags" in {
      Get("/v1/resources/myorg/myproject/_/myid2/tags?rev=1", payload.toEntity) ~> routes ~> check {
        status shouldEqual StatusCodes.OK
        response.asJson shouldEqual json"""{"tags": []}""".addContext(contexts.tags)
      }
      Get("/v1/resources/myorg/myproject/myschema/myid2/tags", payload.toEntity) ~> routes ~> check {
        status shouldEqual StatusCodes.OK
        response.asJson shouldEqual json"""{"tags": [{"rev": 1, "tag": "mytag"}]}""".addContext(contexts.tags)
      }
    }

    "return not found if tag not found" in {
      Get("/v1/resources/myorg/myproject/myschema/myid2?tag=myother") ~> routes ~> check {
        status shouldEqual StatusCodes.NotFound
        response.asJson shouldEqual jsonContentOf("/errors/tag-not-found.json", "tag" -> "myother")
      }
    }

    "reject if provided rev and tag simultaneously" in {
      Get("/v1/resources/myorg/myproject/myschema/myid2?tag=mytag&rev=1") ~> routes ~> check {
        status shouldEqual StatusCodes.BadRequest
        response.asJson shouldEqual jsonContentOf("/errors/tag-and-rev-error.json")
      }
    }

    "tag a deprecated resource" in {
      val payload = json"""{"tag": "mytag", "rev": 6}"""
      Post("/v1/resources/myorg/myproject/_/myid/tags?rev=6", payload.toEntity) ~> routes ~> check {
        status shouldEqual StatusCodes.Created
        response.asJson shouldEqual resourceMetadata(
          projectRef,
          myId,
          schemas.resources,
          (nxv + "Custom").toString,
          rev = 7L,
          deprecated = true
        )
      }
    }

    "delete a tag on resource" in {
      Delete("/v1/resources/myorg/myproject/_/myid2/tags/mytag?rev=2") ~> routes ~> check {
        status shouldEqual StatusCodes.OK
        response.asJson shouldEqual resourceMetadata(projectRef, myId2, schema1.id, (nxv + "Custom").toString, rev = 3L)
      }
    }

    "not return the deleted tag" in {
      Get("/v1/resources/myorg/myproject/_/myid2/tags") ~> routes ~> check {
        status shouldEqual StatusCodes.OK
        response.asJson shouldEqual json"""{"tags": []}""".addContext(contexts.tags)
      }
    }

    "fail to fetch resource by the deleted tag" in {
      Get("/v1/resources/myorg/myproject/_/myid2?tag=mytag") ~> routes ~> check {
        status shouldEqual StatusCodes.NotFound
        response.asJson shouldEqual jsonContentOf("/errors/tag-not-found.json", "tag" -> "mytag")
      }
    }

    "fail to get the events stream without events/read permission" in {
      aclCheck.subtract(AclAddress.Root, Anonymous -> Set(events.read)).accepted

      Head("/v1/resources/myorg/myproject/events") ~> routes ~> check {
        response.status shouldEqual StatusCodes.Forbidden
      }

      forAll(List("/v1/resources/events", "/v1/resources/myorg/events", "/v1/resources/myorg/myproject/events")) {
        endpoint =>
          Get(endpoint) ~> `Last-Event-ID`("2") ~> routes ~> check {
            response.status shouldEqual StatusCodes.Forbidden
            response.asJson shouldEqual jsonContentOf("errors/authorization-failed.json")
          }
      }
    }

    "get the events stream" in {
      aclCheck.append(AclAddress.Root, Anonymous -> Set(events.read)).accepted
      forAll(
        List(
          "/v1/resources/events",
          "/v1/resources/myorg/events",
          s"/v1/resources/$uuid/events",
          "/v1/resources/myorg/myproject/events",
          s"/v1/resources/$uuid/$uuid/events"
        )
      ) { endpoint =>
        Get(endpoint) ~> `Last-Event-ID`("0") ~> routes ~> check {
          mediaType shouldBe `text/event-stream`
          chunksStream.asString(2).strip shouldEqual contentOf("/resources/eventstream-0-2.txt", "uuid" -> uuid).strip
        }
      }
    }

    "check access to SSEs" in {
      Head("/v1/resources/myorg/myproject/events") ~> routes ~> check {
        response.status shouldEqual StatusCodes.OK
      }
    }

    "redirect to fusion with a given tag if the Accept header is set to text/html" in {
      Get("/v1/resources/myorg/myproject/_/myid2?tag=mytag") ~> Accept(`text/html`) ~> routes ~> check {
        response.status shouldEqual StatusCodes.SeeOther
        response.header[Location].value.uri shouldEqual Uri(
          "https://bbp.epfl.ch/nexus/web/myorg/myproject/resources/myid2"
        ).withQuery(Uri.Query("tag" -> "mytag"))
      }
    }
  }
}
