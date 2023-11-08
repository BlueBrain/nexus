package ch.epfl.bluebrain.nexus.delta.routes

import akka.http.scaladsl.model.headers.{`Last-Event-ID`, OAuth2BearerToken}
import akka.http.scaladsl.model.sse.ServerSentEvent
import akka.http.scaladsl.model.{MediaTypes, StatusCodes}
import akka.http.scaladsl.server.Route
import cats.effect.IO
import ch.epfl.bluebrain.nexus.delta.sdk.acls.AclSimpleCheck
import ch.epfl.bluebrain.nexus.delta.sdk.acls.model.AclAddress
import ch.epfl.bluebrain.nexus.delta.sdk.directives.DeltaSchemeDirectives
import ch.epfl.bluebrain.nexus.delta.sdk.identities.IdentitiesDummy
import ch.epfl.bluebrain.nexus.delta.sdk.identities.model.Caller
import ch.epfl.bluebrain.nexus.delta.sdk.permissions.Permissions.events
import ch.epfl.bluebrain.nexus.delta.sdk.projects.FetchContextDummy
import ch.epfl.bluebrain.nexus.delta.sdk.sse.{ServerSentEventStream, SseElemStream}
import ch.epfl.bluebrain.nexus.delta.sdk.utils.BaseRouteSpec
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.{Anonymous, Authenticated, Group}
import ch.epfl.bluebrain.nexus.delta.sourcing.model.ProjectRef
import ch.epfl.bluebrain.nexus.delta.sourcing.offset.Offset
import ch.epfl.bluebrain.nexus.delta.sourcing.query.SelectFilter
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.RemainingElems
import ch.epfl.bluebrain.nexus.testkit.CirceLiteral
import ch.epfl.bluebrain.nexus.testkit.ce.IOFromMap
import fs2.Stream

import java.time.Instant
import java.util.UUID

class ElemRoutesSpec extends BaseRouteSpec with CirceLiteral with IOFromMap {

  private val aclCheck = AclSimpleCheck().accepted

  implicit private val caller: Caller =
    Caller(alice, Set(alice, Anonymous, Authenticated(realm), Group("group", realm)))

  private val identities = IdentitiesDummy(caller)
  private val asAlice    = addCredentials(OAuth2BearerToken("alice"))

  private val uuid       = UUID.randomUUID()
  private val projectRef = ProjectRef.unsafe("org", "proj")

  private val elem1 = ServerSentEvent("""{"id":"id1"}""", "Success", "1")
  private val elem2 = ServerSentEvent("""{"id":"id2"}""", "Dropped", "2")
  private val elem3 = ServerSentEvent("""{"id":"id3"}""", "Failed", "3")

  private val sseElemStream = new SseElemStream {

    private val stream = Stream.emits(List(elem1, elem2, elem3)).covary[IO]

    override def continuous(project: ProjectRef, selectFilter: SelectFilter, start: Offset): ServerSentEventStream =
      stream

    override def currents(project: ProjectRef, selectFilter: SelectFilter, start: Offset): ServerSentEventStream =
      stream
    override def remaining(
        project: ProjectRef,
        selectFilter: SelectFilter,
        start: Offset
    ): IO[Option[RemainingElems]]                                                                                =
      IO.pure(Some(RemainingElems(999L, Instant.EPOCH)))
  }

  private val routes = Route.seal(
    new ElemRoutes(
      identities,
      aclCheck,
      sseElemStream,
      DeltaSchemeDirectives(
        FetchContextDummy.empty,
        ioFromMap(uuid -> projectRef.organization),
        ioFromMap(uuid -> projectRef)
      )
    ).routes
  )

  private val expected =
    """
      |data:{"id":"id1"}
      |event:Success
      |id:1
      |
      |data:{"id":"id2"}
      |event:Dropped
      |id:2
      |
      |data:{"id":"id3"}
      |event:Failed
      |id:3
      |""".stripMargin.strip

  "ElemRoutes" should {
    "fail to get the elems stream without events/read permission" in {
      aclCheck.append(AclAddress.Root, alice -> Set(events.read)).accepted

      val endpoints = List(
        "/v1/elems/org/proj/continuous",
        "/v1/elems/org/xxx/continuous",
        "/v1/elems/org/proj/currents",
        "/v1/elems/org/proj/remaining"
      )

      forAll(endpoints) { endpoint =>
        Get(endpoint) ~> `Last-Event-ID`("2") ~> routes ~> check {
          response.shouldBeForbidden
        }

        Head(endpoint) ~> routes ~> check {
          response.shouldBeForbidden
        }
      }
    }

    "get the continuous elems" in {
      Get("/v1/elems/org/proj/continuous") ~> asAlice ~> routes ~> check {
        mediaType shouldBe MediaTypes.`text/event-stream`
        chunksStream.asString(3).strip shouldEqual expected
      }
    }

    "get the current elems" in {
      Get("/v1/elems/org/proj/currents") ~> asAlice ~> routes ~> check {
        mediaType shouldBe MediaTypes.`text/event-stream`
        chunksStream.asString(3).strip shouldEqual expected
      }
    }

    "get the remaining elem" in {
      val expected =
        json"""{
          "@context": "https://bluebrain.github.io/nexus/contexts/offset.json",
          "count": 999,
          "maxInstant" : "1970-01-01T00:00:00Z"}
          """

      Get("/v1/elems/org/proj/remaining") ~> asAlice ~> routes ~> check {
        response.status shouldEqual StatusCodes.OK
        response.asJson shouldEqual expected
      }
    }
  }

}
