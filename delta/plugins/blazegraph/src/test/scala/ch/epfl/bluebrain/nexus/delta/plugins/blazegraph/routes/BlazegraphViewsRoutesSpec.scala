package ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.routes

import akka.actor.ActorSystem
import akka.http.scaladsl.model.headers.{Accept, OAuth2BearerToken, `Content-Type`}
import akka.http.scaladsl.model.{HttpEntity, StatusCodes}
import akka.http.scaladsl.server.{ExceptionHandler, RejectionHandler, Route}
import akka.persistence.query.Sequence
import akka.util.ByteString
import cats.effect.concurrent.Ref
import ch.epfl.bluebrain.nexus.delta.kernel.utils.UUIDF
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.client.SparqlResults.{Binding, Bindings}
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.client.{SparqlQuery, SparqlResults}
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.model.BlazegraphViewRejection.ViewNotFound
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.model.SparqlLink.{SparqlExternalLink, SparqlResourceLink}
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.model._
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.{BlazegraphViews, BlazegraphViewsQuery}
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.nxv
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.RemoteContextResolution
import ch.epfl.bluebrain.nexus.delta.rdf.utils.JsonKeyOrdering
import ch.epfl.bluebrain.nexus.delta.rdf.{RdfMediaTypes, Vocabulary}
import ch.epfl.bluebrain.nexus.delta.sdk.Permissions.events
import ch.epfl.bluebrain.nexus.delta.sdk.cache.KeyValueStore
import ch.epfl.bluebrain.nexus.delta.sdk.eventlog.EventLogUtils
import ch.epfl.bluebrain.nexus.delta.sdk.generators.ProjectGen
import ch.epfl.bluebrain.nexus.delta.sdk.indexing.DummyIndexingCoordinator
import ch.epfl.bluebrain.nexus.delta.sdk.indexing.DummyIndexingCoordinator.CoordinatorCounts
import ch.epfl.bluebrain.nexus.delta.sdk.marshalling.{RdfExceptionHandler, RdfRejectionHandler}
import ch.epfl.bluebrain.nexus.delta.sdk.model.IdSegment.StringSegment
import ch.epfl.bluebrain.nexus.delta.sdk.model.acls.{Acl, AclAddress}
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Identity.{Anonymous, Authenticated, Group, User}
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.{AuthToken, Caller, Identity}
import ch.epfl.bluebrain.nexus.delta.sdk.model.permissions.Permission
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.ProjectCountsCollection.ProjectCount
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.{ApiMappings, ProjectCountsCollection, ProjectRef}
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.ResultEntry.UnscoredResultEntry
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.SearchResults.UnscoredSearchResults
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.{Pagination, SearchResults}
import ch.epfl.bluebrain.nexus.delta.sdk.model.{permissions => _, _}
import ch.epfl.bluebrain.nexus.delta.sdk.syntax._
import ch.epfl.bluebrain.nexus.delta.sdk.testkit._
import ch.epfl.bluebrain.nexus.delta.sdk.utils.RouteHelpers
import ch.epfl.bluebrain.nexus.delta.sdk.{ProgressesStatistics, ProjectsCounts}
import ch.epfl.bluebrain.nexus.delta.sourcing.EventLog
import ch.epfl.bluebrain.nexus.delta.sourcing.projections.ProjectionId.ViewProjectionId
import ch.epfl.bluebrain.nexus.delta.sourcing.projections.{ProjectionId, ProjectionProgress}
import ch.epfl.bluebrain.nexus.testkit._
import io.circe.Json
import monix.bio.{IO, Task, UIO}
import monix.execution.Scheduler
import org.scalatest._
import org.scalatest.matchers.should.Matchers
import slick.jdbc.JdbcBackend

import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.util.UUID

