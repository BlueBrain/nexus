package ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.routes

import akka.actor.typed.ActorSystem
import akka.actor.typed.scaladsl.adapter._
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.server.{ExceptionHandler, RejectionHandler, Route}
import ch.epfl.bluebrain.nexus.delta.kernel.utils.UrlUtils
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.Fixtures
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.model.contexts.{aggregations, searchMetadata}
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.model.{permissions => esPermissions, schema => elasticSearchSchema}
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.query.ElasticSearchQueryError
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.query.ElasticSearchQueryError.ProjectContextRejection
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.routes.DummyDefaultViewsQuery._
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.contexts.search
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.{contexts, nxv}
import ch.epfl.bluebrain.nexus.delta.rdf.utils.JsonKeyOrdering
import ch.epfl.bluebrain.nexus.delta.sdk.ConfigFixtures
import ch.epfl.bluebrain.nexus.delta.sdk.acls.AclSimpleCheck
import ch.epfl.bluebrain.nexus.delta.sdk.acls.model.AclAddress
import ch.epfl.bluebrain.nexus.delta.sdk.circe.CirceMarshalling
import ch.epfl.bluebrain.nexus.delta.sdk.directives.DeltaSchemeDirectives
import ch.epfl.bluebrain.nexus.delta.sdk.generators.ProjectGen
import ch.epfl.bluebrain.nexus.delta.sdk.identities.IdentitiesDummy
import ch.epfl.bluebrain.nexus.delta.sdk.identities.model.Caller
import ch.epfl.bluebrain.nexus.delta.sdk.implicits._
import ch.epfl.bluebrain.nexus.delta.sdk.marshalling.{RdfExceptionHandler, RdfRejectionHandler}
import ch.epfl.bluebrain.nexus.delta.sdk.model._
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.PaginationConfig
import ch.epfl.bluebrain.nexus.delta.sdk.projects.model.ApiMappings
import ch.epfl.bluebrain.nexus.delta.sdk.projects.{FetchContext, FetchContextDummy}
import ch.epfl.bluebrain.nexus.delta.sdk.utils.RouteHelpers
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.{Anonymous, Authenticated, Group, User}
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Label
import ch.epfl.bluebrain.nexus.testkit._
import io.circe.syntax._
import io.circe.{Json, JsonObject}
import monix.execution.Scheduler
import org.scalatest.matchers.should.Matchers
import org.scalatest.{CancelAfterFailure, Inspectors, OptionValues}

import java.util.UUID

