package ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.routes

import akka.actor.ActorSystem
import akka.http.scaladsl.model.MediaTypes.`text/event-stream`
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.model.headers.{`Last-Event-ID`, OAuth2BearerToken}
import akka.http.scaladsl.server.{ExceptionHandler, RejectionHandler, Route}
import akka.persistence.query.Sequence
import ch.epfl.bluebrain.nexus.delta.kernel.utils.{UUIDF, UrlUtils}
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.config.ElasticSearchViewsConfig
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.model.ElasticSearchViewEvent.{ElasticSearchViewDeprecated, ElasticSearchViewTagAdded}
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.model.{ElasticSearchViewEvent, permissions => esPermissions, schema => elasticSearchSchema}
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.{ElasticSearchViews, RemoteContextResolutionFixture}
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.{contexts, nxv, schemas}
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.JsonLdContext.keywords
import ch.epfl.bluebrain.nexus.delta.rdf.utils.JsonKeyOrdering
import ch.epfl.bluebrain.nexus.delta.sdk.Permissions.events
import ch.epfl.bluebrain.nexus.delta.sdk.cache.KeyValueStore
import ch.epfl.bluebrain.nexus.delta.sdk.circe.CirceMarshalling
import ch.epfl.bluebrain.nexus.delta.sdk.eventlog.EventLogUtils
import ch.epfl.bluebrain.nexus.delta.sdk.generators.ProjectGen
import ch.epfl.bluebrain.nexus.delta.sdk.marshalling.{RdfExceptionHandler, RdfRejectionHandler}
import ch.epfl.bluebrain.nexus.delta.sdk.model.acls.{Acl, AclAddress}
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Identity.{Anonymous, Authenticated, Group, Subject, User}
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.{AuthToken, Caller, Identity}
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.ProjectCountsCollection.ProjectCount
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.{ApiMappings, ProjectCountsCollection, ProjectRef}
import ch.epfl.bluebrain.nexus.delta.sdk.model.resolvers.{ResolverContextResolution, ResourceResolutionReport}
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.PaginationConfig
import ch.epfl.bluebrain.nexus.delta.sdk.model._
import ch.epfl.bluebrain.nexus.delta.sdk.syntax._
import ch.epfl.bluebrain.nexus.delta.sdk.testkit._
import ch.epfl.bluebrain.nexus.delta.sdk.utils.RouteHelpers
import ch.epfl.bluebrain.nexus.delta.sdk.{JsonLdValue, ProgressesStatistics, ProjectsCounts, SseEventLog}
import ch.epfl.bluebrain.nexus.delta.sourcing.EventLog
import ch.epfl.bluebrain.nexus.delta.sourcing.projections.ProjectionId.ViewProjectionId
import ch.epfl.bluebrain.nexus.delta.sourcing.projections.{ProjectionId, ProjectionProgress}
import ch.epfl.bluebrain.nexus.testkit._
import io.circe.Json
import monix.bio.{IO, UIO}
import monix.execution.Scheduler
import org.scalatest.matchers.should.Matchers
import org.scalatest.{CancelAfterFailure, Inspectors, OptionValues}
import slick.jdbc.JdbcBackend

import java.time.Instant
import java.util.UUID

