package ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.routes

import akka.http.scaladsl.model.MediaTypes.`text/html`
import akka.http.scaladsl.model.headers.*
import akka.http.scaladsl.model.{HttpEntity, StatusCodes, Uri}
import akka.http.scaladsl.server.Route
import akka.util.ByteString
import cats.effect.IO
import ch.epfl.bluebrain.nexus.delta.kernel.RdfMediaTypes.*
import ch.epfl.bluebrain.nexus.delta.kernel.search.Pagination
import ch.epfl.bluebrain.nexus.delta.kernel.utils.{UUIDF, UrlUtils}
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.BlazegraphViews
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.client.{SparqlQueryClientDummy, SparqlResults}
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.model.BlazegraphViewRejection.ViewNotFound
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.model.*
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.query.IncomingOutgoingLinks
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.nxv
import ch.epfl.bluebrain.nexus.delta.rdf.query.SparqlQuery
import ch.epfl.bluebrain.nexus.delta.rdf.query.SparqlQuery.SparqlConstructQuery
import ch.epfl.bluebrain.nexus.delta.sdk.acls.model.AclAddress
import ch.epfl.bluebrain.nexus.delta.sdk.directives.DeltaSchemeDirectives
import ch.epfl.bluebrain.nexus.delta.sdk.fusion.FusionConfig
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.SearchResults
import ch.epfl.bluebrain.nexus.delta.sdk.model.{IdSegment, ResourceAccess}
import ch.epfl.bluebrain.nexus.delta.sdk.projects.FetchContextDummy
import ch.epfl.bluebrain.nexus.delta.sdk.resolvers.ResolverContextResolution
import ch.epfl.bluebrain.nexus.delta.sdk.resources.ResourceErrors.resourceAlreadyExistsError
import ch.epfl.bluebrain.nexus.delta.sdk.views.BlazegraphViewErrors.*
import ch.epfl.bluebrain.nexus.delta.sourcing.model.ProjectRef
import io.circe.Json
import io.circe.syntax.*
import org.scalatest.Assertion

class BlazegraphViewsRoutesSpec extends BlazegraphViewRoutesFixtures {

  private val prefix = "prefix"

  implicit private val uuidF: UUIDF = UUIDF.fixed(uuid)

  private val indexingSource  = jsonContentOf("indexing-view-source.json")
  private val aggregateSource = jsonContentOf("aggregate-view-source.json")

  private val updatedIndexingSource = indexingSource.mapObject(_.add("resourceTag", Json.fromString("v1.5")))

  private val indexingViewId  = nxv + "indexing-view"
  private val aggregateViewId = nxv + "aggregate-view"

