package ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.routes

import akka.http.scaladsl.model.headers.`Last-Event-ID`
import akka.http.scaladsl.model.{MediaTypes, StatusCodes}
import akka.http.scaladsl.server.Route
import cats.effect.IO
import ch.epfl.bluebrain.nexus.delta.kernel.utils.UrlUtils
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.CompositeViewsGen
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.indexing.CompositeViewDef.ActiveViewDef
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.model.CompositeRestart.{FullRebuild, FullRestart, PartialRebuild}
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.model.CompositeViewRejection.ProjectContextRejection
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.model.{permissions, CompositeViewRejection}
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.projections.{CompositeIndexingDetails, CompositeProjections}
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.store.CompositeRestartStore
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.stream.CompositeBranch.Run.Main
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.stream.{CompositeBranch, CompositeProgress}
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.test.{expandOnlyIris, expectIndexingView}
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.nxv
import ch.epfl.bluebrain.nexus.delta.sdk.acls.model.AclAddress
import ch.epfl.bluebrain.nexus.delta.sdk.directives.DeltaSchemeDirectives
import ch.epfl.bluebrain.nexus.delta.sdk.implicits._
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.PaginationConfig
import ch.epfl.bluebrain.nexus.delta.sdk.projects.FetchContextDummy
import ch.epfl.bluebrain.nexus.delta.sdk.views.ViewRef
import ch.epfl.bluebrain.nexus.delta.sourcing.config.{BatchConfig, QueryConfig}
import ch.epfl.bluebrain.nexus.delta.sourcing.model.EntityType
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.Anonymous
import ch.epfl.bluebrain.nexus.delta.sourcing.offset.Offset
import ch.epfl.bluebrain.nexus.delta.sourcing.projections.ProjectionErrors
import ch.epfl.bluebrain.nexus.delta.sourcing.query.RefreshStrategy
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.Elem.FailedElem
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.{ProjectionProgress, RemainingElems}
import io.circe.Json
import io.circe.syntax._

import java.time.Instant
import scala.concurrent.duration._
class CompositeViewsIndexingRoutesSpec extends CompositeViewsRoutesFixtures {

  implicit private val paginationConfig: PaginationConfig = PaginationConfig(5, 10, 5)

  private val now      = Instant.now()
  private val nowPlus5 = now.plusSeconds(5)

  private val fetchContext    = FetchContextDummy[CompositeViewRejection](List(project), ProjectContextRejection)
  private val groupDirectives = DeltaSchemeDirectives(fetchContext, _ => IO.none, _ => IO.none)

  private val myId         = nxv + "myid"
  private val view         = CompositeViewsGen.resourceFor(projectRef, myId, uuid, viewValue, source = Json.obj())
  private val indexingView = ActiveViewDef(
    ViewRef(view.value.project, view.id),
    view.value.uuid,
    view.rev,
    viewValue
  )

  private lazy val restartStore     = new CompositeRestartStore(xas)
  private lazy val projections      =
    CompositeProjections(
      restartStore,
      xas,
      QueryConfig(5, RefreshStrategy.Stop),
      BatchConfig(5, 100.millis),
      3.seconds
    )
  private lazy val projectionErrors = ProjectionErrors(xas, queryConfig)

  private def lastRestart = restartStore.last(ViewRef(project.ref, myId)).map(_.flatMap(_.toOption)).accepted

  private val details: CompositeIndexingDetails = new CompositeIndexingDetails(
    (_) =>
      IO.pure(
        CompositeProgress(
          Map(
            CompositeBranch(projectSource.id, esProjection.id, Main)         ->
              ProjectionProgress(Offset.at(3L), now, 6, 1, 1),
            CompositeBranch(projectSource.id, blazegraphProjection.id, Main) ->
              ProjectionProgress(Offset.at(3L), now, 6, 1, 1)
          )
        )
      ),
    (_, _, _) => IO.pure(Some(RemainingElems(10, nowPlus5))),
    "prefix"
  )

  private lazy val routes =
    Route.seal(
      CompositeViewsIndexingRoutes(
        identities,
        aclCheck,
        expectIndexingView(indexingView, "myid"),
        expandOnlyIris,
        details,
        projections,
        projectionErrors,
        groupDirectives
      )
    )

  override def beforeAll(): Unit = {
    super.beforeAll()
    val error = new Exception("boom")
    val rev   = 1
    val fail1 = FailedElem(EntityType("ACL"), myId, Some(projectRef), Instant.EPOCH, Offset.At(42L), error, rev)
    val fail2 = FailedElem(EntityType("Schema"), myId, None, Instant.EPOCH, Offset.At(42L), error, rev)
    val save  = projectionErrors.saveFailedElems(indexingView.metadata, List(fail1, fail2))
    save.accepted
  }

  private val bgProjectionEncodedId = UrlUtils.encode(blazegraphProjection.id.toString)

  private val viewEndpoint = "/views/myorg/myproj/myid"

