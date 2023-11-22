package ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.routes

import akka.http.scaladsl.model.headers.`Last-Event-ID`
import akka.http.scaladsl.model.{MediaTypes, StatusCodes}
import akka.http.scaladsl.server.Route
import cats.effect.IO
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.indexing.IndexingViewDef.ActiveViewDef
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.model.BlazegraphViewRejection.{InvalidResourceId, ProjectContextRejection, ViewNotFound}
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.model._
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.routes.BlazegraphViewsIndexingRoutes.FetchIndexingView
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.nxv
import ch.epfl.bluebrain.nexus.delta.sdk.acls.model.AclAddress
import ch.epfl.bluebrain.nexus.delta.sdk.directives.DeltaSchemeDirectives
import ch.epfl.bluebrain.nexus.delta.sdk.model.IdSegment
import ch.epfl.bluebrain.nexus.delta.sdk.model.IdSegment.{IriSegment, StringSegment}
import ch.epfl.bluebrain.nexus.delta.sdk.projects.FetchContextDummy
import ch.epfl.bluebrain.nexus.delta.sdk.views.ViewRef
import ch.epfl.bluebrain.nexus.delta.sourcing.model.EntityType
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.Anonymous
import ch.epfl.bluebrain.nexus.delta.sourcing.offset.Offset
import ch.epfl.bluebrain.nexus.delta.sourcing.projections.{ProjectionErrors, Projections}
import ch.epfl.bluebrain.nexus.delta.sourcing.query.SelectFilter
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.Elem.FailedElem
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.ProjectionProgress
import ch.epfl.bluebrain.nexus.testkit.ce.IOFromMap

import java.time.Instant
import scala.concurrent.duration._

class BlazegraphViewsIndexingRoutesSpec extends BlazegraphViewRoutesFixtures with IOFromMap {

  private lazy val projections      = Projections(xas, queryConfig, 1.hour, clock)
  private lazy val projectionErrors = ProjectionErrors(xas, queryConfig, clock)

  private val fetchContext = FetchContextDummy[BlazegraphViewRejection](
    Map(project.ref -> project.context),
    Set(deprecatedProject.ref),
    ProjectContextRejection
  )

  private val groupDirectives =
    DeltaSchemeDirectives(
      fetchContext,
      ioFromMap(uuid -> projectRef.organization),
      ioFromMap(uuid -> projectRef)
    )

  private val myId         = nxv + "myid"
  private val indexingView = ActiveViewDef(
    ViewRef(projectRef, myId),
    "projection",
    SelectFilter.latest,
    None,
    "namespace",
    1,
    1
  )
  private val progress     = ProjectionProgress(Offset.at(15L), Instant.EPOCH, 9000L, 400L, 30L)

  private def fetchView: FetchIndexingView =
    (id: IdSegment, ref) =>
      id match {
        case IriSegment(`myId`)    => IO.pure(indexingView)
        case IriSegment(id)        => IO.raiseError(ViewNotFound(id, ref))
        case StringSegment("myid") => IO.pure(indexingView)
        case StringSegment(id)     => IO.raiseError(InvalidResourceId(id))
      }

  private lazy val routes =
    Route.seal(
      BlazegraphViewsIndexingRoutes(
        fetchView,
        identities,
        aclCheck,
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
    val save  = for {
      _ <- projections.save(indexingView.projectionMetadata, progress)
      _ <- projectionErrors.saveFailedElems(indexingView.projectionMetadata, List(fail1, fail2))
    } yield ()
    save.accepted
  }

  private val viewEndpoint = "/views/myorg/myproject/myid"

  "fail to fetch statistics and offset from view without resources/read permission" in {
    val endpoints = List(
      s"$viewEndpoint/statistics",
      s"$viewEndpoint/offset"
    )
    forAll(endpoints) { endpoint =>
      Get(endpoint) ~> routes ~> check {
        response.shouldBeForbidden
      }
    }
  }

  "fetch statistics from view" in {
    aclCheck.append(AclAddress.Root, Anonymous -> Set(permissions.read)).accepted

    val expectedResponse =
      json"""
        {
        "@context": "https://bluebrain.github.io/nexus/contexts/statistics.json",
        "@type": "ViewStatistics",
        "delayInSeconds" : 0,
        "discardedEvents": 400,
        "evaluatedEvents": 8570,
        "failedEvents": 30,
        "lastEventDateTime": "${Instant.EPOCH}",
        "lastProcessedEventDateTime": "${Instant.EPOCH}",
        "processedEvents": 9000,
        "remainingEvents": 0,
        "totalEvents": 9000
      }"""

    Get(s"$viewEndpoint/statistics") ~> routes ~> check {
      response.status shouldEqual StatusCodes.OK
      response.asJson shouldEqual expectedResponse
    }
  }

  "fetch offset from view" in {
    val expectedResponse =
      json"""{
        "@context" : "https://bluebrain.github.io/nexus/contexts/offset.json",
        "@type" : "At",
        "value" : 15
      }"""

    Get(s"$viewEndpoint/offset") ~> routes ~> check {
      response.status shouldEqual StatusCodes.OK
      response.asJson shouldEqual expectedResponse
    }
  }

  "fail to restart offset from view without resources/write permission" in {

    Delete(s"$viewEndpoint/offset") ~> routes ~> check {
      response.shouldBeForbidden
    }
  }

  "restart offset from view" in {

    aclCheck.append(AclAddress.Root, Anonymous -> Set(permissions.write)).accepted
    projections.restarts(Offset.start).compile.toList.accepted.size shouldEqual 0
    Delete(s"$viewEndpoint/offset") ~> routes ~> check {
      response.status shouldEqual StatusCodes.OK
      response.asJson shouldEqual json"""{"@context": "${Vocabulary.contexts.offset}", "@type": "Start"}"""
      projections.restarts(Offset.start).compile.toList.accepted.size shouldEqual 1
    }
  }

  "return no blazegraph projection failures without write permission" in {
    aclCheck.subtract(AclAddress.Root, Anonymous -> Set(permissions.write)).accepted

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
      chunksStream.asString(2).strip shouldEqual contentOf("routes/sse/indexing-failures-1-2.txt")
    }
  }

  "return failures as SSE only from the given LastEventID" in {
    Get(s"$viewEndpoint/failures/sse") ~> `Last-Event-ID`("1") ~> routes ~> check {
      response.status shouldBe StatusCodes.OK
      mediaType shouldBe MediaTypes.`text/event-stream`
      chunksStream.asString(3).strip shouldEqual contentOf("routes/sse/indexing-failure-2.txt")
    }
  }

  "return failures as a listing" in {
    aclCheck.append(AclAddress.Root, Anonymous -> Set(permissions.write)).accepted
    Get(s"$viewEndpoint/failures") ~> routes ~> check {
      response.status shouldBe StatusCodes.OK
      response.asJson shouldEqual jsonContentOf("routes/list-indexing-errors.json")
    }
  }

}