class ElasticSearchViewsRoutesSpec
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
    with ConfigFixtures
    with TestHelpers
    with CirceMarshalling
    with RemoteContextResolutionFixture {

  import akka.actor.typed.scaladsl.adapter._
  implicit val typedSystem = system.toTyped

  override protected def createActorSystem(): ActorSystem =
    ActorSystem("StoragesRoutersSpec", AbstractDBSpec.config)

  private val uuid                  = UUID.randomUUID()
  implicit private val uuidF: UUIDF = UUIDF.fixed(uuid)

  implicit private val ordering: JsonKeyOrdering =
    JsonKeyOrdering.default(topKeys = List("@context", "@id", "@type", "reason", "details", "_total", "_results"))

  implicit private val baseUri: BaseUri                   = BaseUri("http://localhost", Label.unsafe("v1"))
  implicit private val paginationConfig: PaginationConfig = PaginationConfig(5, 10, 5)
  implicit private val s: Scheduler                       = Scheduler.global
  implicit private val rejectionHandler: RejectionHandler = RdfRejectionHandler.apply
  implicit private val exceptionHandler: ExceptionHandler = RdfExceptionHandler.apply

  private val realm: Label = Label.unsafe("wonderland")
  private val alice: User  = User("alice", realm)

  implicit private val subject: Subject = Identity.Anonymous

  private val caller = Caller(alice, Set(alice, Anonymous, Authenticated(realm), Group("group", realm)))

  private val identities = IdentitiesDummy(Map(AuthToken("alice") -> caller))

  private val asAlice = addCredentials(OAuth2BearerToken("alice"))

  private val org                = Label.unsafe("myorg")
  private val project            = ProjectGen.resourceFor(ProjectGen.project("myorg", "myproject", uuid = uuid, orgUuid = uuid))
  private val defaultApiMappings = ApiMappings("_" -> schemas.resources, "resource" -> schemas.resources)
  private val projectRef         = project.value.ref

  private val myId           = nxv + "myid"
  private val myIdEncoded    = UrlUtils.encode(myId.toString)
  private val myId2          = nxv + "myid2"
  private val myId2Encoded   = UrlUtils.encode(myId2.toString)
  private val mapping        = jsonContentOf("mapping.json")
  private val payload        = json"""{"@id": "$myId", "@type": "ElasticSearchView", "mapping": $mapping}"""
  private val payloadNoId    = payload.removeKeys(keywords.id)
  private val payloadUpdated = payloadNoId deepMerge json"""{"includeDeprecated": false}"""

  private val (orgs, projs) =
    ProjectSetup
      .init(orgsToCreate = List(org), projectsToCreate = List(project.value), defaultApiMappings = defaultApiMappings)
      .accepted

  private val allowedPerms = Set(esPermissions.write, esPermissions.read, esPermissions.query, events.read)

  private val (acls, permissions) = AclSetup.initWithPerms(allowedPerms, Set(realm)).accepted

  private val eventLog: EventLog[Envelope[ElasticSearchViewEvent]] =
    EventLog.postgresEventLog[Envelope[ElasticSearchViewEvent]](EventLogUtils.toEnvelope).hideErrors.accepted

  private val config =
    ElasticSearchViewsConfig(
      "http://localhost",
      httpClientConfig,
      aggregate,
      keyValueStore,
      pagination,
      cacheIndexing,
      externalIndexing
    )

  private val resolverContext: ResolverContextResolution =
    new ResolverContextResolution(rcr, (_, _, _) => IO.raiseError(ResourceResolutionReport()))
  private val views                                      =
    ElasticSearchViews(
      config,
      eventLog,
      resolverContext,
      orgs,
      projs,
      permissions,
      (_, _) => UIO.unit
    ).accepted

  private val now          = Instant.now()
  private val nowMinus5    = now.minusSeconds(5)
  private val projectStats = ProjectCount(10, now)

  private val projectsCounts = new ProjectsCounts {
    override def get(): UIO[ProjectCountsCollection]                 =
      UIO(ProjectCountsCollection(Map(projectRef -> projectStats)))
    override def get(project: ProjectRef): UIO[Option[ProjectCount]] = get().map(_.get(project))
  }

  private val viewsQuery = DummyElasticSearchViewsQuery

  var restartedView: Option[(ProjectRef, Iri)] = None

  private def restart(id: Iri, projectRef: ProjectRef) = UIO { restartedView = Some(projectRef -> id) }.void

  private val resourceToSchemaMapping = ResourceToSchemaMappings(Label.unsafe("views") -> elasticSearchSchema.iri)
  private val viewsProgressesCache    =
    KeyValueStore.localLRU[ProjectionId, ProjectionProgress[Unit]](10L).accepted

  private val statisticsProgress = new ProgressesStatistics(viewsProgressesCache, projectsCounts)

  private val sseEventLog: SseEventLog = new SseEventLogDummy(
    List(
      Envelope(
        ElasticSearchViewTagAdded(myId, projectRef, uuid, 1, TagLabel.unsafe("mytag"), 1, Instant.EPOCH, subject),
        Sequence(1),
        "p1",
        1
      ),
      Envelope(ElasticSearchViewDeprecated(myId, projectRef, uuid, 1, Instant.EPOCH, subject), Sequence(2), "p1", 2)
    ),
    { case ev: ElasticSearchViewEvent => JsonLdValue(ev) }
  )

  private val routes =
    Route.seal(
      ElasticSearchViewsRoutes(
        identities,
        acls,
        orgs,
        projs,
        views,
        viewsQuery,
        statisticsProgress,
        restart,
        resourceToSchemaMapping,
        sseEventLog
      )
    )

  "Elasticsearch views routes" should {

    "fail to create a view without views/write permission" in {
      acls.append(Acl(AclAddress.Root, Anonymous -> Set(events.read)), 0L).accepted
      Post("/v1/views/myorg/myproject", payload.toEntity) ~> routes ~> check {
        response.status shouldEqual StatusCodes.Forbidden
        response.asJson shouldEqual jsonContentOf("/routes/errors/authorization-failed.json")
      }
    }

    "create a view" in {
      acls
        .append(
          Acl(AclAddress.Root, Anonymous -> Set(esPermissions.write), caller.subject -> Set(esPermissions.write)),
          1L
        )
        .accepted
      Post("/v1/views/myorg/myproject", payload.toEntity) ~> routes ~> check {
        status shouldEqual StatusCodes.Created
        response.asJson shouldEqual elasticSearchViewMetadata(myId)
      }
    }

    "create a view with an authenticated user and provided id" in {
      Put("/v1/views/myorg/myproject/myid2", payloadNoId.toEntity) ~> asAlice ~> routes ~> check {
        status shouldEqual StatusCodes.Created
        response.asJson shouldEqual elasticSearchViewMetadata(myId2, createdBy = alice, updatedBy = alice)
      }
    }

    "reject the creation of a view which already exists" in {
      Put("/v1/views/myorg/myproject/myid", payload.toEntity) ~> routes ~> check {
        status shouldEqual StatusCodes.Conflict
        response.asJson shouldEqual
          jsonContentOf("/routes/errors/already-exists.json", "id" -> myId, "project" -> "myorg/myproject")
      }
    }

    "fail to update a view without views/write permission" in {
      acls.subtract(Acl(AclAddress.Root, Anonymous -> Set(esPermissions.write)), 2L).accepted
      Put(s"/v1/views/myorg/myproject/myid?rev=1", payload.toEntity) ~> routes ~> check {
        response.status shouldEqual StatusCodes.Forbidden
        response.asJson shouldEqual jsonContentOf("/routes/errors/authorization-failed.json")
      }
    }

    "update a view" in {
      acls.append(Acl(AclAddress.Root, Anonymous -> Set(esPermissions.write)), 3L).accepted
      val endpoints = List(
        "/v1/views/myorg/myproject/myid",
        s"/v1/views/myorg/myproject/$myIdEncoded"
      )
      forAll(endpoints.zipWithIndex) { case (endpoint, idx) =>
        Put(s"$endpoint?rev=${idx + 1}", payloadUpdated.toEntity) ~> routes ~> check {
          status shouldEqual StatusCodes.OK
          response.asJson shouldEqual elasticSearchViewMetadata(myId, rev = idx + 2L)
        }
      }
    }

    "reject the update of a non-existent view" in {
      val payload = payloadUpdated.removeKeys(keywords.id)
      Put("/v1/views/myorg/myproject/myid10?rev=1", payload.toEntity) ~> routes ~> check {
        status shouldEqual StatusCodes.NotFound
        response.asJson shouldEqual
          jsonContentOf("/routes/errors/not-found.json", "id" -> (nxv + "myid10"), "proj" -> "myorg/myproject")
      }
    }

    "reject the update of a view at a non-existent revision" in {
      Put("/v1/views/myorg/myproject/myid?rev=10", payloadUpdated.toEntity) ~> routes ~> check {
        status shouldEqual StatusCodes.Conflict
        response.asJson shouldEqual
          jsonContentOf("/routes/errors/incorrect-rev.json", "provided" -> 10L, "expected" -> 3L)
      }
    }

    "fail to deprecate a view without views/write permission" in {
      acls.subtract(Acl(AclAddress.Root, Anonymous -> Set(esPermissions.write)), 4L).accepted
      Delete("/v1/views/myorg/myproject/myid?rev=3") ~> routes ~> check {
        response.status shouldEqual StatusCodes.Forbidden
        response.asJson shouldEqual jsonContentOf("/routes/errors/authorization-failed.json")
      }
    }

    "deprecate a schema" in {
      acls.append(Acl(AclAddress.Root, Anonymous -> Set(esPermissions.write)), 5L).accepted
      Delete("/v1/views/myorg/myproject/myid?rev=3") ~> routes ~> check {
        status shouldEqual StatusCodes.OK
        response.asJson shouldEqual elasticSearchViewMetadata(myId, rev = 4L, deprecated = true)
      }
    }

    "reject the deprecation of a view without rev" in {
      Delete("/v1/views/myorg/myproject/myid") ~> routes ~> check {
        status shouldEqual StatusCodes.BadRequest
        response.asJson shouldEqual jsonContentOf("/routes/errors/missing-query-param.json", "field" -> "rev")
      }
    }

    "reject the deprecation of a already deprecated view" in {
      Delete(s"/v1/views/myorg/myproject/myid?rev=4") ~> routes ~> check {
        status shouldEqual StatusCodes.BadRequest
        response.asJson shouldEqual jsonContentOf("/routes/errors/view-deprecated.json", "id" -> myId)
      }
    }

    "tag a view" in {
      val payload = json"""{"tag": "mytag", "rev": 1}"""
      Post("/v1/views/myorg/myproject/myid2/tags?rev=1", payload.toEntity) ~> routes ~> check {
        status shouldEqual StatusCodes.Created
        response.asJson shouldEqual elasticSearchViewMetadata(myId2, rev = 2, createdBy = alice)
      }
    }

    "fail to fetch a view without resources/read permission" in {
      val endpoints = List(
        "/v1/views/myorg/myproject/myid2",
        "/v1/views/myorg/myproject/myid2/tags"
      )
      forAll(endpoints) { endpoint =>
        forAll(List("", "?rev=1", "?tags=mytag")) { suffix =>
          Get(s"$endpoint$suffix") ~> routes ~> check {
            response.status shouldEqual StatusCodes.Forbidden
            response.asJson shouldEqual jsonContentOf("/routes/errors/authorization-failed.json")
          }
        }
      }
    }

    "fetch a view" in {
      acls.append(Acl(AclAddress.Root, Anonymous -> Set(esPermissions.read)), 6L).accepted
      Get("/v1/views/myorg/myproject/myid") ~> routes ~> check {
        status shouldEqual StatusCodes.OK
        response.asJson shouldEqual elasticSearchView(myId, includeDeprecated = false, rev = 4, deprecated = true)
      }
    }

    "fetch a view by rev and tag" in {
      val endpoints = List(
        s"/v1/views/$uuid/$uuid/myid2",
        s"/v1/resources/$uuid/$uuid/_/myid2",
        "/v1/views/myorg/myproject/myid2",
        "/v1/resources/myorg/myproject/_/myid2",
        s"/v1/views/myorg/myproject/$myId2Encoded",
        s"/v1/resources/myorg/myproject/_/$myId2Encoded"
      )
      forAll(endpoints) { endpoint =>
        forAll(List("rev=1", "tag=mytag")) { param =>
          Get(s"$endpoint?$param") ~> routes ~> check {
            status shouldEqual StatusCodes.OK
            response.asJson shouldEqual elasticSearchView(myId2, createdBy = alice, updatedBy = alice)
          }
        }
      }
    }

    "fetch a view original payload" in {
      val endpoints = List(
        s"/v1/views/$uuid/$uuid/myid2/source",
        s"/v1/resources/$uuid/$uuid/_/myid2/source",
        "/v1/views/myorg/myproject/myid2/source",
        "/v1/resources/myorg/myproject/_/myid2/source",
        s"/v1/views/myorg/myproject/$myId2Encoded/source",
        s"/v1/resources/myorg/myproject/_/$myId2Encoded/source"
      )
      forAll(endpoints) { endpoint =>
        Get(endpoint) ~> routes ~> check {
          status shouldEqual StatusCodes.OK
          response.asJson shouldEqual payloadNoId
        }
      }
    }
    "fetch a view original payload by rev or tag" in {
      val endpoints = List(
        s"/v1/views/$uuid/$uuid/myid2/source",
        "/v1/views/myorg/myproject/myid2/source",
        s"/v1/views/myorg/myproject/$myId2Encoded/source"
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

    "fetch the view tags" in {
      Get("/v1/resources/myorg/myproject/_/myid2/tags?rev=1") ~> routes ~> check {
        status shouldEqual StatusCodes.OK
        response.asJson shouldEqual json"""{"tags": []}""".addContext(contexts.tags)
      }
      Get("/v1/views/myorg/myproject/myid2/tags") ~> routes ~> check {
        status shouldEqual StatusCodes.OK
        response.asJson shouldEqual json"""{"tags": [{"rev": 1, "tag": "mytag"}]}""".addContext(contexts.tags)
      }
    }

    "return not found if tag not found" in {
      Get("/v1/views/myorg/myproject/myid2?tag=myother") ~> routes ~> check {
        status shouldEqual StatusCodes.NotFound
        response.asJson shouldEqual jsonContentOf("/routes/errors/tag-not-found.json", "tag" -> "myother")
      }
    }

    "reject if provided rev and tag simultaneously" in {
      Get("/v1/views/myorg/myproject/myid2?tag=mytag&rev=1") ~> routes ~> check {
        status shouldEqual StatusCodes.BadRequest
        response.asJson shouldEqual jsonContentOf("/routes/errors/tag-and-rev-error.json")
      }
    }

    "fail to fetch statistics and offset from view without resources/read permission" in {
      acls.subtract(Acl(AclAddress.Root, Anonymous -> Set(esPermissions.read)), 7L).accepted

      val endpoints = List(
        "/v1/views/myorg/myproject/myid2/statistics",
        "/v1/views/myorg/myproject/myid2/offset"
      )
      forAll(endpoints) { endpoint =>
        Get(endpoint) ~> routes ~> check {
          response.status shouldEqual StatusCodes.Forbidden
          response.asJson shouldEqual jsonContentOf("/routes/errors/authorization-failed.json")
        }
      }
    }

    "fetch statistics from view" in {
      acls.append(Acl(AclAddress.Root, Anonymous -> Set(esPermissions.read)), 8L).accepted
      val projectionId = ViewProjectionId(s"elasticsearch-${uuid}_2")
      viewsProgressesCache.put(projectionId, ProjectionProgress(Sequence(2), nowMinus5, 2, 0, 0, 0)).accepted
      Get("/v1/views/myorg/myproject/myid2/statistics") ~> routes ~> check {
        response.status shouldEqual StatusCodes.OK
        response.asJson shouldEqual jsonContentOf(
          "/routes/statistics.json",
          "projectLatestInstant" -> now,
          "viewLatestInstant"    -> nowMinus5
        )
      }
    }

    "fetch offset from view" in {
      Get("/v1/views/myorg/myproject/myid2/offset") ~> routes ~> check {
        response.status shouldEqual StatusCodes.OK
        response.asJson shouldEqual jsonContentOf("/routes/offset.json")
      }
    }

    "fail to restart offset from view without resources/write permission" in {
      acls.subtract(Acl(AclAddress.Root, Anonymous -> Set(esPermissions.write)), 9L).accepted

      Delete("/v1/views/myorg/myproject/myid2/offset") ~> routes ~> check {
        response.status shouldEqual StatusCodes.Forbidden
        response.asJson shouldEqual jsonContentOf("/routes/errors/authorization-failed.json")
      }
    }

    "restart offset from view" in {
      acls.append(Acl(AclAddress.Root, Anonymous -> Set(esPermissions.write)), 10L).accepted
      restartedView shouldEqual None
      Delete("/v1/views/myorg/myproject/myid2/offset") ~> routes ~> check {
        response.status shouldEqual StatusCodes.OK
        response.asJson shouldEqual json"""{"@context": "${Vocabulary.contexts.offset}", "@type": "NoOffset"}"""
        restartedView shouldEqual Some(projectRef -> myId2)
      }
    }

    "run query" in {
      val query = json"""{"query": { "match_all": {} } }"""
      Post("/v1/views/myorg/myproject/myid2/_search?from=0&size=5&q1=v1&q=something", query) ~> routes ~> check {
        response.status shouldEqual StatusCodes.OK
        response.asJson shouldEqual
          json"""{"id" : "myid2", "project" : "myorg/myproject", "q1" : "v1"}""".deepMerge(query)
      }
    }

    "fail to do listings from view without resources/read permission" in {
      acls.subtract(Acl(AclAddress.Root, Anonymous -> Set(esPermissions.read)), 11L).accepted

      val endpoints = List(
        "/v1/views/myorg/myproject",
        "/v1/resources/myorg/myproject/view"
      )
      forAll(endpoints) { endpoint =>
        Get(endpoint) ~> routes ~> check {
          response.status shouldEqual StatusCodes.Forbidden
          response.asJson shouldEqual jsonContentOf("/routes/errors/authorization-failed.json")
        }
      }
    }

    "list" in {
      acls.append(Acl(AclAddress.Root, Anonymous -> Set(esPermissions.read)), 12L).accepted

      val endpoints = List(
        "/v1/views/myorg/myproject"                    -> elasticSearchSchema,
        "/v1/resources/myorg/myproject/schema"         -> "schema",
        s"/v1/resources/myorg/myproject/$myId2Encoded" -> myId2
      )
      forAll(endpoints) { case (endpoint, schema) =>
        Get(s"$endpoint?from=0&size=5&q1=v1&q=something") ~> routes ~> check {
          response.status shouldEqual StatusCodes.OK
          response.asJson shouldEqual jsonContentOf("/routes/elasticsearch-view-list-response.json", "schema" -> schema)
        }
      }
    }

    "fail to get the events stream without events/read permission" in {
      acls.subtract(Acl(AclAddress.Root, Anonymous -> Set(events.read)), 13L).accepted

      Get("/v1/views/myorg/myproject/events") ~> routes ~> check {
        response.status shouldEqual StatusCodes.Forbidden
        response.asJson shouldEqual jsonContentOf("/routes/errors/authorization-failed.json")
      }
    }

    "get the events stream" in {
      acls.append(Acl(AclAddress.Root, Anonymous -> Set(events.read)), 14L).accepted
      val endpoints = List("/v1/views/events", "/v1/views/myorg/events", "/v1/views/myorg/myproject/events")
      forAll(endpoints) { endpoint =>
        Get(endpoint) ~> `Last-Event-ID`("0") ~> routes ~> check {
          mediaType shouldBe `text/event-stream`
          chunksStream.asString(2).strip shouldEqual contentOf("/routes/eventstream-0-2.txt", "uuid" -> uuid).strip
        }
      }
    }
  }

  private def elasticSearchViewMetadata(
      id: Iri,
      rev: Long = 1L,
      deprecated: Boolean = false,
      createdBy: Subject = Anonymous,
      updatedBy: Subject = Anonymous
  ): Json =
    jsonContentOf(
      "/routes/elasticsearch-view-write-response.json",
      "project"    -> projectRef,
      "id"         -> id,
      "rev"        -> rev,
      "uuid"       -> uuid,
      "deprecated" -> deprecated,
      "createdBy"  -> createdBy.id,
      "updatedBy"  -> updatedBy.id,
      "label"      -> lastSegment(id)
    )

  private def elasticSearchView(
      id: Iri,
      includeDeprecated: Boolean = false,
      rev: Long = 1L,
      deprecated: Boolean = false,
      createdBy: Subject = Anonymous,
      updatedBy: Subject = Anonymous
  ): Json =
    jsonContentOf(
      "/routes/elasticsearch-view-read-response.json",
      "project"           -> projectRef,
      "id"                -> id,
      "rev"               -> rev,
      "uuid"              -> uuid,
      "deprecated"        -> deprecated,
      "createdBy"         -> createdBy.id,
      "updatedBy"         -> updatedBy.id,
      "includeDeprecated" -> includeDeprecated,
      "label"             -> lastSegment(id)
    )

  private def lastSegment(iri: Iri) =
    iri.toString.substring(iri.toString.lastIndexOf("/") + 1)

  private var db: JdbcBackend.Database = null

  override protected def beforeAll(): Unit = {
    super.beforeAll()
    db = AbstractDBSpec.beforeAll
    ()
  }

  override protected def afterAll(): Unit = {
    AbstractDBSpec.afterAll(db)
    super.afterAll()
  }
}
