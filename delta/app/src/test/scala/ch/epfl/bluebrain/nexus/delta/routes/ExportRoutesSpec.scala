package ch.epfl.bluebrain.nexus.delta.routes

import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.model.headers.OAuth2BearerToken
import akka.http.scaladsl.server.Route
import cats.effect.{IO, Ref}
import ch.epfl.bluebrain.nexus.delta.sdk.acls.AclSimpleCheck
import ch.epfl.bluebrain.nexus.delta.sdk.acls.model.AclAddress.Root
import ch.epfl.bluebrain.nexus.delta.sdk.identities.IdentitiesDummy
import ch.epfl.bluebrain.nexus.delta.sdk.identities.model.Caller
import ch.epfl.bluebrain.nexus.delta.sdk.permissions.Permissions
import ch.epfl.bluebrain.nexus.delta.sdk.utils.BaseRouteSpec
import ch.epfl.bluebrain.nexus.delta.sourcing.exporter.Exporter.ExportResult
import ch.epfl.bluebrain.nexus.delta.sourcing.exporter.{ExportEventQuery, Exporter}
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.{Anonymous, Authenticated, Group}
import fs2.io.file.Path

import java.time.Instant

class ExportRoutesSpec extends BaseRouteSpec {

  private val caller = Caller(alice, Set(alice, Anonymous, Authenticated(realm), Group("group", realm)))

  private val identities = IdentitiesDummy(caller)

  private val asAlice = addCredentials(OAuth2BearerToken("alice"))

  private val exportTrigger = Ref.unsafe[IO, Boolean](false)

  private val aclCheck = AclSimpleCheck((alice, Root, Set(Permissions.exporter.run))).accepted

  private val exporter = new Exporter {
    override def events(query: ExportEventQuery): IO[ExportResult] =
      exportTrigger.set(true).as(ExportResult(Path("json"), Path("Success"), Instant.EPOCH, Instant.EPOCH))
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
      Post("/v1/export/events", query.toEntity) ~> asAlice ~> routes ~> check {
        response.status shouldEqual StatusCodes.Accepted
        exportTrigger.get.accepted shouldEqual true
      }
    }
  }

}
