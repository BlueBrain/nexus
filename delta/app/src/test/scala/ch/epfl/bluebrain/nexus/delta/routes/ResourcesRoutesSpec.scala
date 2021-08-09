package ch.epfl.bluebrain.nexus.delta.routes

import akka.http.scaladsl.model.MediaTypes.`text/event-stream`
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.model.headers.{`Last-Event-ID`, OAuth2BearerToken}
import akka.http.scaladsl.server.Route
import akka.persistence.query.Sequence
import ch.epfl.bluebrain.nexus.delta.kernel.utils.{UUIDF, UrlUtils}
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.{contexts, nxv, schema, schemas}
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.JsonLdContext.keywords
import ch.epfl.bluebrain.nexus.delta.sdk.JsonValue
import ch.epfl.bluebrain.nexus.delta.sdk.Permissions.{events, resources}
import ch.epfl.bluebrain.nexus.delta.sdk.ResolverResolution.{FetchResource, ResourceResolution}
import ch.epfl.bluebrain.nexus.delta.sdk.generators.{ProjectGen, ResourceResolutionGen, SchemaGen}
import ch.epfl.bluebrain.nexus.delta.sdk.model._
import ch.epfl.bluebrain.nexus.delta.sdk.model.acls.{Acl, AclAddress}
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Identity.{Anonymous, Authenticated, Group, Subject}
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.{AuthToken, Caller, Identity}
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.{ApiMappings, ProjectRef}
import ch.epfl.bluebrain.nexus.delta.sdk.model.resolvers.{ResolverContextResolution, ResourceResolutionReport}
import ch.epfl.bluebrain.nexus.delta.sdk.model.resources.ResourceEvent
import ch.epfl.bluebrain.nexus.delta.sdk.model.resources.ResourceEvent.{ResourceDeprecated, ResourceTagAdded}
import ch.epfl.bluebrain.nexus.delta.sdk.model.schemas.Schema
import ch.epfl.bluebrain.nexus.delta.sdk.syntax._
import ch.epfl.bluebrain.nexus.delta.sdk.testkit._
import ch.epfl.bluebrain.nexus.delta.sdk.utils.RouteHelpers
import ch.epfl.bluebrain.nexus.delta.utils.RouteFixtures
import ch.epfl.bluebrain.nexus.testkit._
import monix.bio.{IO, UIO}
import org.scalatest.matchers.should.Matchers
import org.scalatest.{CancelAfterFailure, Inspectors, OptionValues}

import java.time.Instant
import java.util.UUID