class BlazegraphViewsRoutesSpec
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
    with BeforeAndAfterAll
    with TestHelpers {

  import akka.actor.typed.scaladsl.adapter._
  implicit val typedSystem = system.toTyped

  val uuid                                  = UUID.randomUUID()
  implicit val uuidF: UUIDF                 = UUIDF.fixed(uuid)
  implicit val sc: Scheduler                = Scheduler.global
  val realm                                 = Label.unsafe("myrealm")
  val bob                                   = User("Bob", realm)
  implicit val caller: Caller               = Caller(bob, Set(bob, Group("mygroup", realm), Authenticated(realm)))
  implicit val baseUri: BaseUri             = BaseUri("http://localhost", Label.unsafe("v1"))
  implicit val rcr: RemoteContextResolution = RemoteContextResolution.fixed(
    Vocabulary.contexts.metadata   -> jsonContentOf("/contexts/metadata.json"),
    Vocabulary.contexts.error      -> jsonContentOf("contexts/error.json"),
    Vocabulary.contexts.statistics -> jsonContentOf("/contexts/statistics.json"),
    Vocabulary.contexts.offset     -> jsonContentOf("/contexts/offset.json"),
    Vocabulary.contexts.tags       -> jsonContentOf("contexts/tags.json"),
    Vocabulary.contexts.search     -> jsonContentOf("contexts/search.json"),
    contexts.blazegraph            -> jsonContentOf("/contexts/blazegraph.json")
  )
  private val identities                    = IdentitiesDummy(Map(AuthToken("bob") -> caller))
  private val asBob                         = addCredentials(OAuth2BearerToken("bob"))

  val indexingSource  = jsonContentOf("indexing-view-source.json")
  val aggregateSource = jsonContentOf("aggregate-view-source.json")

  val updatedIndexingSource = indexingSource.mapObject(_.add("resourceTag", Json.fromString("v1.5")))

  val indexingViewId = nxv + "indexing-view"

  val resource = nxv + "resource-incoming-outgoing"

  val undefinedPermission = Permission.unsafe("not/defined")

  val allowedPerms = Set(
    defaultPermission,
    permissions.read,
    permissions.write,
    events.read
  )

  private val perms  = PermissionsDummy(allowedPerms).accepted
  private val realms = RealmSetup.init(realm).accepted
  private val acls   = AclsDummy(perms, realms).accepted

  val org           = Label.unsafe("org")
  val orgDeprecated = Label.unsafe("org-deprecated")
  val base          = nxv.base

  val project                  = ProjectGen.project("org", "proj", base = base, mappings = ApiMappings("example" -> iri"http://example.com/"))
  val deprecatedProject        = ProjectGen.project("org", "proj-deprecated")
  val projectWithDeprecatedOrg = ProjectGen.project("org-deprecated", "other-proj")
  val projectRef               = project.ref
  def projectSetup             =
    ProjectSetup
      .init(
        orgsToCreate = org :: orgDeprecated :: Nil,
        projectsToCreate = project :: deprecatedProject :: projectWithDeprecatedOrg :: Nil,
        projectsToDeprecate = deprecatedProject.ref :: Nil,
        organizationsToDeprecate = orgDeprecated :: Nil
      )

  val viewRef                                     = ViewRef(project.ref, indexingViewId)
  val config                                      = BlazegraphViewsConfig(
    "http://localhost",
    None,
    httpClientConfig,
    aggregate,
    keyValueStore,
    pagination,
    externalIndexing
  )
  implicit val ordering: JsonKeyOrdering          = JsonKeyOrdering.alphabetical
  implicit val rejectionHandler: RejectionHandler = RdfRejectionHandler.apply
  implicit val exceptionHandler: ExceptionHandler = RdfExceptionHandler.apply

  val tag = TagLabel.unsafe("v1.5")

  val doesntExistId = nxv + "doesntexist"

  private val now          = Instant.now()
  private val nowMinus5    = now.minusSeconds(5)
  private val projectStats = ProjectCount(10, now)

  val projectsCounts       = new ProjectsCounts {
    override def get(): UIO[ProjectCountsCollection]                 =
      UIO(ProjectCountsCollection(Map(projectRef -> projectStats)))
    override def get(project: ProjectRef): UIO[Option[ProjectCount]] = get().map(_.get(project))
  }
  val viewsProgressesCache =
    KeyValueStore.localLRU[ProjectionId, ProjectionProgress[Unit]]("view-progress", 10).accepted
  val statisticsProgress   = new ProgressesStatistics(viewsProgressesCache, projectsCounts)

  implicit val externalIndexingConfig = config.indexing
  implicit val paginationConfig       = config.pagination

  val queryResults = SparqlResults(
    SparqlResults.Head(List("s", "p", "o")),
    Bindings(
      List(
        Map(
          "s" -> Binding("uri", "http://example.com/subject"),
          "p" -> Binding("uri", "https://bluebrain.github.io/nexus/vocabulary/bool"),
          "o" -> Binding("literal", "false", None, Some("http://www.w3.org/2001/XMLSchema#boolean"))
        ),
        Map(
          "s" -> Binding("uri", "http://example.com/subject"),
          "p" -> Binding("uri", "https://bluebrain.github.io/nexus/vocabulary/number"),
          "o" -> Binding("literal", "1", None, Some("http://www.w3.org/2001/XMLSchema#integer"))
        ),
        Map(
          "s" -> Binding("uri", "http://example.com/subject"),
          "p" -> Binding("uri", "http://www.w3.org/1999/02/22-rdf-syntax-ns#type"),
          "o" -> Binding("uri", nxv.View.toString)
        )
      )
    )
  )

  val linksResults: SearchResults[SparqlLink] = UnscoredSearchResults(
    2,
    List(
      UnscoredResultEntry(
        SparqlResourceLink(
          ResourceF(
          iri"http://example.com/id1",
            ResourceUris.resource(projectRef, projectRef, iri"http://example.com/id1", ResourceRef(iri"http://example.com/someSchema"))(project.apiMappings, project.base),
            1L,
          Set(iri"http://example.com/type1", iri"http://example.com/type2"),
          false,
          Instant.EPOCH,
            Identity.Anonymous,
          Instant.EPOCH,
            Identity.Anonymous,
          ResourceRef(iri"http://example.com/someSchema"),
          List(iri"http://example.com/property1", iri"http://example.com/property2")
        )
      )),
      UnscoredResultEntry(
        SparqlExternalLink(
          iri"http://example.com/external",
          List(iri"http://example.com/property3", iri"http://example.com/property4"),
          Set(iri"http://example.com/type3", iri"http://example.com/type4")
        )
      )

  ))

  val viewsQuery = new BlazegraphViewsQuery {

    override def query(id: IdSegment, project: ProjectRef, query: SparqlQuery)(implicit
        caller: Caller
    ): IO[BlazegraphViewRejection, SparqlResults] =
      if (project == projectRef && id == StringSegment("indexing-view") && query.value == "select * WHERE {?s ?p ?o}")
        IO.pure(queryResults)
      else
        IO.raiseError(ViewNotFound(nxv + "id", project))

    override def incoming(id: IdSegment, project: ProjectRef, pagination: Pagination.FromPagination)(implicit
        caller: Caller,
 base: BaseUri
    ): IO[BlazegraphViewRejection, SearchResults[SparqlLink]] =
      if (project == projectRef && id == StringSegment("resource-incoming-outgoing"))
        IO.pure(linksResults)
      else
        IO.raiseError(ViewNotFound(defaultViewId, project))

    override def outgoing(
        id: IdSegment,
        project: ProjectRef,
        pagination: Pagination.FromPagination,
        includeExternalLinks: Boolean
    )(implicit caller: Caller, base: BaseUri): IO[BlazegraphViewRejection, SearchResults[SparqlLink]] =
      if (project == projectRef && id == StringSegment("resource-incoming-outgoing"))
        IO.pure(linksResults)
      else
        IO.raiseError(ViewNotFound(defaultViewId, project))
  }

  val (coordinatorCounts, routes) = (for {
    eventLog          <- EventLog.postgresEventLog[Envelope[BlazegraphViewEvent]](EventLogUtils.toEnvelope).hideErrors
    (orgs, projects)  <- projectSetup
    coordinatorCounts <- Ref.of[Task, Map[ProjectionId, CoordinatorCounts]](Map.empty)
    coordinator        = new DummyIndexingCoordinator[IndexingViewResource](coordinatorCounts)
    views             <- BlazegraphViews(config, eventLog, perms, orgs, projects, _ => UIO.unit, _ => UIO.unit)
    routes             =
      Route.seal(BlazegraphViewsRoutes(views, viewsQuery, identities, acls, projects, statisticsProgress, coordinator))
  } yield (coordinatorCounts, routes)).accepted

  "Blazegraph view routes" should {
    "fail to create a view without permission" in {
      acls.append(Acl(AclAddress.Root, Anonymous -> Set(events.read)), 0L).accepted
      Post("/v1/views/org/proj", indexingSource.toEntity) ~> routes ~> check {
        response.status shouldEqual StatusCodes.Forbidden
        response.asJson shouldEqual jsonContentOf("routes/errors/authorization-failed.json")
      }
    }
    "create an indexing view" in {
      acls
        .append(Acl(AclAddress.Root, caller.subject -> Set(permissions.write, permissions.read)), 1L)
        .accepted
      Post("/v1/views/org/proj", indexingSource.toEntity) ~> asBob ~> routes ~> check {
        response.status shouldEqual StatusCodes.Created
        response.asJson shouldEqual jsonContentOf(
          "routes/responses/indexing-view-metadata.json",
          "rev"        -> 1,
          "deprecated" -> false
        )
      }
    }

    "create an aggregate view" in {
      Put("/v1/views/org/proj/aggregate-view", aggregateSource.toEntity) ~> asBob ~> routes ~> check {
        response.status shouldEqual StatusCodes.Created
        response.asJson shouldEqual jsonContentOf("routes/responses/aggregate-view-metadata.json")
      }
    }

    "query a view" in {
      val queryEntity = HttpEntity(RdfMediaTypes.`application/sparql-query`, ByteString("select * WHERE {?s ?p ?o}"))

      val postRequest = Post("/v1/views/org/proj/indexing-view/sparql", queryEntity).withHeaders(
        Accept(RdfMediaTypes.`application/sparql-results+json`)
      )
      val getRequest  = Get(
        s"/v1/views/org/proj/indexing-view/sparql?query=${URLEncoder.encode("select * WHERE {?s ?p ?o}", StandardCharsets.UTF_8)}"
      ).withHeaders(
        Accept(RdfMediaTypes.`application/sparql-results+json`)
      )

      forAll(List(postRequest, getRequest)) { req =>
        req ~> asBob ~> routes ~> check {
          response.status shouldEqual StatusCodes.OK
          response.header[`Content-Type`].value.value shouldEqual RdfMediaTypes.`application/sparql-results+json`.value
          response.asJson shouldEqual jsonContentOf("routes/responses/query-response.json")
        }
      }
    }

    "reject creation of a view which already exits" in {
      Put("/v1/views/org/proj/aggregate-view", aggregateSource.toEntity) ~> asBob ~> routes ~> check {
        response.status shouldEqual StatusCodes.Conflict
        response.asJson shouldEqual jsonContentOf("routes/errors/view-already-exists.json")
      }

    }
    "fail to update a view without permission" in {
      Put("/v1/views/org/proj/aggregate-view?rev=1", aggregateSource.toEntity) ~> routes ~> check {
        response.status shouldEqual StatusCodes.Forbidden
        response.asJson shouldEqual jsonContentOf("routes/errors/authorization-failed.json")
      }
    }
    "update a view" in {
      Put("/v1/views/org/proj/indexing-view?rev=1", updatedIndexingSource.toEntity) ~> asBob ~> routes ~> check {
        response.status shouldEqual StatusCodes.OK
        response.asJson shouldEqual jsonContentOf(
          "routes/responses/indexing-view-metadata.json",
          "rev"        -> 2,
          "deprecated" -> false
        )

      }
    }
    "reject update of a view at a non-existent revision" in {
      Put("/v1/views/org/proj/indexing-view?rev=3", updatedIndexingSource.toEntity) ~> asBob ~> routes ~> check {
        response.status shouldEqual StatusCodes.Conflict
        response.asJson shouldEqual jsonContentOf("routes/errors/incorrect-rev.json", "provided" -> 3, "expected" -> 2)

      }
    }

    "tag a view" in {
      val payload = json"""{"tag": "mytag", "rev": 1}"""
      Post("/v1/views/org/proj/indexing-view/tags?rev=2", payload.toEntity) ~> asBob ~> routes ~> check {
        status shouldEqual StatusCodes.Created
        response.asJson shouldEqual jsonContentOf(
          "routes/responses/indexing-view-metadata.json",
          "rev"        -> 3,
          "deprecated" -> false
        )
      }
    }

    "fail to deprecate a view without permission" in {
      Delete("/v1/views/org/proj/indexing-view?rev=3", updatedIndexingSource.toEntity) ~> routes ~> check {
        response.status shouldEqual StatusCodes.Forbidden
        response.asJson shouldEqual jsonContentOf("routes/errors/authorization-failed.json")
      }
    }
    "reject a deprecation of a view without rev" in {
      Delete("/v1/views/org/proj/indexing-view", updatedIndexingSource.toEntity) ~> asBob ~> routes ~> check {
        response.status shouldEqual StatusCodes.BadRequest
        response.asJson shouldEqual jsonContentOf("routes/errors/missing-query-param.json", "field" -> "rev")
      }
    }
    "deprecate a view" in {
      Delete("/v1/views/org/proj/indexing-view?rev=3", updatedIndexingSource.toEntity) ~> asBob ~> routes ~> check {
        response.status shouldEqual StatusCodes.OK
        response.asJson shouldEqual jsonContentOf(
          "routes/responses/indexing-view-metadata.json",
          "rev"        -> 4,
          "deprecated" -> true
        )

      }
    }

    "fail to fetch a view without permission" in {
      Get("/v1/views/org/proj/indexing-view") ~> routes ~> check {
        response.status shouldEqual StatusCodes.Forbidden
        response.asJson shouldEqual jsonContentOf("routes/errors/authorization-failed.json")

      }
    }
    "fetch a view" in {
      Get("/v1/views/org/proj/indexing-view") ~> asBob ~> routes ~> check {
        response.status shouldEqual StatusCodes.OK
        response.asJson shouldEqual jsonContentOf(
          "routes/responses/indexing-view.json",
          "uuid"       -> uuid,
          "deprecated" -> true,
          "rev"        -> 4
        )
      }
    }
    "fetch a view by rev or tag" in {
      forAll(List(Get("/v1/views/org/proj/indexing-view?tag=mytag"), Get("/v1/views/org/proj/indexing-view?rev=1"))) {
        req =>
          req ~> asBob ~> routes ~> check {
            response.status shouldEqual StatusCodes.OK
            response.asJson shouldEqual jsonContentOf(
              "routes/responses/indexing-view.json",
              "uuid"       -> uuid,
              "deprecated" -> false,
              "rev"        -> 1
            ).mapObject(_.remove("resourceTag"))
          }
      }

    }
    "fetch a view source" in {
      Get("/v1/views/org/proj/indexing-view/source") ~> asBob ~> routes ~> check {
        response.status shouldEqual StatusCodes.OK
        response.asJson shouldEqual updatedIndexingSource
      }
    }
    "fetch the view tags" in {
      Get("/v1/views/org/proj/indexing-view/tags") ~> asBob ~> routes ~> check {
        response.status shouldEqual StatusCodes.OK
        response.asJson shouldEqual json"""{"tags": [{"rev": 1, "tag": "mytag"}]}""".addContext(
          Vocabulary.contexts.tags
        )
      }
    }
    "reject if provided rev and tag simultaneously" in {
      Get("/v1/views/org/proj/indexing-view?rev=1&tag=mytag") ~> asBob ~> routes ~> check {
        status shouldEqual StatusCodes.BadRequest
        response.asJson shouldEqual jsonContentOf("routes/errors/tag-and-rev-error.json")
      }
    }

    "fail to fetch statistics and offset from view without resources/read permission" in {

      val endpoints = List(
        "/v1/views/org/proj/indexing-view/statistics",
        "/v1/views/org/proj/indexing-view/offset"
      )
      forAll(endpoints) { endpoint =>
        Get(endpoint) ~> routes ~> check {
          response.status shouldEqual StatusCodes.Forbidden
          response.asJson shouldEqual jsonContentOf("routes/errors/authorization-failed.json")
        }
      }
    }

    "fetch statistics from view" in {
      val projectionId = ViewProjectionId(s"blazegraph-${uuid}_4")
      viewsProgressesCache.put(projectionId, ProjectionProgress(Sequence(2), nowMinus5, 2, 0, 0, 0)).accepted

      Get("/v1/views/org/proj/indexing-view/statistics") ~> asBob ~> routes ~> check {
        response.status shouldEqual StatusCodes.OK
        response.asJson shouldEqual jsonContentOf(
          "routes/responses/statistics.json",
          "projectLatestInstant" -> now,
          "viewLatestInstant"    -> nowMinus5
        )
      }
    }

    "fetch offset from view" in {
      Get("/v1/views/org/proj/indexing-view/offset") ~> asBob ~> routes ~> check {
        response.status shouldEqual StatusCodes.OK
        response.asJson shouldEqual jsonContentOf("routes/responses/offset.json")
      }
    }

    "fetch incoming links" in {
      forAll(
        List(
          Get("/v1/resources/org/proj/notimportant/resource-incoming-outgoing/incoming"),
          Get("/v1/views/org/proj/resource-incoming-outgoing/incoming"),
          Get("/v1/resolvers/org/proj/resource-incoming-outgoing/incoming"),
          Get("/v1/files/org/proj/resource-incoming-outgoing/incoming"),
          Get("/v1/storages/org/proj/resource-incoming-outgoing/incoming")
        )
      ) { req =>
        req ~> asBob ~> routes ~> check {
          response.status shouldEqual StatusCodes.OK
          response.asJson shouldEqual jsonContentOf("routes/responses/incoming-outgoing.json")
        }
      }
    }

    "fetch outgoing links" in {
      forAll(
        List(
          Get("/v1/resources/org/proj/notimportant/resource-incoming-outgoing/outgoing"),
          Get("/v1/views/org/proj/resource-incoming-outgoing/outgoing"),
          Get("/v1/resolvers/org/proj/resource-incoming-outgoing/outgoing"),
          Get("/v1/files/org/proj/resource-incoming-outgoing/outgoing"),
          Get("/v1/storages/org/proj/resource-incoming-outgoing/outgoing")
        )
      ) { req =>
        req ~> asBob ~> routes ~> check {
          response.status shouldEqual StatusCodes.OK
          response.asJson shouldEqual jsonContentOf("routes/responses/incoming-outgoing.json")
        }
      }
    }

    "fail to fetch incoming or outgoing links without permission" in {
      forAll(
        List(
          Get("/v1/resources/org/proj/notimportant/resource-incoming-outgoing/incoming"),
          Get("/v1/views/org/proj/resource-incoming-outgoing/incoming"),
          Get("/v1/resolvers/org/proj/resource-incoming-outgoing/incoming"),
          Get("/v1/files/org/proj/resource-incoming-outgoing/incoming"),
          Get("/v1/storages/org/proj/resource-incoming-outgoing/incoming"),
          Get("/v1/resources/org/proj/notimportant/resource-incoming-outgoing/outgoing"),
          Get("/v1/views/org/proj/resource-incoming-outgoing/outgoing"),
          Get("/v1/resolvers/org/proj/resource-incoming-outgoing/outgoing"),
          Get("/v1/files/org/proj/resource-incoming-outgoing/outgoing"),
          Get("/v1/storages/org/proj/resource-incoming-outgoing/outgoing")
        )
      ) { req =>
        req ~> routes ~> check {
          response.status shouldEqual StatusCodes.Forbidden
          response.asJson shouldEqual jsonContentOf("routes/errors/authorization-failed.json")
        }
      }
    }
    "fail to restart offset from view without resources/write permission" in {

      Delete("/v1/views/org/proj/indexing-view/offset") ~> routes ~> check {
        response.status shouldEqual StatusCodes.Forbidden
        response.asJson shouldEqual jsonContentOf("/routes/errors/authorization-failed.json")
      }
    }

    "restart offset from view" in {
      val projectionId = ViewProjectionId(s"blazegraph-${uuid}_4")

      acls.append(Acl(AclAddress.Root, Anonymous -> Set(permissions.write)), 2L).accepted
      coordinatorCounts.get.accepted.get(projectionId) shouldEqual None
      Delete("/v1/views/org/proj/indexing-view/offset") ~> routes ~> check {
        response.status shouldEqual StatusCodes.OK
        response.asJson shouldEqual json"""{"@context": "${Vocabulary.contexts.offset}", "@type": "NoOffset"}"""
        coordinatorCounts.get.accepted.get(projectionId).value shouldEqual CoordinatorCounts(0, 1, 0)
      }
    }
  }

  private var db: JdbcBackend.Database = null

  override protected def createActorSystem(): ActorSystem =
    ActorSystem("BlazegraphViewsRoutesSpec", AbstractDBSpec.config)

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
