package ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.routes

import akka.http.scaladsl.model.MediaTypes.`text/html`
import akka.http.scaladsl.model.headers._
import akka.http.scaladsl.model.{HttpEntity, StatusCodes, Uri}
import akka.http.scaladsl.server.Route
import akka.util.ByteString
import cats.effect.IO
import ch.epfl.bluebrain.nexus.delta.kernel.utils.{UUIDF, UrlUtils}
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.BlazegraphViews
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.client.{SparqlQueryClientDummy, SparqlResults}
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.model.BlazegraphViewRejection.ProjectContextRejection
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.model._
import ch.epfl.bluebrain.nexus.delta.rdf.RdfMediaTypes._
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.nxv
import ch.epfl.bluebrain.nexus.delta.rdf.query.SparqlQuery
import ch.epfl.bluebrain.nexus.delta.rdf.query.SparqlQuery.SparqlConstructQuery
import ch.epfl.bluebrain.nexus.delta.sdk.IndexingAction
import ch.epfl.bluebrain.nexus.delta.sdk.acls.model.AclAddress
import ch.epfl.bluebrain.nexus.delta.sdk.directives.DeltaSchemeDirectives
import ch.epfl.bluebrain.nexus.delta.sdk.fusion.FusionConfig
import ch.epfl.bluebrain.nexus.delta.sdk.implicits._
import ch.epfl.bluebrain.nexus.delta.sdk.model.ResourceUris
import ch.epfl.bluebrain.nexus.delta.sdk.permissions.Permissions.events
import ch.epfl.bluebrain.nexus.delta.sdk.projects.FetchContextDummy
import ch.epfl.bluebrain.nexus.delta.sdk.resolvers.ResolverContextResolution
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.Anonymous
import io.circe.Json
import io.circe.syntax._

class BlazegraphViewsRoutesSpec extends BlazegraphViewRoutesFixtures {

  private val prefix = "prefix"

  implicit private val uuidF: UUIDF = UUIDF.fixed(uuid)

  private val indexingSource  = jsonContentOf("indexing-view-source.json")
  private val aggregateSource = jsonContentOf("aggregate-view-source.json")

  private val updatedIndexingSource = indexingSource.mapObject(_.add("resourceTag", Json.fromString("v1.5")))

  private val indexingViewId  = nxv + "indexing-view"
  private val aggregateViewId = nxv + "aggregate-view"

  private val fetchContext = FetchContextDummy[BlazegraphViewRejection](
    Map(project.ref -> project.context),
    Set(deprecatedProject.ref),
    ProjectContextRejection
  )

  implicit private val f: FusionConfig = fusionConfig

  private val selectQuery    = SparqlQuery("SELECT * {?s ?p ?o}")
  private val constructQuery = SparqlConstructQuery("CONSTRUCT {?s ?p ?o} WHERE {?s ?p ?o}").rightValue

  private lazy val views = BlazegraphViews(
    fetchContext,
    ResolverContextResolution(rcr),
    alwaysValidate,
    _ => IO.unit,
    eventLogConfig,
    prefix,
    xas
  ).accepted

  lazy val viewsQuery = new BlazegraphViewsQueryDummy(
    projectRef,
    new SparqlQueryClientDummy(),
    views,
    Map("resource-incoming-outgoing" -> linksResults)
  )

  private val groupDirectives = DeltaSchemeDirectives(fetchContext, _ => IO.none, _ => IO.none)
  private lazy val routes     =
    Route.seal(
      BlazegraphViewsRoutesHandler(
        groupDirectives,
        BlazegraphViewsRoutes(
          views,
          viewsQuery,
          identities,
          aclCheck,
          groupDirectives,
          IndexingAction.noop
        )
      )
    )

  "Blazegraph view routes" should {
    "fail to create a view without permission" in {
      aclCheck.append(AclAddress.Root, Anonymous -> Set(events.read)).accepted
      Post("/v1/views/org/proj", indexingSource.toEntity) ~> routes ~> check {
        response.shouldBeForbidden
      }
    }
    "create an indexing view" in {
      aclCheck
        .append(AclAddress.Root, caller.subject -> Set(permissions.write, permissions.read))
        .accepted
      Post("/v1/views/org/proj", indexingSource.toEntity) ~> asBob ~> routes ~> check {
        response.status shouldEqual StatusCodes.Created
        response.asJson shouldEqual indexingViewMetadata(1, 1, false)
      }
    }

    "create an aggregate view" in {
      Put("/v1/views/org/proj/aggregate-view", aggregateSource.toEntity) ~> asBob ~> routes ~> check {
        response.status shouldEqual StatusCodes.Created
        response.asJson shouldEqual jsonContentOf(
          "routes/responses/aggregate-view-metadata.json",
          "self" -> ResourceUris("views", projectRef, aggregateViewId).accessUri
        )
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
        response.shouldBeForbidden
      }
    }
    "update a view" in {
      Put("/v1/views/org/proj/indexing-view?rev=1", updatedIndexingSource.toEntity) ~> asBob ~> routes ~> check {
        response.status shouldEqual StatusCodes.OK
        response.asJson shouldEqual indexingViewMetadata(2, 2, false)
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
        response.asJson shouldEqual indexingViewMetadata(3, 2, false)
      }
    }

    "fail to deprecate a view without permission" in {
      Delete("/v1/views/org/proj/indexing-view?rev=3") ~> routes ~> check {
        response.shouldBeForbidden
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
        response.asJson shouldEqual indexingViewMetadata(4, 2, true)
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
        response.shouldBeForbidden
      }
    }
    "fetch a view" in {
      Get("/v1/views/org/proj/indexing-view") ~> asBob ~> routes ~> check {
        response.status shouldEqual StatusCodes.OK
        response.asJson shouldEqual indexingView(4, 2, true)
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
          response.asJson shouldEqual indexingView(1, 1, false).mapObject(_.remove("resourceTag"))
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
          response.shouldBeForbidden
        }
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

  private def indexingViewMetadata(rev: Int, indexingRev: Int, deprecated: Boolean) =
    jsonContentOf(
      "routes/responses/indexing-view-metadata.json",
      "uuid"        -> uuid,
      "deprecated"  -> deprecated,
      "rev"         -> rev,
      "indexingRev" -> indexingRev,
      "self"        -> ResourceUris("views", projectRef, indexingViewId).accessUri
    )

  private def indexingView(rev: Int, indexingRev: Int, deprecated: Boolean) =
    jsonContentOf(
      "routes/responses/indexing-view.json",
      "uuid"        -> uuid,
      "deprecated"  -> deprecated,
      "rev"         -> rev,
      "indexingRev" -> indexingRev,
      "self"        -> ResourceUris("views", projectRef, indexingViewId).accessUri
    )
}
