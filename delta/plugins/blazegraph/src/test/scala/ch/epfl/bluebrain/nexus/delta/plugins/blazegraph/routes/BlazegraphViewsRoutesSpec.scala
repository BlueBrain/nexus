package ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.routes

import akka.actor.ActorSystem
import akka.http.scaladsl.model.MediaTypes.`text/html`
import akka.http.scaladsl.model.headers.{`Content-Type`, Accept, Location, OAuth2BearerToken}
import akka.http.scaladsl.model.{HttpEntity, StatusCodes, Uri}
import akka.http.scaladsl.server.{ExceptionHandler, RejectionHandler, Route}
import akka.persistence.query.Sequence
import akka.util.ByteString
import ch.epfl.bluebrain.nexus.delta.kernel.utils.{UUIDF, UrlUtils}
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.client.{SparqlQueryClientDummy, SparqlResults}
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.model.BlazegraphViewRejection.ProjectContextRejection
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.model._
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.{BlazegraphViewsSetup, Fixtures}
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.RdfMediaTypes._
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.nxv
import ch.epfl.bluebrain.nexus.delta.rdf.query.SparqlQuery
import ch.epfl.bluebrain.nexus.delta.rdf.query.SparqlQuery.SparqlConstructQuery
import ch.epfl.bluebrain.nexus.delta.rdf.utils.JsonKeyOrdering
import ch.epfl.bluebrain.nexus.delta.sdk.ProgressesStatistics
import ch.epfl.bluebrain.nexus.delta.sdk.acls.AclSimpleCheck
import ch.epfl.bluebrain.nexus.delta.sdk.acls.model.AclAddress
import ch.epfl.bluebrain.nexus.delta.sdk.cache.KeyValueStore
import ch.epfl.bluebrain.nexus.delta.sdk.fusion.FusionConfig
import ch.epfl.bluebrain.nexus.delta.sdk.identities.IdentitiesDummy
import ch.epfl.bluebrain.nexus.delta.sdk.identities.model.Caller
import ch.epfl.bluebrain.nexus.delta.sdk.implicits._
import ch.epfl.bluebrain.nexus.delta.sdk.marshalling.{RdfExceptionHandler, RdfRejectionHandler}
import ch.epfl.bluebrain.nexus.delta.sdk.model._
import ch.epfl.bluebrain.nexus.delta.sdk.permissions.Permissions.events
import ch.epfl.bluebrain.nexus.delta.sdk.projects.FetchContextDummy
import ch.epfl.bluebrain.nexus.delta.sdk.projects.model.ProjectStatistics
import ch.epfl.bluebrain.nexus.delta.sdk.testkit._
import ch.epfl.bluebrain.nexus.delta.sdk.utils.RouteHelpers
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.{Anonymous, Authenticated, Group, User}
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Tag.UserTag
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{Label, ProjectRef}
import ch.epfl.bluebrain.nexus.delta.sourcing.projections.ProjectionId.ViewProjectionId
import ch.epfl.bluebrain.nexus.delta.sourcing.projections.{ProjectionId, ProjectionProgress}
import ch.epfl.bluebrain.nexus.testkit._
import io.circe.Json
import io.circe.syntax._
import monix.bio.UIO
import monix.execution.Scheduler
import org.scalatest._
import org.scalatest.matchers.should.Matchers
import slick.jdbc.JdbcBackend

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
    with TestHelpers
    with Fixtures
    with BlazegraphViewRoutesFixtures {

  import akka.actor.typed.scaladsl.adapter._
  implicit private val typedSystem = system.toTyped

  private val uuid                      = UUID.randomUUID()
  implicit private val uuidF: UUIDF     = UUIDF.fixed(uuid)
  implicit private val sc: Scheduler    = Scheduler.global
  private val realm                     = Label.unsafe("myrealm")
  private val bob                       = User("Bob", realm)
  implicit private val caller: Caller   = Caller(bob, Set(bob, Group("mygroup", realm), Authenticated(realm)))
  implicit private val baseUri: BaseUri = BaseUri("http://localhost", Label.unsafe("v1"))
  private val identities                = IdentitiesDummy(caller)
  private val asBob                     = addCredentials(OAuth2BearerToken("bob"))

  val indexingSource          = jsonContentOf("indexing-view-source.json")
  private val aggregateSource = jsonContentOf("aggregate-view-source.json")

  val updatedIndexingSource = indexingSource.mapObject(_.add("resourceTag", Json.fromString("v1.5")))

  private val indexingViewId = nxv + "indexing-view"

  private val allowedPerms = Set(
    permissions.query,
    permissions.read,
    permissions.write,
    events.read
  )

  private val fetchContext = FetchContextDummy[BlazegraphViewRejection](
    Map(project.ref -> project.context),
    Set(deprecatedProject.ref),
    ProjectContextRejection
  )

  implicit private val ordering: JsonKeyOrdering  =
    JsonKeyOrdering.default(topKeys =
      List("@context", "@id", "@type", "reason", "details", "sourceId", "projectionId", "_total", "_results")
    )
  implicit val rejectionHandler: RejectionHandler = RdfRejectionHandler.apply
  implicit val exceptionHandler: ExceptionHandler = RdfExceptionHandler.apply

  val tag = UserTag.unsafe("v1.5")

  val doesntExistId = nxv + "doesntexist"

  private val now       = Instant.now()
  private val nowMinus5 = now.minusSeconds(5)

  val viewsProgressesCache =
    KeyValueStore.localLRU[ProjectionId, ProjectionProgress[Unit]](10L).accepted
  val statisticsProgress   = new ProgressesStatistics(
    viewsProgressesCache,
    ioFromMap(
      projectRef -> ProjectStatistics(events = 10, resources = 10, now)
    )
  )

  implicit val externalIndexingConfig  = externalIndexing
  implicit val paginationConfig        = pagination
  implicit private val f: FusionConfig = fusionConfig

  private val selectQuery    = SparqlQuery("SELECT * {?s ?p ?o}")
  private val constructQuery = SparqlConstructQuery("CONSTRUCT {?s ?p ?o} WHERE {?s ?p ?o}").rightValue

  var restartedView: Option[(ProjectRef, Iri)] = None

  private def restart(id: Iri, projectRef: ProjectRef) = UIO { restartedView = Some(projectRef -> id) }.void
  private val views                                    = BlazegraphViewsSetup.init(fetchContext, allowedPerms)

  val viewsQuery = new BlazegraphViewsQueryDummy(
    projectRef,
    new SparqlQueryClientDummy(),
    views,
    Map("resource-incoming-outgoing" -> linksResults)
  )

  private val aclCheck = AclSimpleCheck().accepted
  private val routes   =
    Route.seal(
      BlazegraphViewsRoutes(
        views,
        viewsQuery,
        identities,
        aclCheck,
        null,
        statisticsProgress,
        restart,
        IndexingActionDummy()
      )
    )

  "Blazegraph view routes" should {
    "fail to create a view without permission" in {
      aclCheck.append(AclAddress.Root, Anonymous -> Set(events.read)).accepted
      Post("/v1/views/org/proj", indexingSource.toEntity) ~> routes ~> check {
        response.status shouldEqual StatusCodes.Forbidden
        response.asJson shouldEqual jsonContentOf("routes/errors/authorization-failed.json")
      }
    }
    "create an indexing view" in {
      aclCheck
        .append(AclAddress.Root, caller.subject -> Set(permissions.write, permissions.read))
        .accepted
      Post("/v1/views/org/proj", indexingSource.toEntity) ~> asBob ~> routes ~> check {
        response.status shouldEqual StatusCodes.Created
        response.asJson shouldEqual jsonContentOf(
          "routes/responses/indexing-view-metadata.json",
          "uuid"       -> uuid,
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

      val list = List(
        (`application/sparql-results+json`, selectQuery, SparqlResults.empty.asJson.deepDropNullValues.noSpaces),
        (`application/sparql-results+xml`, selectQuery, ""),
        (`application/n-triples`, constructQuery, ""),
        (`application/rdf+xml`, constructQuery, ""),
        (`application/ld+json`, constructQuery, "{}")
      )

      forAll(list) { case (mediaType, query, expected) =>
        val queryEntity = HttpEntity(`application/sparql-query`, ByteString(query.value))
        val encodedQ    = UrlUtils.encode(query.value)
        val postRequest = Post("/v1/views/org/proj/indexing-view/sparql", queryEntity).withHeaders(Accept(mediaType))
        val getRequest  = Get(s"/v1/views/org/proj/indexing-view/sparql?query=$encodedQ").withHeaders(Accept(mediaType))
        forAll(List(postRequest, getRequest)) { req =>
          req ~> asBob ~> routes ~> check {
            response.status shouldEqual StatusCodes.OK
            response.header[`Content-Type`].value.value shouldEqual mediaType.value
            response.asString shouldEqual expected
          }
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
          "uuid"       -> uuid,
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
          "uuid"       -> uuid,
          "rev"        -> 3,
          "deprecated" -> false
        )
      }
    }

    "fail to deprecate a view without permission" in {
      Delete("/v1/views/org/proj/indexing-view?rev=3") ~> routes ~> check {
        response.status shouldEqual StatusCodes.Forbidden
        response.asJson shouldEqual jsonContentOf("routes/errors/authorization-failed.json")
      }
    }
    "reject a deprecation of a view without rev" in {
      Delete("/v1/views/org/proj/indexing-view") ~> asBob ~> routes ~> check {
        response.status shouldEqual StatusCodes.BadRequest
        response.asJson shouldEqual jsonContentOf("routes/errors/missing-query-param.json", "field" -> "rev")
      }
    }
    "deprecate a view" in {
      Delete("/v1/views/org/proj/indexing-view?rev=3") ~> asBob ~> routes ~> check {
        response.status shouldEqual StatusCodes.OK
        response.asJson shouldEqual jsonContentOf(
          "routes/responses/indexing-view-metadata.json",
          "uuid"       -> uuid,
          "rev"        -> 4,
          "deprecated" -> true
        )

      }
    }

    "reject querying a deprecated view" in {
      val queryEntity = HttpEntity(`application/sparql-query`, ByteString(selectQuery.value))
      Post("/v1/views/org/proj/indexing-view/sparql", queryEntity) ~> asBob ~> routes ~> check {
        response.status shouldEqual StatusCodes.BadRequest
        response.asJson shouldEqual jsonContentOf("/routes/errors/view-deprecated.json", "id" -> indexingViewId)
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
      val endpoints = List(
        "/v1/views/org/proj/indexing-view?tag=mytag",
        "/v1/resources/org/proj/_/indexing-view?tag=mytag",
        "/v1/resources/org/proj/view/indexing-view?tag=mytag",
        "/v1/views/org/proj/indexing-view?rev=1",
        "/v1/resources/org/proj/_/indexing-view?rev=1",
        "/v1/resources/org/proj/view/indexing-view?rev=1"
      )
      forAll(endpoints) { endpoint =>
        Get(endpoint) ~> asBob ~> routes ~> check {
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
      val endpoints = List(
        "/v1/views/org/proj/indexing-view/source",
        "/v1/resources/org/proj/_/indexing-view/source",
        "/v1/resources/org/proj/view/indexing-view/source"
      )
      forAll(endpoints) { endpoint =>
        Get(endpoint) ~> asBob ~> routes ~> check {
          response.status shouldEqual StatusCodes.OK
          response.asJson shouldEqual updatedIndexingSource
        }
      }
    }
    "fetch the view tags" in {
      val endpoints = List(
        "/v1/views/org/proj/indexing-view/tags",
        "/v1/resources/org/proj/_/indexing-view/tags",
        "/v1/resources/org/proj/view/indexing-view/tags"
      )
      forAll(endpoints) { endpoint =>
        Get(endpoint) ~> asBob ~> routes ~> check {
          response.status shouldEqual StatusCodes.OK
          response.asJson shouldEqual
            json"""{"tags": [{"rev": 1, "tag": "mytag"}]}""".addContext(
              Vocabulary.contexts.tags
            )
        }
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
      aclCheck.append(AclAddress.Root, Anonymous -> Set(permissions.write)).accepted
      restartedView shouldEqual None
      Delete("/v1/views/org/proj/indexing-view/offset") ~> routes ~> check {
        response.status shouldEqual StatusCodes.OK
        response.asJson shouldEqual json"""{"@context": "${Vocabulary.contexts.offset}", "@type": "NoOffset"}"""
        restartedView shouldEqual Some(projectRef -> indexingViewId)
      }
    }

    "redirect to fusion for the latest version if the Accept header is set to text/html" in {
      Get("/v1/views/org/proj/indexing-view") ~> Accept(`text/html`) ~> routes ~> check {
        response.status shouldEqual StatusCodes.SeeOther
        response.header[Location].value.uri shouldEqual Uri(
          "https://bbp.epfl.ch/nexus/web/org/proj/resources/indexing-view"
        )
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