class ElasticSearchQueryRoutesSpec
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
    with TestHelpers
    with CirceMarshalling
    with Fixtures {

  implicit val typedSystem: ActorSystem[Nothing] = system.toTyped

  private val uuid = UUID.randomUUID()

  implicit private val ordering: JsonKeyOrdering =
    JsonKeyOrdering.default(topKeys =
      List("@context", "@id", "@type", "reason", "details", "sourceId", "projectionId", "_total", "_results")
    )

  implicit private val baseUri: BaseUri                   = BaseUri("http://localhost", Label.unsafe("v1"))
  implicit private val paginationConfig: PaginationConfig = PaginationConfig(5, 10, 5)
  implicit private val s: Scheduler                       = Scheduler.global
  implicit private val rejectionHandler: RejectionHandler = RdfRejectionHandler.apply
  implicit private val exceptionHandler: ExceptionHandler = RdfExceptionHandler.apply

  private val realm: Label = Label.unsafe("wonderland")
  private val alice: User  = User("alice", realm)

  private val caller = Caller(alice, Set(alice, Anonymous, Authenticated(realm), Group("group", realm)))

  private val identities = IdentitiesDummy(caller)

  private val project    = ProjectGen.resourceFor(
    ProjectGen.project(
      "myorg",
      "myproject",
      uuid = uuid,
      orgUuid = uuid,
      mappings = ApiMappings("view" -> elasticSearchSchema.iri)
    )
  )
  private val projectRef = project.value.ref

  private val myId2        = nxv + "myid2"
  private val myId2Encoded = UrlUtils.encode(myId2.toString)

  implicit private val fetchContextError: FetchContext[ElasticSearchQueryError] =
    FetchContextDummy[ElasticSearchQueryError](
      Map(project.value.ref -> project.value.context),
      ProjectContextRejection
    )

  private val resourceToSchemaMapping = ResourceToSchemaMappings(Label.unsafe("views") -> elasticSearchSchema.iri)

  private val aclCheck        = AclSimpleCheck().accepted
  private val groupDirectives =
    DeltaSchemeDirectives(
      fetchContextError,
      ioFromMap(uuid -> projectRef.organization),
      ioFromMap(uuid -> projectRef)
    )

  private lazy val defaultViewsQuery = new DummyDefaultViewsQuery

  private lazy val routes =
    Route.seal(
      new ElasticSearchQueryRoutes(
        identities,
        aclCheck,
        resourceToSchemaMapping,
        groupDirectives,
        defaultViewsQuery
      ).routes
    )

  "list at project level" in {
    aclCheck.append(AclAddress.Root, Anonymous -> Set(esPermissions.read)).accepted

    val endpoints: Seq[(String, IdSegment)] = List(
      "/v1/views/myorg/myproject"                    -> elasticSearchSchema,
      "/v1/resources/myorg/myproject/schema"         -> "schema",
      s"/v1/resources/myorg/myproject/$myId2Encoded" -> myId2
    )
    forAll(endpoints) { case (endpoint, _) =>
      Get(s"$endpoint?from=0&size=5&q=something") ~> routes ~> check {
        response.status shouldEqual StatusCodes.OK
        response.asJson shouldEqual
          JsonObject("_total" -> 1.asJson)
            .add("_results", Json.arr(listResponse.asJson))
            .addContext(contexts.metadata)
            .addContext(search)
            .addContext(searchMetadata)
            .asJson
      }
    }
  }

  "list at org level" in {
    Get(s"/v1/views/myorg?from=0&size=5&q=something") ~> routes ~> check {
      response.status shouldEqual StatusCodes.OK
      response.asJson shouldEqual
        JsonObject("_total" -> 1.asJson)
          .add("_results", Json.arr(listResponse.asJson))
          .addContext(contexts.metadata)
          .addContext(search)
          .addContext(searchMetadata)
          .asJson
    }

    Get(s"/v1/resources/myorg?from=0&size=5&q=something") ~> routes ~> check {
      response.status shouldEqual StatusCodes.OK
      response.asJson shouldEqual
        JsonObject("_total" -> 1.asJson)
          .add("_results", Json.arr(listResponse.asJson))
          .addContext(contexts.metadata)
          .addContext(search)
          .addContext(searchMetadata)
          .asJson
    }
  }

  "list at root level" in {
    Get(s"/v1/views?from=0&size=5&q=something") ~> routes ~> check {
      response.status shouldEqual StatusCodes.OK
      response.asJson shouldEqual
        JsonObject("_total" -> 1.asJson)
          .add("_results", Json.arr(listResponse.asJson))
          .addContext(contexts.metadata)
          .addContext(search)
          .addContext(searchMetadata)
          .asJson
    }

    Get(s"/v1/resources?from=0&size=5&q=something") ~> routes ~> check {
      response.status shouldEqual StatusCodes.OK
      response.asJson shouldEqual
        JsonObject("_total" -> 1.asJson)
          .add("_results", Json.arr(listResponse.asJson))
          .addContext(contexts.metadata)
          .addContext(search)
          .addContext(searchMetadata)
          .asJson
    }
  }

  List(
    ("aggregate at project level", "/v1/aggregations/myorg/myproject"),
    ("aggregate at org level", "/v1/aggregations/myorg"),
    ("aggregate at root level", "/v1/aggregations")
  ).foreach { case (testName, path) =>
    testName in {
      Get(path) ~> routes ~> check {
        response.status shouldEqual StatusCodes.OK
        response.asJson shouldEqual
          JsonObject("total" -> 1.asJson)
            .add("aggregations", aggregationResponse.asJson)
            .addContext(aggregations)
            .asJson
      }
    }
  }

}
