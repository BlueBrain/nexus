package ai.senscience.nexus.delta.routes

import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.server.Route
import cats.effect.{IO, Ref}
import ch.epfl.bluebrain.nexus.delta.sdk.acls.AclSimpleCheck
import ch.epfl.bluebrain.nexus.delta.sdk.acls.model.AclAddress.Root
import ch.epfl.bluebrain.nexus.delta.sdk.identities.IdentitiesDummy
import ch.epfl.bluebrain.nexus.delta.sdk.permissions.Permissions
import ch.epfl.bluebrain.nexus.delta.sdk.utils.BaseRouteSpec
import ch.epfl.bluebrain.nexus.delta.sourcing.exporter.Exporter.ExportResult
import ch.epfl.bluebrain.nexus.delta.sourcing.exporter.{ExportEventQuery, Exporter}
import fs2.io.file.Path

class ExportRoutesSpec extends BaseRouteSpec {

  private val identities = IdentitiesDummy.fromUsers(alice)

  private val exportTrigger = Ref.unsafe[IO, Boolean](false)

  private val aclCheck = AclSimpleCheck((alice, Root, Set(Permissions.exporter.run))).accepted

  private val exporter = new Exporter {
    override def events(query: ExportEventQuery): IO[ExportResult] =
      exportTrigger.set(true).as(ExportResult(Path("target"), Path("success")))
  }

  private lazy val routes = Route.seal(
    new ExportRoutes(
      identities,
      aclCheck,
      exporter
    ).routes
  )

  "The export route" should {
    val query =
      json"""{ "output": "export-test", "projects": ["org/proj", "org/proj2"], "offset": {"@type": "At", "value": 2}  }"""
    "fail triggering the export the 'export/run' permission" in {
      Post("/v1/export/events", query.toEntity) ~> routes ~> check {
        response.shouldBeForbidden
        exportTrigger.get.accepted shouldEqual false
      }
    }

    "trigger the 'export/run' permission" in {
      Post("/v1/export/events", query.toEntity) ~> as(alice) ~> routes ~> check {
        response.status shouldEqual StatusCodes.Accepted
        exportTrigger.get.accepted shouldEqual true
      }
    }
  }

}