  private val fetchContext = FetchContextDummy(Map(project.ref -> project.context), Set(deprecatedProject.ref))

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
    xas,
    clock
  ).accepted

  private val incomingOutgoingLinks = new IncomingOutgoingLinks {
    override def incoming(
        id: IdSegment,
        project: ProjectRef,
        pagination: Pagination.FromPagination
    ): IO[SearchResults[SparqlLink]] =
      if (project == projectRef) IO.pure(linksResults)
      else IO.raiseError(ViewNotFound(defaultViewId, project))

    override def outgoing(
        id: IdSegment,
        project: ProjectRef,
        pagination: Pagination.FromPagination,
        includeExternalLinks: Boolean
    ): IO[SearchResults[SparqlLink]] =
      if (project == projectRef) IO.pure(linksResults)
      else IO.raiseError(ViewNotFound(defaultViewId, project))
  }

  private lazy val viewsQuery = new BlazegraphViewsQueryDummy(new SparqlQueryClientDummy(), views)

  private val groupDirectives = DeltaSchemeDirectives(fetchContext)
  private lazy val routes     =
    Route.seal(
      BlazegraphViewsRoutesHandler(
        groupDirectives,
        BlazegraphViewsRoutes(
          views,
          viewsQuery,
          incomingOutgoingLinks,
          identities,
          aclCheck
        )
      )
    )

  override def beforeAll(): Unit = {
    super.beforeAll()
    aclCheck.append(AclAddress.Root, reader -> Set(permissions.read, permissions.query)).accepted
    aclCheck.append(AclAddress.Root, writer -> Set(permissions.write)).accepted
  }

  "Blazegraph view routes" should {
    "fail to create a view without permission" in {
      Post("/v1/views/org/proj", indexingSource.toEntity) ~> as(reader) ~> routes ~> check {
        response.shouldBeForbidden
      }
    }
    "create an indexing view" in {
      Post("/v1/views/org/proj", indexingSource.toEntity) ~> as(writer) ~> routes ~> check {
        response.status shouldEqual StatusCodes.Created
        response.asJson shouldEqual indexingViewMetadata(indexingViewId, 1, 1, deprecated = false)
      }
    }

    "create an aggregate view" in {
      Put("/v1/views/org/proj/aggregate-view", aggregateSource.toEntity) ~> as(writer) ~> routes ~> check {
        response.status shouldEqual StatusCodes.Created
        response.asJson shouldEqual jsonContentOf(
          "routes/responses/aggregate-view-metadata.json",
          "self" -> ResourceAccess("views", projectRef, aggregateViewId).uri
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
          req ~> routes ~> check {
            response.status shouldEqual StatusCodes.OK
            response.header[`Content-Type`].value.value shouldEqual mediaType.value
            response.asString shouldEqual expected
          }
        }
      }
    }

    "reject creation of a view which already exits" in {
      givenAView { view =>
        val viewPayload = indexingSource deepMerge json"""{"@id": "$view"}"""
        Put(s"/v1/views/org/proj/$view", viewPayload.toEntity) ~> as(writer) ~> routes ~> check {
          response.status shouldEqual StatusCodes.Conflict
          response.asJson shouldEqual resourceAlreadyExistsError(nxv + view, projectRef)
        }
      }
    }
    "fail to update a view without permission" in {
      Put("/v1/views/org/proj/aggregate-view?rev=1", aggregateSource.toEntity) ~> as(reader) ~> routes ~> check {
        response.shouldBeForbidden
      }
    }
    "update a view" in {
      Put("/v1/views/org/proj/indexing-view?rev=1", updatedIndexingSource.toEntity) ~> as(writer) ~> routes ~> check {
        response.status shouldEqual StatusCodes.OK
        response.asJson shouldEqual indexingViewMetadata(indexingViewId, 2, 2, deprecated = false)
      }
    }
    "reject update of a view at a non-existent revision" in {
      Put("/v1/views/org/proj/indexing-view?rev=3", updatedIndexingSource.toEntity) ~> as(writer) ~> routes ~> check {
        response.status shouldEqual StatusCodes.Conflict
        response.asJson shouldEqual jsonContentOf("routes/errors/incorrect-rev.json", "provided" -> 3, "expected" -> 2)

      }
    }

    "fail to deprecate a view without permission" in {
      Delete("/v1/views/org/proj/indexing-view?rev=2") ~> as(reader) ~> routes ~> check {
        response.shouldBeForbidden
      }
    }

    "reject a deprecation of a view without rev" in {
      Delete("/v1/views/org/proj/indexing-view") ~> as(writer) ~> routes ~> check {
        response.status shouldEqual StatusCodes.BadRequest
        response.asJson shouldEqual jsonContentOf("routes/errors/missing-query-param.json", "field" -> "rev")
      }
    }

    "deprecate a view" in {
      Delete("/v1/views/org/proj/indexing-view?rev=2") ~> as(writer) ~> routes ~> check {
        response.status shouldEqual StatusCodes.OK
        response.asJson shouldEqual indexingViewMetadata(indexingViewId, 3, 2, deprecated = true)
      }
    }

    "reject querying a deprecated view" in {
      givenADeprecatedView { view =>
        val queryEntity = HttpEntity(`application/sparql-query`, ByteString(selectQuery.value))
        Post(s"/v1/views/org/proj/$view/sparql", queryEntity) ~> routes ~> check {
          response.status shouldEqual StatusCodes.BadRequest
          response.asJson shouldEqual viewIsDeprecatedError(nxv + view)
        }
      }
    }

    "fail to undeprecate a view without permission" in {
      givenADeprecatedView { view =>
        Put(s"/v1/views/org/proj/$view/undeprecate?rev=2") ~> as(reader) ~> routes ~> check {
          response.shouldBeForbidden
        }
      }
    }

    "reject an undeprecation of a view without rev" in {
      givenADeprecatedView { view =>
        Put(s"/v1/views/org/proj/$view/undeprecate") ~> as(writer) ~> routes ~> check {
          response.status shouldEqual StatusCodes.BadRequest
          response.asJson shouldEqual jsonContentOf("routes/errors/missing-query-param.json", "field" -> "rev")
        }
      }
    }

    "reject undeprecation of a view if the view is not deprecated" in {
      givenAView { view =>
        Put(s"/v1/views/org/proj/$view/undeprecate?rev=1") ~> as(writer) ~> routes ~> check {
          response.status shouldEqual StatusCodes.BadRequest
          response.asJson shouldEqual viewIsNotDeprecatedError(nxv + view)
        }
      }
    }

    "undeprecate a view" in {
      givenADeprecatedView { view =>
        Put(s"/v1/views/org/proj/$view/undeprecate?rev=2") ~> as(writer) ~> routes ~> check {
          response.status shouldEqual StatusCodes.OK
          response.asJson shouldEqual indexingViewMetadata(nxv + view, 3, 1, deprecated = false)
        }
      }
    }

    "fail to fetch a view without permission" in {
      Get("/v1/views/org/proj/indexing-view") ~> routes ~> check {
        response.shouldBeForbidden
      }
    }

    "fetch a view" in {
      Get("/v1/views/org/proj/indexing-view") ~> as(reader) ~> routes ~> check {
        response.status shouldEqual StatusCodes.OK
        response.asJson shouldEqual indexingView(3, 2, deprecated = true)
      }
    }

    "fetch a view by rev" in {
      val endpoints = List(
        "/v1/views/org/proj/indexing-view?rev=1",
        "/v1/resources/org/proj/_/indexing-view?rev=1",
        "/v1/resources/org/proj/view/indexing-view?rev=1"
      )
      forAll(endpoints) { endpoint =>
        Get(endpoint) ~> as(reader) ~> routes ~> check {
          response.status shouldEqual StatusCodes.OK
          response.asJson shouldEqual indexingView(1, 1, deprecated = false).mapObject(_.remove("resourceTag"))
        }
      }
    }

    "reject fetching a view tag" in {
      val endpoints = List(
        "/v1/views/org/proj/indexing-view?tag=mytag",
        "/v1/resources/org/proj/_/indexing-view?tag=mytag",
        "/v1/resources/org/proj/view/indexing-view?tag=mytag"
      )
      forAll(endpoints) { endpoint =>
        Get(endpoint) ~> as(reader) ~> routes ~> check {
          response.status shouldEqual StatusCodes.BadRequest
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
        Get(endpoint) ~> as(reader) ~> routes ~> check {
          response.status shouldEqual StatusCodes.OK
          response.asJson shouldEqual updatedIndexingSource
        }
      }
    }

    "reject if provided rev and tag simultaneously" in {
      Get("/v1/views/org/proj/indexing-view?rev=1&tag=mytag") ~> as(reader) ~> routes ~> check {
        status shouldEqual StatusCodes.BadRequest
        response.asJson shouldEqual jsonContentOf("routes/errors/tag-and-rev-error.json")
      }
    }

    "fetch incoming links" in {
      forAll(
        List(
          Get("/v1/resources/org/proj/notimportant/resource-incoming-outgoing/incoming"),
          Get("/v1/files/org/proj/resource-incoming-outgoing/incoming")
        )
      ) { req =>
        req ~> as(reader) ~> routes ~> check {
          response.status shouldEqual StatusCodes.OK
          response.asJson shouldEqual jsonContentOf("routes/responses/incoming-outgoing.json")
        }
      }
    }

    "fetch outgoing links" in {
      forAll(
        List(
          Get("/v1/resources/org/proj/notimportant/resource-incoming-outgoing/outgoing"),
          Get("/v1/files/org/proj/resource-incoming-outgoing/outgoing")
        )
      ) { req =>
        req ~> as(reader) ~> routes ~> check {
          response.status shouldEqual StatusCodes.OK
          response.asJson shouldEqual jsonContentOf("routes/responses/incoming-outgoing.json")
        }
      }
    }

    "fail to fetch incoming or outgoing links without permission" in {
      forAll(
        List(
          Get("/v1/resources/org/proj/notimportant/resource-incoming-outgoing/incoming"),
          Get("/v1/files/org/proj/resource-incoming-outgoing/incoming"),
          Get("/v1/resources/org/proj/notimportant/resource-incoming-outgoing/outgoing"),
          Get("/v1/files/org/proj/resource-incoming-outgoing/outgoing")
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

    "reject if deprecating the default view" in {
      Delete("/v1/views/org/proj/nxv:defaultSparqlIndex?rev=1") ~> as(writer) ~> routes ~> check {
        status shouldEqual StatusCodes.Forbidden
        response.asJson shouldEqual
          json"""{
          "@context": "https://bluebrain.github.io/nexus/contexts/error.json",
          "@type": "ViewIsDefaultView",
          "reason": "Cannot perform write operations on the default Blazegraph view."
        }"""
      }
    }
  }

  private def givenAView(test: String => Assertion): Assertion = {
    val viewName       = genString()
    val viewDefPayload = indexingSource deepMerge json"""{"@id": "$viewName"}"""
    Post("/v1/views/org/proj", viewDefPayload.toEntity) ~> as(writer) ~> routes ~> check {
      response.status shouldEqual StatusCodes.Created
    }
    test(viewName)
  }

  private def givenADeprecatedView(test: String => Assertion): Assertion =
    givenAView { view =>
      Delete(s"/v1/views/org/proj/$view?rev=1") ~> as(writer) ~> routes ~> check {
        response.status shouldEqual StatusCodes.OK
      }
      test(view)
    }

  private def indexingViewMetadata(id: Iri, rev: Int, indexingRev: Int, deprecated: Boolean) =
    jsonContentOf(
      "routes/responses/indexing-view-metadata.json",
      "uuid"        -> uuid,
      "id"          -> id,
      "deprecated"  -> deprecated,
      "rev"         -> rev,
      "indexingRev" -> indexingRev,
      "self"        -> ResourceAccess("views", projectRef, id).uri
    )

  private def indexingView(rev: Int, indexingRev: Int, deprecated: Boolean) =
    jsonContentOf(
      "routes/responses/indexing-view.json",
      "uuid"        -> uuid,
      "deprecated"  -> deprecated,
      "rev"         -> rev,
      "indexingRev" -> indexingRev,
      "self"        -> ResourceAccess("views", projectRef, indexingViewId).uri
    )
}
