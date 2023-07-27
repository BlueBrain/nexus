package ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.routes

import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.server.Route
import ch.epfl.bluebrain.nexus.delta.kernel.utils.UrlUtils
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.model.contexts.{aggregations, searchMetadata}
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.model.{permissions => esPermissions, schema => elasticSearchSchema}
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.query.ElasticSearchQueryError
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.query.ElasticSearchQueryError.ProjectContextRejection
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.routes.DummyDefaultViewsQuery._
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.contexts.search
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.{contexts, nxv}
import ch.epfl.bluebrain.nexus.delta.sdk.acls.model.AclAddress
import ch.epfl.bluebrain.nexus.delta.sdk.directives.DeltaSchemeDirectives
import ch.epfl.bluebrain.nexus.delta.sdk.implicits._
import ch.epfl.bluebrain.nexus.delta.sdk.model._
import ch.epfl.bluebrain.nexus.delta.sdk.projects.{FetchContext, FetchContextDummy}
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.Anonymous
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Label
import io.circe.syntax._
import io.circe.{Json, JsonObject}

class ElasticSearchQueryRoutesSpec extends ElasticSearchViewsRoutesBaseSpec {

  private val myId2        = nxv + "myid2"
  private val myId2Encoded = UrlUtils.encode(myId2.toString)

  implicit private val fetchContextError: FetchContext[ElasticSearchQueryError] =
    FetchContextDummy[ElasticSearchQueryError](
      Map(project.value.ref -> project.value.context),
      ProjectContextRejection
    )

  private val resourceToSchemaMapping = ResourceToSchemaMappings(Label.unsafe("views") -> elasticSearchSchema.iri)

  private val groupDirectives =
    DeltaSchemeDirectives(
      fetchContextError,
      ioFromMap(uuid -> projectRef.organization),
      ioFromMap(uuid -> projectRef)
    )

  private lazy val defaultViewsQuery = new DummyDefaultViewsQuery

  private lazy val routes =
    Route.seal(
      ElasticSearchAllRoutes(
        groupDirectives,
        new ElasticSearchQueryRoutes(
          identities,
          aclCheck,
          resourceToSchemaMapping,
          groupDirectives,
          defaultViewsQuery
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
    ("aggregate at project level", "/v1/resources/myorg/myproject?aggregations=true"),
    ("aggregate at project level with schema", "/v1/resources/myorg/myproject/schema?aggregations=true"),
    ("aggregate at org level", "/v1/resources/myorg?aggregations=true"),
    ("aggregate at root level", "/v1/resources?aggregations=true")
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