  "Composite views routes" should {

    "fail to fetch/delete offset without permission" in {
      val endpoints = List(
        s"$viewEndpoint/offset",
        s"$viewEndpoint/projections/_/offset",
        s"$viewEndpoint/projections/$bgProjectionEncodedId/offset"
      )
      forAll(endpoints) { endpoint =>
        forAll(List(Get(endpoint), Delete(endpoint))) { req =>
          req ~> routes ~> check {
            response.shouldBeForbidden
          }
        }
      }

      lastRestart shouldEqual None
    }

    "fetch offsets" in {
      aclCheck.append(AclAddress.Root, caller.subject -> Set(permissions.write, permissions.read)).accepted
      val viewOffsets       = jsonContentOf("routes/responses/view-offsets.json")
      val projectionOffsets = jsonContentOf("routes/responses/view-offsets-projection.json")
      val endpoints         = List(
        s"$viewEndpoint/offset"                                    -> viewOffsets,
        s"$viewEndpoint/projections/_/offset"                      -> viewOffsets,
        s"$viewEndpoint/projections/$bgProjectionEncodedId/offset" -> projectionOffsets
      )
      forAll(endpoints) { case (endpoint, expected) =>
        Get(endpoint) ~> asBob ~> routes ~> check {
          response.status shouldEqual StatusCodes.OK
          response.asJson shouldEqual expected
        }
      }
    }

    "fetch statistics" in {
      val encodedSource   = UrlUtils.encode(projectSource.id.toString)
      val viewStats       = jsonContentOf(
        "routes/responses/view-statistics.json",
        "last"                  -> nowPlus5,
        "instant_elasticsearch" -> now,
        "instant_blazegraph"    -> now
      )
      val projectionStats = jsonContentOf(
        "routes/responses/view-statistics-projection.json",
        "last"    -> nowPlus5,
        "instant" -> now
      )
      val sourceStats     = jsonContentOf(
        "routes/responses/view-statistics-source.json",
        "last"                  -> nowPlus5,
        "instant_elasticsearch" -> now,
        "instant_blazegraph"    -> now
      )
      val endpoints       = List(
        s"$viewEndpoint/statistics"                                    -> viewStats,
        s"$viewEndpoint/projections/_/statistics"                      -> viewStats,
        s"$viewEndpoint/projections/$bgProjectionEncodedId/statistics" -> projectionStats,
        s"$viewEndpoint/sources/$encodedSource/statistics"             -> sourceStats
      )
      forAll(endpoints) { case (endpoint, expected) =>
        Get(endpoint) ~> asBob ~> routes ~> check {
          response.status shouldEqual StatusCodes.OK
          response.asJson shouldEqual expected
        }
      }
    }

    "fail to fetch indexing description without permission" in {
      Get(s"$viewEndpoint/description") ~> routes ~> check {
        response.shouldBeForbidden
      }
    }

    "fetch indexing description" in {
      Get(s"$viewEndpoint/description") ~> asBob ~> routes ~> check {
        response.status shouldEqual StatusCodes.OK
        response.asJson shouldEqual jsonContentOf(
          "routes/responses/view-indexing-description.json",
          "uuid"                  -> uuid,
          "last"                  -> nowPlus5,
          "instant_elasticsearch" -> now,
          "instant_blazegraph"    -> now
        )
      }
    }

    "delete offsets" in {
      val viewOffsets       =
        jsonContentOf("routes/responses/view-offsets.json").replaceKeyWithValue("offset", Offset.start.asJson)
      val projectionOffsets =
        jsonContentOf("routes/responses/view-offsets-projection.json").replaceKeyWithValue(
          "offset",
          Offset.start.asJson
        )

      Delete(s"$viewEndpoint/offset") ~> asBob ~> routes ~> check {
        response.status shouldEqual StatusCodes.OK
        response.asJson shouldEqual viewOffsets
        lastRestart.value shouldEqual FullRestart(indexingView.ref, Instant.EPOCH, bob)
      }

      val endpoints = List(
        (
          s"$viewEndpoint/projections/_/offset",
          viewOffsets,
          FullRebuild(indexingView.ref, Instant.EPOCH, bob)
        ),
        (
          s"$viewEndpoint/projections/$bgProjectionEncodedId/offset",
          projectionOffsets,
          PartialRebuild(indexingView.ref, blazegraphProjection.id, Instant.EPOCH, bob)
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

    "return no failures without write permission" in {
      val endpoints = List(
        s"$viewEndpoint/failures",
        s"$viewEndpoint/failures/sse"
      )
      forAll(endpoints) { endpoint =>
        Get(endpoint) ~> routes ~> check {
          response.shouldBeForbidden
        }
      }
    }

    "return all failures as SSE when no LastEventID is provided" in {
      aclCheck.append(AclAddress.Root, Anonymous -> Set(permissions.write)).accepted
      Get(s"$viewEndpoint/failures/sse") ~> routes ~> check {
        response.status shouldBe StatusCodes.OK
        mediaType shouldBe MediaTypes.`text/event-stream`
        chunksStream.asString(2).strip shouldEqual contentOf("/routes/sse/indexing-failures-1-2.txt")
      }
    }

    "return failures as SSE only from the given LastEventID" in {
      Get(s"$viewEndpoint/failures/sse") ~> `Last-Event-ID`("1") ~> routes ~> check {
        response.status shouldBe StatusCodes.OK
        mediaType shouldBe MediaTypes.`text/event-stream`
        chunksStream.asString(3).strip shouldEqual contentOf("/routes/sse/indexing-failure-2.txt")
      }
    }

    "return failures as a listing" in {
      aclCheck.append(AclAddress.Root, Anonymous -> Set(permissions.write)).accepted
      Get(s"$viewEndpoint/failures") ~> routes ~> check {
        response.status shouldBe StatusCodes.OK
        response.asJson shouldEqual jsonContentOf("/routes/list-indexing-errors.json")
      }
    }
  }
}
