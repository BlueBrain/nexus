package ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.routes

import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.server.Route
import cats.effect.IO
import ch.epfl.bluebrain.nexus.delta.kernel.utils.UrlUtils
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.model.contexts.{aggregations, searchMetadata}
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.model.{permissions => esPermissions, schema => elasticSearchSchema}
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.routes.DummyDefaultIndexQuery._
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.contexts.search
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.{contexts, nxv}
import ch.epfl.bluebrain.nexus.delta.sdk.acls.model.AclAddress
import ch.epfl.bluebrain.nexus.delta.sdk.directives.DeltaSchemeDirectives
import ch.epfl.bluebrain.nexus.delta.sdk.identities.model.Caller
import ch.epfl.bluebrain.nexus.delta.sdk.implicits._
import ch.epfl.bluebrain.nexus.delta.sdk.model._
import ch.epfl.bluebrain.nexus.delta.sdk.permissions.model.Permission
import ch.epfl.bluebrain.nexus.delta.sdk.projects.{FetchContext, FetchContextDummy, ProjectScopeResolver}
import ch.epfl.bluebrain.nexus.delta.sourcing.Scope
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.Anonymous
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{Label, ProjectRef}
import io.circe.syntax._
import io.circe.{Json, JsonObject}

class ListingRoutesSpec extends ElasticSearchViewsRoutesFixtures {

  private val myId2        = nxv + "myid2"
  private val myId2Encoded = UrlUtils.encode(myId2.toString)

  implicit private val fetchContext: FetchContext = FetchContextDummy(Map(project.value.ref -> project.value.context))

  private val resourceToSchemaMapping = ResourceToSchemaMappings(Label.unsafe("views") -> elasticSearchSchema.iri)

  private val groupDirectives = DeltaSchemeDirectives(fetchContext)

  private def projectResolver: ProjectScopeResolver = new ProjectScopeResolver {
    override def apply(scope: Scope, permission: Permission)(implicit caller: Caller): IO[Set[ProjectRef]] =
      IO.pure { Set.empty }
  }

  private lazy val defaultIndexQuery = new DummyDefaultIndexQuery

  private lazy val routes =
    Route.seal(
      ElasticSearchViewsRoutesHandler(
        groupDirectives,
        new ListingRoutes(
          identities,
          aclCheck,
          projectResolver,
          resourceToSchemaMapping,
          groupDirectives,
          defaultIndexQuery
        ).routes
      )
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
    ("aggregate generic resources at project level", "/v1/resources/myorg/myproject?aggregations=true"),
    (
      "aggregate generic resources at project level with schema",
      "/v1/resources/myorg/myproject/schema?aggregations=true"
    ),
    ("aggregate generic resources at org level", "/v1/resources/myorg?aggregations=true"),
    ("aggregate generic resources at root level", "/v1/resources?aggregations=true"),
    ("aggregate views at root level", "/v1/views?aggregations=true"),
    ("aggregate views at org level", "/v1/views/myorg?aggregations=true"),
    ("aggregate views at project level", "/v1/views/myorg/myproject?aggregations=true")
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
