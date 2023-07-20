package ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.routes

import akka.actor.typed.ActorSystem
import akka.http.scaladsl.model.MediaTypes.`text/html`
import akka.http.scaladsl.model.headers._
import akka.http.scaladsl.model.{HttpEntity, MediaTypes, StatusCodes, Uri}
import akka.http.scaladsl.server.{ExceptionHandler, RejectionHandler, Route}
import akka.util.ByteString
import ch.epfl.bluebrain.nexus.delta.kernel.utils.{UUIDF, UrlUtils}
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.client.{SparqlQueryClientDummy, SparqlResults}
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.model.BlazegraphViewRejection.ProjectContextRejection
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.model._
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.{BlazegraphViews, Fixtures}
import ch.epfl.bluebrain.nexus.delta.rdf.RdfMediaTypes._
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.nxv
import ch.epfl.bluebrain.nexus.delta.rdf.query.SparqlQuery
import ch.epfl.bluebrain.nexus.delta.rdf.query.SparqlQuery.SparqlConstructQuery
import ch.epfl.bluebrain.nexus.delta.rdf.utils.JsonKeyOrdering
import ch.epfl.bluebrain.nexus.delta.sdk.acls.AclSimpleCheck
import ch.epfl.bluebrain.nexus.delta.sdk.acls.model.AclAddress
import ch.epfl.bluebrain.nexus.delta.sdk.directives.DeltaSchemeDirectives
import ch.epfl.bluebrain.nexus.delta.sdk.fusion.FusionConfig
import ch.epfl.bluebrain.nexus.delta.sdk.identities.IdentitiesDummy
import ch.epfl.bluebrain.nexus.delta.sdk.identities.model.Caller
import ch.epfl.bluebrain.nexus.delta.sdk.implicits._
import ch.epfl.bluebrain.nexus.delta.sdk.marshalling.{RdfExceptionHandler, RdfRejectionHandler}
import ch.epfl.bluebrain.nexus.delta.sdk.model._
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.PaginationConfig
import ch.epfl.bluebrain.nexus.delta.sdk.permissions.Permissions.events
import ch.epfl.bluebrain.nexus.delta.sdk.projects.FetchContextDummy
import ch.epfl.bluebrain.nexus.delta.sdk.resolvers.ResolverContextResolution
import ch.epfl.bluebrain.nexus.delta.sdk.utils.RouteHelpers
import ch.epfl.bluebrain.nexus.delta.sdk.{ConfigFixtures, IndexingAction}
import ch.epfl.bluebrain.nexus.delta.sourcing.config.QueryConfig
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.{Anonymous, Authenticated, Group, User}
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{EntityType, Label}
import ch.epfl.bluebrain.nexus.delta.sourcing.offset.Offset
import ch.epfl.bluebrain.nexus.delta.sourcing.postgres.DoobieScalaTestFixture
import ch.epfl.bluebrain.nexus.delta.sourcing.projections.Projections
import ch.epfl.bluebrain.nexus.delta.sourcing.projections.model.ProjectionRestart
import ch.epfl.bluebrain.nexus.delta.sourcing.query.RefreshStrategy
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.Elem.{FailedElem, SuccessElem}
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.ProjectionMetadata
import ch.epfl.bluebrain.nexus.testkit._
import io.circe.Json
import io.circe.syntax._
import monix.bio.UIO
import monix.execution.Scheduler
import org.scalatest._
import org.scalatest.matchers.should.Matchers

import java.time.Instant
import java.util.UUID
import scala.concurrent.duration._