class ResourcesRoutesSpec
    extends RouteHelpers
    with Matchers
    with CancelAfterFailure
    with CirceLiteral
    with CirceEq
    with IOFixedClock
    with IOValues
    with OptionValues
    with TestMatchers
    with Inspectors
    with RouteFixtures {

  private val uuid                  = UUID.randomUUID()
  implicit private val uuidF: UUIDF = UUIDF.fixed(uuid)

  implicit private val subject: Subject = Identity.Anonymous

  implicit private val caller: Caller =
    Caller(alice, Set(alice, Anonymous, Authenticated(realm), Group("group", realm)))

  private val identities = IdentitiesDummy(Map(AuthToken("alice") -> caller))

  private val asAlice = addCredentials(OAuth2BearerToken("alice"))

  private val org          = Label.unsafe("myorg")
  private val am           = ApiMappings("nxv" -> nxv.base, "Person" -> schema.Person)
  private val projBase     = nxv.base
  private val project      = ProjectGen.resourceFor(
    ProjectGen.project("myorg", "myproject", uuid = uuid, orgUuid = uuid, base = projBase, mappings = am)
  )
  private val projectRef   = project.value.ref
  private val schemaSource = jsonContentOf("resources/schema.json").addContext(contexts.shacl, contexts.schemasMetadata)
  private val schema1      = SchemaGen.schema(nxv + "myschema", project.value.ref, schemaSource.removeKeys(keywords.id))
  private val schema2      = SchemaGen.schema(schema.Person, project.value.ref, schemaSource.removeKeys(keywords.id))

  private val (orgs, projs) =
    ProjectSetup
      .init(orgsToCreate = List(org), projectsToCreate = List(project.value))
      .accepted

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

  private val resourceResolution: ResourceResolution[Schema] =
    ResourceResolutionGen.singleInProject(projectRef, fetchSchema)

  private val acls = AclSetup.init(Set(resources.write, resources.read, events.read), Set(realm)).accepted

  private val resourcesDummy =
    ResourcesDummy(
      orgs,
      projs,
      resourceResolution,
      (_, _) => IO.unit,
      resolverContextResolution
    ).accepted
  private val sseEventLog    = new SseEventLogDummy(
    List(
      Envelope(
        ResourceTagAdded(myId, projectRef, Set.empty, 1, TagLabel.unsafe("mytag"), 1, Instant.EPOCH, subject),
        Sequence(1),
        "p1",
        1
      ),
      Envelope(ResourceDeprecated(myId, projectRef, Set.empty, 1, Instant.EPOCH, subject), Sequence(2), "p1", 2)
    ),
    { case ev: ResourceEvent => JsonValue(ev).asInstanceOf[JsonValue.Aux[Event]] }
  )

  private val routes =
    Route.seal(ResourcesRoutes(identities, acls, orgs, projs, resourcesDummy, sseEventLog, IndexingActionDummy()))

  val payloadUpdated = payload deepMerge json"""{"name": "Alice"}"""

  "A resource route" should {

    "fail to create a resource without resources/write permission" in {
      acls.append(Acl(AclAddress.Root, Anonymous -> Set(events.read)), 0L).accepted
      Post("/v1/resources/myorg/myproject", payload.toEntity) ~> routes ~> check {
        response.status shouldEqual StatusCodes.Forbidden
        response.asJson shouldEqual jsonContentOf("errors/authorization-failed.json")
      }
    }

    "create a resource" in {
      acls
        .append(Acl(AclAddress.Root, Anonymous -> Set(resources.write), caller.subject -> Set(resources.write)), 1L)
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
      acls.subtract(Acl(AclAddress.Root, Anonymous -> Set(resources.write)), 2L).accepted
      Put("/v1/resources/myorg/myproject/_/myid?rev=1", payload.toEntity) ~> routes ~> check {
        response.status shouldEqual StatusCodes.Forbidden
        response.asJson shouldEqual jsonContentOf("errors/authorization-failed.json")
      }
    }

    "update a resource" in {
      acls.append(Acl(AclAddress.Root, Anonymous -> Set(resources.write)), 3L).accepted
      val encodedSchema = UrlUtils.encode(schemas.resources.toString)
      val endpoints     = List(
        "/v1/resources/myorg/myproject/_/myid"               -> 1L,
        s"/v1/resources/myorg/myproject/_/$myIdEncoded"      -> 2L,
        "/v1/resources/myorg/myproject/resource/myid"        -> 3L,
        s"/v1/resources/myorg/myproject/$encodedSchema/myid" -> 4L
      )
      forAll(endpoints) { case (endpoint, rev) =>
        Put(s"$endpoint?rev=$rev", payloadUpdated.toEntity) ~> routes ~> check {
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
      acls.subtract(Acl(AclAddress.Root, Anonymous -> Set(resources.write)), 4L).accepted
      Delete("/v1/resources/myorg/myproject/_/myid?rev=4") ~> routes ~> check {
        response.status shouldEqual StatusCodes.Forbidden
        response.asJson shouldEqual jsonContentOf("errors/authorization-failed.json")
      }
    }

    "deprecate a resource" in {
      acls.append(Acl(AclAddress.Root, Anonymous -> Set(resources.write)), 5L).accepted
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
      acls.append(Acl(AclAddress.Root, Anonymous -> Set(resources.read)), 6L).accepted
      Get("/v1/resources/myorg/myproject/_/myid") ~> routes ~> check {
        status shouldEqual StatusCodes.OK
        val meta = resourceMetadata(projectRef, myId, schemas.resources, "Custom", deprecated = true, rev = 6L)
        response.asJson shouldEqual payloadUpdated.deepMerge(meta).deepMerge(resourceCtx)
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

    "fail to get the events stream without events/read permission" in {
      acls.subtract(Acl(AclAddress.Root, Anonymous -> Set(events.read)), 7L).accepted

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
      acls.append(Acl(AclAddress.Root, Anonymous -> Set(events.read)), 8L).accepted
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
  }
}
