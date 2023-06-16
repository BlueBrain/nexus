package ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.routes

import akka.actor.typed.ActorSystem
import akka.http.scaladsl.model.MediaTypes.`text/html`
import akka.http.scaladsl.model.headers.{`Content-Type`, Accept, Location, OAuth2BearerToken}
import akka.http.scaladsl.model.{HttpEntity, StatusCodes, Uri}
import akka.http.scaladsl.server.{ExceptionHandler, RejectionHandler, Route}
import akka.util.ByteString
import ch.epfl.bluebrain.nexus.delta.kernel.utils.UrlUtils
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.client.SparqlQueryClientDummy
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.model.CompositeRestart.{FullRebuild, FullRestart, PartialRebuild}
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.model.CompositeViewRejection.ProjectContextRejection
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.model.{permissions, CompositeViewRejection}
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.projections.{CompositeIndexingDetails, CompositeProjections}
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.store.CompositeRestartStore
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.stream.CompositeBranch.Run.Main
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.stream.{CompositeBranch, CompositeProgress}
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.{CompositeViews, CompositeViewsFixture, Fixtures}
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.BNode
import ch.epfl.bluebrain.nexus.delta.rdf.RdfMediaTypes.`application/sparql-query`
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.nxv
import ch.epfl.bluebrain.nexus.delta.rdf.graph.NTriples
import ch.epfl.bluebrain.nexus.delta.rdf.query.SparqlQuery
import ch.epfl.bluebrain.nexus.delta.rdf.utils.JsonKeyOrdering
import ch.epfl.bluebrain.nexus.delta.rdf.{RdfMediaTypes, Vocabulary}
import ch.epfl.bluebrain.nexus.delta.sdk.acls.AclSimpleCheck
import ch.epfl.bluebrain.nexus.delta.sdk.acls.model.AclAddress
import ch.epfl.bluebrain.nexus.delta.sdk.circe.CirceMarshalling
import ch.epfl.bluebrain.nexus.delta.sdk.directives.DeltaSchemeDirectives
import ch.epfl.bluebrain.nexus.delta.sdk.fusion.FusionConfig
import ch.epfl.bluebrain.nexus.delta.sdk.identities.IdentitiesDummy
import ch.epfl.bluebrain.nexus.delta.sdk.identities.model.Caller
import ch.epfl.bluebrain.nexus.delta.sdk.implicits._
import ch.epfl.bluebrain.nexus.delta.sdk.marshalling.{RdfExceptionHandler, RdfRejectionHandler}
import ch.epfl.bluebrain.nexus.delta.sdk.model.{BaseUri, IdSegment}
import ch.epfl.bluebrain.nexus.delta.sdk.permissions.Permissions.events
import ch.epfl.bluebrain.nexus.delta.sdk.permissions.model.Permission
import ch.epfl.bluebrain.nexus.delta.sdk.projects.FetchContextDummy
import ch.epfl.bluebrain.nexus.delta.sdk.resolvers.ResolverContextResolution
import ch.epfl.bluebrain.nexus.delta.sdk.utils.RouteHelpers
import ch.epfl.bluebrain.nexus.delta.sdk.views.ViewRef
import ch.epfl.bluebrain.nexus.delta.sourcing.config.{BatchConfig, QueryConfig}
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.{Anonymous, Authenticated, Group, User}
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Label
import ch.epfl.bluebrain.nexus.delta.sourcing.offset.Offset
import ch.epfl.bluebrain.nexus.delta.sourcing.query.RefreshStrategy
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.{ProjectionProgress, RemainingElems}
import ch.epfl.bluebrain.nexus.testkit._
import io.circe.syntax._
import monix.bio.UIO
import org.scalatest.matchers.should.Matchers
import org.scalatest.{CancelAfterFailure, Inspectors, OptionValues}

import java.time.Instant
import scala.concurrent.duration._