class BlazegraphViewsRoutesSpec
    extends RouteHelpers
    with DoobieScalaTestFixture
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
  implicit private val typedSystem: ActorSystem[Nothing] = system.toTyped

  private val prefix                    = "prefix"
  private val uuid                      = UUID.randomUUID()
  implicit private val uuidF: UUIDF     = UUIDF.fixed(uuid)
  implicit private val sc: Scheduler    = Scheduler.global
  private val realm                     = Label.unsafe("myrealm")
  private val bob                       = User("Bob", realm)
  implicit private val caller: Caller   = Caller(bob, Set(bob, Group("mygroup", realm), Authenticated(realm)))
  implicit private val baseUri: BaseUri = BaseUri("http://localhost", Label.unsafe("v1"))
  private val identities                = IdentitiesDummy(caller)
  private val asBob                     = addCredentials(OAuth2BearerToken("Bob"))

  private val indexingSource  = jsonContentOf("indexing-view-source.json")
  private val indexingSource2 = jsonContentOf("indexing-view-source-2.json")
  private val aggregateSource = jsonContentOf("aggregate-view-source.json")

  private val updatedIndexingSource = indexingSource.mapObject(_.add("resourceTag", Json.fromString("v1.5")))

  private val indexingViewId = nxv + "indexing-view"

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

  implicit val paginationConfig: PaginationConfig = pagination
  implicit private val f: FusionConfig            = fusionConfig

  private val selectQuery    = SparqlQuery("SELECT * {?s ?p ?o}")
  private val constructQuery = SparqlConstructQuery("CONSTRUCT {?s ?p ?o} WHERE {?s ?p ?o}").rightValue

  private lazy val views = BlazegraphViews(
    fetchContext,
    ResolverContextResolution(rcr),
    alwaysValidate,
    _ => UIO.unit,
    eventLogConfig,
    prefix,
    xas
  ).accepted

  private lazy val projections = Projections(xas, QueryConfig(10, RefreshStrategy.Stop), 1.hour)

  lazy val viewsQuery = new BlazegraphViewsQueryDummy(
    projectRef,
    new SparqlQueryClientDummy(),
    views,
    Map("resource-incoming-outgoing" -> linksResults)
  )

  private val aclCheck        = AclSimpleCheck().accepted
  private val groupDirectives = DeltaSchemeDirectives(fetchContext, _ => UIO.none, _ => UIO.none)
  private lazy val routes     =
    Route.seal(
      BlazegraphViewsRoutes(
        views,
        viewsQuery,
        identities,
        aclCheck,
        projections,
        groupDirectives,
        IndexingAction.noop
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
          "uuid"        -> uuid,
          "rev"         -> 1,
          "indexingRev" -> 1,
          "deprecated"  -> false
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

      Get("/v1/views/org/proj/indexing-view/statistics") ~> asBob ~> routes ~> check {
        response.status shouldEqual StatusCodes.OK
        response.asJson shouldEqual jsonContentOf(
          "routes/responses/statistics.json",
          "projectLatestInstant" -> Instant.EPOCH,
          "viewLatestInstant"    -> Instant.EPOCH
        )
      }
    }

    "fetch offset from view" in {
      Get("/v1/views/org/proj/indexing-view/offset") ~> asBob ~> routes ~> check {
        response.status shouldEqual StatusCodes.OK
        response.asJson shouldEqual jsonContentOf("routes/responses/offset.json")
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
          "uuid"        -> uuid,
          "rev"         -> 2,
          "indexingRev" -> 2,
          "deprecated"  -> false
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
          "uuid"        -> uuid,
          "rev"         -> 3,
          "indexingRev" -> 2,
          "deprecated"  -> false
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
          "uuid"        -> uuid,
          "rev"         -> 4,
          "indexingRev" -> 2,
          "deprecated"  -> true
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
          "uuid"        -> uuid,
          "deprecated"  -> true,
          "rev"         -> 4,
          "indexingRev" -> 2
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
            "uuid"        -> uuid,
            "deprecated"  -> false,
            "rev"         -> 1,
            "indexingRev" -> 1
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
      // Creating a new view, as indexing-view is deprecated and cannot be restarted
      Post("/v1/views/org/proj", indexingSource2.toEntity) ~> asBob ~> routes

      aclCheck.append(AclAddress.Root, Anonymous -> Set(permissions.write)).accepted
      projections.restarts(Offset.start).compile.toList.accepted.size shouldEqual 0
      Delete("/v1/views/org/proj/indexing-view-2/offset") ~> routes ~> check {
        response.status shouldEqual StatusCodes.OK
        response.asJson shouldEqual json"""{"@context": "${Vocabulary.contexts.offset}", "@type": "Start"}"""
        projections.restarts(Offset.start).compile.lastOrError.accepted shouldEqual SuccessElem(
          ProjectionRestart.entityType,
          ProjectionRestart.restartId(Offset.at(1L)),
          None,
          Instant.EPOCH,
          Offset.at(1L),
          ProjectionRestart(
            "blazegraph-org/proj-https://bluebrain.github.io/nexus/vocabulary/indexing-view-2-1",
            Instant.EPOCH,
            Anonymous
          ),
          1
        )
      }
    }

    "return no blazegraph projection failures without write permission" in {
      aclCheck.subtract(AclAddress.Root, Anonymous -> Set(permissions.write)).accepted

      Get("/v1/views/org/proj/indexing-view/failures") ~> routes ~> check {
        response.status shouldBe StatusCodes.Forbidden
        response.asJson shouldEqual jsonContentOf("/routes/errors/authorization-failed.json")
      }
    }

    "not return any failures if there aren't any" in {
      aclCheck.append(AclAddress.Root, Anonymous -> Set(permissions.write)).accepted

      Get("/v1/views/org/proj/indexing-view/failures") ~> routes ~> check {
        mediaType shouldBe MediaTypes.`text/event-stream`
        response.status shouldBe StatusCodes.OK
        chunksStream.asString(2).strip shouldBe ""
      }
    }

    "return all available failures when no LastEventID is provided" in {
      val metadata = ProjectionMetadata("testModule", "testName", Some(projectRef), Some(indexingViewId))
      val error    = new Exception("boom")
      val rev      = 1
      val fail1    =
        FailedElem(EntityType("ACL"), nxv + "myid", Some(projectRef), Instant.EPOCH, Offset.At(42L), error, rev)
      val fail2    = FailedElem(EntityType("Schema"), nxv + "myid", None, Instant.EPOCH, Offset.At(42L), error, rev)
      projections.saveFailedElems(metadata, List(fail1, fail2)).accepted

      Get("/v1/views/org/proj/indexing-view/failures") ~> routes ~> check {
        mediaType shouldBe MediaTypes.`text/event-stream`
        response.status shouldBe StatusCodes.OK
        chunksStream.asString(2).strip shouldEqual contentOf("/routes/sse/indexing-failures-1-2.txt")
      }
    }

    "return failures only from the given LastEventID" in {
      Get("/v1/views/org/proj/indexing-view/failures") ~> `Last-Event-ID`("1") ~> routes ~> check {
        mediaType shouldBe MediaTypes.`text/event-stream`
        response.status shouldBe StatusCodes.OK
        chunksStream.asString(3).strip shouldEqual contentOf("/routes/sse/indexing-failure-2.txt")
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
}