class CompositeViewsRoutesSpec
    extends RouteHelpers
    with DoobieScalaTestFixture
    with Matchers
    with CirceLiteral
    with CirceEq
    with IOFixedClock
    with OptionValues
    with TestMatchers
    with Inspectors
    with CancelAfterFailure
    with CompositeViewsFixture
    with Fixtures {
  import akka.actor.typed.scaladsl.adapter._
  implicit private val typedSystem: ActorSystem[Nothing] = system.toTyped

  implicit val ordering: JsonKeyOrdering =
    JsonKeyOrdering.default(topKeys =
      List("@context", "@id", "@type", "reason", "details", "sourceId", "projectionId", "_total", "_results")
    )

  implicit val baseUri: BaseUri                   = BaseUri("http://localhost", Label.unsafe("v1"))
  implicit val rejectionHandler: RejectionHandler = RdfRejectionHandler.apply
  implicit val exceptionHandler: ExceptionHandler = RdfExceptionHandler.apply
  implicit private val f: FusionConfig            = fusionConfig

  val realm                   = Label.unsafe("myrealm")
  val bob                     = User("Bob", realm)
  implicit val caller: Caller = Caller(bob, Set(bob, Group("mygroup", realm), Authenticated(realm)))
  private val identities      = IdentitiesDummy(caller)
  private val asBob           = addCredentials(OAuth2BearerToken("Bob"))

  val undefinedPermission = Permission.unsafe("not/defined")
  val allowedPerms        = Set(
    permissions.read,
    permissions.write,
    events.read
  )

  private val aclCheck = AclSimpleCheck().accepted

  private val now      = Instant.now()
  private val nowPlus5 = now.plusSeconds(5)

  private val viewId                = iri"https://bluebrain.github.io/nexus/vocabulary/$uuid"
  private val projectSourceId       = iri"http://example.com/project-source"
  private val crossProjectSourceId  = iri"http://example.com/cross-project-source"
  private val remoteProjectSourceId = iri"http://example.com/remote-project-source"
  private val esId                  = iri"http://example.com/es-projection"
  private val blazeId               = iri"http://example.com/blazegraph-projection"

  private val selectQuery = SparqlQuery("SELECT * WHERE {?s ?p ?o}")
  private val esQuery     = jobj"""{"query": {"match_all": {} } }"""
  private val esResult    = json"""{"k": "v"}"""

  private val responseCommonNs         = NTriples("queryCommonNs", BNode.random)
  private val responseQueryProjection  = NTriples("queryProjection", BNode.random)
  private val responseQueryProjections = NTriples("queryProjections", BNode.random)

  private val fetchContext    = FetchContextDummy[CompositeViewRejection](List(project), ProjectContextRejection)
  private val groupDirectives = DeltaSchemeDirectives(fetchContext, _ => UIO.none, _ => UIO.none)

  private lazy val views: CompositeViews = CompositeViews(
    fetchContext,
    ResolverContextResolution(rcr),
    alwaysValidate,
    crypto,
    config,
    xas
  ).accepted

  private lazy val blazegraphQuery = new BlazegraphQueryDummy(
    new SparqlQueryClientDummy(
      sparqlNTriples = {
        case seq if seq.toSet == Set("queryCommonNs")    => responseCommonNs
        case seq if seq.toSet == Set("queryProjection")  => responseQueryProjection
        case seq if seq.toSet == Set("queryProjections") => responseQueryProjections
        case _                                           => NTriples.empty
      }
    ),
    views
  )

  private lazy val elasticSearchQuery =
    new ElasticSearchQueryDummy(Map((esId: IdSegment, esQuery) -> esResult), Map(esQuery -> esResult), views)

  private lazy val restartStore       = new CompositeRestartStore(xas)
  private lazy val projections        =
    CompositeProjections(
      restartStore,
      xas,
      QueryConfig(5, RefreshStrategy.Stop),
      BatchConfig(5, 100.millis),
      3.seconds
    )

  private def lastRestart = restartStore.last(ViewRef(project.ref, viewId)).map(_.flatMap(_.toOption)).accepted

  private val details: CompositeIndexingDetails = new CompositeIndexingDetails(
    (_, _, _) =>
      UIO.pure(
        CompositeProgress(
          Map(
            CompositeBranch(projectSourceId, esId, Main)          ->
              ProjectionProgress(Offset.at(3L), now, 6, 1, 1),
            CompositeBranch(projectSourceId, blazeId, Main)       ->
              ProjectionProgress(Offset.at(3L), now, 6, 1, 1),
            CompositeBranch(crossProjectSourceId, esId, Main)     ->
              ProjectionProgress(Offset.at(6L), now, 3, 2, 0),
            CompositeBranch(crossProjectSourceId, blazeId, Main)  ->
              ProjectionProgress(Offset.at(6L), now, 3, 2, 0),
            CompositeBranch(remoteProjectSourceId, esId, Main)    ->
              ProjectionProgress(Offset.at(7L), now, 1, 1, 0),
            CompositeBranch(remoteProjectSourceId, blazeId, Main) ->
              ProjectionProgress(Offset.at(7L), now, 1, 1, 0)
          )
        )
      ),
    (_, _, _) => UIO.some(RemainingElems(10, nowPlus5))
  )

  private lazy val routes =
    Route.seal(
      CompositeViewsRoutes(
        identities,
        aclCheck,
        views,
        details,
        projections,
        blazegraphQuery,
        elasticSearchQuery,
        groupDirectives
      )
    )

  val viewSource        = jsonContentOf("composite-view-source.json")
  val viewSourceUpdated = jsonContentOf("composite-view-source-updated.json")

  "Composite views routes" should {
    "fail to create a view without permission" in {
      aclCheck.append(AclAddress.Root, Anonymous -> Set(events.read)).accepted
      Post("/v1/views/myorg/myproj", viewSource.toEntity) ~> routes ~> check {
        response.status shouldEqual StatusCodes.Forbidden
        response.asJson shouldEqual jsonContentOf("routes/errors/authorization-failed.json")
      }
    }

    "create a view" in {
      aclCheck.append(AclAddress.Root, caller.subject -> Set(permissions.write, permissions.read)).accepted
      Post("/v1/views/myorg/myproj", viewSource.toEntity) ~> asBob ~> routes ~> check {
        response.status shouldEqual StatusCodes.Created
        response.asJson shouldEqual jsonContentOf(
          "routes/responses/view-metadata.json",
          "uuid"       -> uuid,
          "rev"        -> 1,
          "deprecated" -> false
        )
      }
    }

    "reject creation of a view which already exists" in {
      Put(s"/v1/views/myorg/myproj/$uuid", viewSource.toEntity) ~> asBob ~> routes ~> check {
        response.status shouldEqual StatusCodes.Conflict
        response.asJson shouldEqual jsonContentOf("routes/errors/view-already-exists.json", "uuid" -> uuid)
      }
    }

    "fail to update a view without permission" in {
      Put(s"/v1/views/myorg/myproj/$uuid?rev=1", viewSource.toEntity) ~> routes ~> check {
        response.status shouldEqual StatusCodes.Forbidden
        response.asJson shouldEqual jsonContentOf("routes/errors/authorization-failed.json")
      }
    }

    "update a view" in {
      Put(s"/v1/views/myorg/myproj/$uuid?rev=1", viewSourceUpdated.toEntity) ~> asBob ~> routes ~> check {
        response.status shouldEqual StatusCodes.OK
        response.asJson shouldEqual jsonContentOf(
          "routes/responses/view-metadata.json",
          "uuid"       -> uuid,
          "rev"        -> 2,
          "deprecated" -> false
        )
      }
    }

    "reject update of a view at a non-existent revision" in {
      Put(s"/v1/views/myorg/myproj/$uuid?rev=3", viewSourceUpdated.toEntity) ~> asBob ~> routes ~> check {
        response.status shouldEqual StatusCodes.Conflict
        response.asJson shouldEqual jsonContentOf("routes/errors/incorrect-rev.json", "provided" -> 3, "expected" -> 2)
      }
    }

    "tag a view" in {
      val payload = json"""{"tag": "mytag", "rev": 1}"""
      Post(s"/v1/views/myorg/myproj/$uuid/tags?rev=2", payload.toEntity) ~> asBob ~> routes ~> check {
        status shouldEqual StatusCodes.Created
        response.asJson shouldEqual jsonContentOf(
          "routes/responses/view-metadata.json",
          "uuid"       -> uuid,
          "rev"        -> 3,
          "deprecated" -> false
        )
      }
    }

    "fail to fetch a view without permission" in {
      Get(s"/v1/views/myorg/myproj/$uuid") ~> routes ~> check {
        response.status shouldEqual StatusCodes.Forbidden
        response.asJson shouldEqual jsonContentOf("routes/errors/authorization-failed.json")
      }
    }

    "fetch a view" in {
      Get(s"/v1/views/myorg/myproj/$uuid") ~> asBob ~> routes ~> check {
        response.status shouldEqual StatusCodes.OK
        response.asJson should equalIgnoreArrayOrder(
          jsonContentOf(
            "routes/responses/view.json",
            "uuid"            -> uuid,
            "deprecated"      -> false,
            "rev"             -> 3,
            "rebuildInterval" -> "2 minutes"
          )
        )
      }
    }

    "fetch a view by rev or tag" in {
      val endpoints = List(
        s"/v1/views/myorg/myproj/$uuid?tag=mytag",
        s"/v1/resources/myorg/myproj/_/$uuid?tag=mytag",
        s"/v1/views/myorg/myproj/$uuid?rev=1",
        s"/v1/resources/myorg/myproj/_/$uuid?rev=1"
      )
      forAll(endpoints) { endpoint =>
        Get(endpoint) ~> asBob ~> routes ~> check {
          response.status shouldEqual StatusCodes.OK
          response.asJson should equalIgnoreArrayOrder(
            jsonContentOf(
              "routes/responses/view.json",
              "uuid"            -> uuid,
              "deprecated"      -> false,
              "rev"             -> 1,
              "rebuildInterval" -> "1 minute"
            ).mapObject(_.remove("resourceTag"))
          )
        }
      }
    }

    "fetch a view source" in {
      val endpoints = List(s"/v1/views/myorg/myproj/$uuid/source", s"/v1/resources/myorg/myproj/_/$uuid/source")
      forAll(endpoints) { endpoint =>
        Get(endpoint) ~> asBob ~> routes ~> check {
          response.status shouldEqual StatusCodes.OK
          response.asJson shouldEqual viewSourceUpdated.removeAllKeys("token")
        }
      }
    }

    "fetch the view tags" in {
      val endpoints = List(s"/v1/views/myorg/myproj/$uuid/tags", s"/v1/resources/myorg/myproj/_/$uuid/tags")
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
      Get(s"/v1/views/myorg/myproj/$uuid?rev=1&tag=mytag") ~> asBob ~> routes ~> check {
        status shouldEqual StatusCodes.BadRequest
        response.asJson shouldEqual jsonContentOf("routes/errors/tag-and-rev-error.json")
      }
    }

    "fail to fetch/delete offset without permission" in {
      val encodedId = UrlUtils.encode(blazeId.toString)
      val endpoints = List(
        s"/v1/views/myorg/myproj/$uuid/offset",
        s"/v1/views/myorg/myproj/$uuid/projections/_/offset",
        s"/v1/views/myorg/myproj/$uuid/projections/$encodedId/offset"
      )
      forAll(endpoints) { endpoint =>
        forAll(List(Get(endpoint), Delete(endpoint))) { req =>
          req ~> routes ~> check {
            response.status shouldEqual StatusCodes.Forbidden
            response.asJson shouldEqual jsonContentOf("routes/errors/authorization-failed.json")
          }
        }
      }

      lastRestart shouldEqual None
    }

    "fetch offsets" in {
      val encodedId         = UrlUtils.encode(blazeId.toString)
      val viewOffsets       = jsonContentOf("routes/responses/view-offsets.json")
      val projectionOffsets = jsonContentOf("routes/responses/view-offsets-projection.json")
      val endpoints         = List(
        s"/v1/views/myorg/myproj/$uuid/offset"                        -> viewOffsets,
        s"/v1/views/myorg/myproj/$uuid/projections/_/offset"          -> viewOffsets,
        s"/v1/views/myorg/myproj/$uuid/projections/$encodedId/offset" -> projectionOffsets
      )
      forAll(endpoints) { case (endpoint, expected) =>
        Get(endpoint) ~> asBob ~> routes ~> check {
          response.status shouldEqual StatusCodes.OK
          response.asJson shouldEqual expected
        }
      }
    }

    "fetch statistics" in {
      val encodedProjection = UrlUtils.encode(blazeId.toString)
      val encodedSource     = UrlUtils.encode("http://example.com/cross-project-source")
      val viewStats         = jsonContentOf(
        "routes/responses/view-statistics.json",
        "last"                  -> nowPlus5,
        "instant_elasticsearch" -> now,
        "instant_blazegraph"    -> now
      )
      val projectionStats   = jsonContentOf(
        "routes/responses/view-statistics-projection.json",
        "last"    -> nowPlus5,
        "instant" -> now
      )
      val sourceStats       = jsonContentOf(
        "routes/responses/view-statistics-source.json",
        "last"                  -> nowPlus5,
        "instant_elasticsearch" -> now,
        "instant_blazegraph"    -> now
      )
      val endpoints         = List(
        s"/v1/views/myorg/myproj/$uuid/statistics"                                -> viewStats,
        s"/v1/views/myorg/myproj/$uuid/projections/_/statistics"                  -> viewStats,
        s"/v1/views/myorg/myproj/$uuid/projections/$encodedProjection/statistics" -> projectionStats,
        s"/v1/views/myorg/myproj/$uuid/sources/$encodedSource/statistics"         -> sourceStats
      )
      forAll(endpoints) { case (endpoint, expected) =>
        Get(endpoint) ~> asBob ~> routes ~> check {
          response.status shouldEqual StatusCodes.OK
          response.asJson shouldEqual expected
        }
      }
    }

    "delete offsets" in {
      val encodedId         = UrlUtils.encode(blazeId.toString)
      val viewOffsets       =
        jsonContentOf("routes/responses/view-offsets.json").replaceKeyWithValue("offset", Offset.start.asJson)
      val projectionOffsets =
        jsonContentOf("routes/responses/view-offsets-projection.json").replaceKeyWithValue(
          "offset",
          Offset.start.asJson
        )

      Delete(s"/v1/views/myorg/myproj/$uuid/offset") ~> asBob ~> routes ~> check {
        response.status shouldEqual StatusCodes.OK
        response.asJson shouldEqual viewOffsets
        lastRestart.value shouldEqual FullRestart(project.ref, viewId, Instant.EPOCH, bob)
      }

      val endpoints = List(
        (
          s"/v1/views/myorg/myproj/$uuid/projections/_/offset",
          viewOffsets,
          FullRebuild(project.ref, viewId, Instant.EPOCH, bob)
        ),
        (
          s"/v1/views/myorg/myproj/$uuid/projections/$encodedId/offset",
          projectionOffsets,
          PartialRebuild(project.ref, viewId, blazeId, Instant.EPOCH, bob)
        )
      )
      forAll(endpoints) { case (endpoint, expectedResult, restart) =>
        Delete(endpoint) ~> asBob ~> routes ~> check {
          response.status shouldEqual StatusCodes.OK
          response.asJson shouldEqual expectedResult
          lastRestart.value shouldEqual restart
        }
      }
    }

    "query blazegraph common namespace and projection(s)" in {
      val encodedId = UrlUtils.encode(blazeId.toString)
      val mediaType = RdfMediaTypes.`application/n-triples`

      val queryEntity = HttpEntity(`application/sparql-query`, ByteString(selectQuery.value))
      val accept      = Accept(mediaType)
      val list        = List(
        s"/v1/views/myorg/myproj/$uuid/sparql"                        -> responseCommonNs.value,
        s"/v1/views/myorg/myproj/$uuid/projections/_/sparql"          -> responseQueryProjections.value,
        s"/v1/views/myorg/myproj/$uuid/projections/$encodedId/sparql" -> responseQueryProjection.value
      )

      forAll(list) { case (endpoint, expected) =>
        val postRequest = Post(endpoint, queryEntity).withHeaders(accept)
        val getRequest  = Get(s"$endpoint?query=${UrlUtils.encode(selectQuery.value)}").withHeaders(accept)
        forAll(List(postRequest, getRequest)) { req =>
          req ~> asBob ~> routes ~> check {
            response.status shouldEqual StatusCodes.OK
            response.header[`Content-Type`].value.value shouldEqual mediaType.value
            response.asString shouldEqual expected
          }
        }
      }
    }

    "query elasticsearch projection(s)" in {
      val encodedId = UrlUtils.encode(esId.toString)

      val endpoints = List(
        s"/v1/views/myorg/myproj/$uuid/projections/_/_search",
        s"/v1/views/myorg/myproj/$uuid/projections/$encodedId/_search"
      )

      forAll(endpoints) { endpoint =>
        Post(endpoint, esQuery.asJson)(CirceMarshalling.jsonMarshaller, sc) ~> asBob ~> routes ~> check {
          response.status shouldEqual StatusCodes.OK
          response.asJson shouldEqual esResult
        }
      }
    }

    "fail to deprecate a view without permission" in {
      Delete(s"/v1/views/myorg/myproj/$uuid?rev=3") ~> routes ~> check {
        response.status shouldEqual StatusCodes.Forbidden
        response.asJson shouldEqual jsonContentOf("routes/errors/authorization-failed.json")
      }
    }

    "reject a deprecation of a view without rev" in {
      Delete(s"/v1/views/myorg/myproj/$uuid") ~> asBob ~> routes ~> check {
        response.status shouldEqual StatusCodes.BadRequest
        response.asJson shouldEqual jsonContentOf("routes/errors/missing-query-param.json", "field" -> "rev")
      }
    }

    "deprecate a view" in {
      Delete(s"/v1/views/myorg/myproj/$uuid?rev=3") ~> asBob ~> routes ~> check {
        response.status shouldEqual StatusCodes.OK
        response.asJson shouldEqual jsonContentOf(
          "routes/responses/view-metadata.json",
          "uuid"       -> uuid,
          "rev"        -> 4,
          "deprecated" -> true
        )
      }
    }

    "reject querying blazegraph common namespace and projection(s) for a deprecated view" in {
      val encodedId = UrlUtils.encode(blazeId.toString)
      val mediaType = RdfMediaTypes.`application/n-triples`

      val queryEntity = HttpEntity(`application/sparql-query`, ByteString(selectQuery.value))
      val accept      = Accept(mediaType)
      val list        = List(
        s"/v1/views/myorg/myproj/$uuid/sparql",
        s"/v1/views/myorg/myproj/$uuid/projections/_/sparql",
        s"/v1/views/myorg/myproj/$uuid/projections/$encodedId/sparql"
      )

      forAll(list) { endpoint =>
        val postRequest = Post(endpoint, queryEntity).withHeaders(accept)
        val getRequest  = Get(s"$endpoint?query=${UrlUtils.encode(selectQuery.value)}").withHeaders(accept)
        forAll(List(postRequest, getRequest)) { req =>
          req ~> asBob ~> routes ~> check {
            response.status shouldEqual StatusCodes.BadRequest
            response.asJson shouldEqual jsonContentOf(
              "routes/errors/view-deprecated.json",
              "id" -> nxv.base / uuid.toString
            )
          }
        }
      }
    }

    "reject querying elasticsearch projection(s) for a deprecated view" in {
      val encodedId = UrlUtils.encode(esId.toString)

      val endpoints = List(
        s"/v1/views/myorg/myproj/$uuid/projections/_/_search",
        s"/v1/views/myorg/myproj/$uuid/projections/$encodedId/_search"
      )

      forAll(endpoints) { endpoint =>
        Post(endpoint, esQuery.asJson)(CirceMarshalling.jsonMarshaller, sc) ~> asBob ~> routes ~> check {
          response.status shouldEqual StatusCodes.BadRequest
          response.asJson shouldEqual jsonContentOf(
            "routes/errors/view-deprecated.json",
            "id" -> nxv.base / uuid.toString
          )
        }
      }
    }

    "redirect to fusion for the latest version if the Accept header is set to text/html" in {
      Get(s"/v1/views/myorg/myproj/$uuid") ~> Accept(`text/html`) ~> routes ~> check {
        response.status shouldEqual StatusCodes.SeeOther
        response.header[Location].value.uri shouldEqual Uri(
          s"https://bbp.epfl.ch/nexus/web/myorg/myproj/resources/$uuid"
        )
      }
    }
  }
}
