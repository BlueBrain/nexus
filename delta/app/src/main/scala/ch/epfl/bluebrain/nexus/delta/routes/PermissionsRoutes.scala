package ch.epfl.bluebrain.nexus.delta.routes

import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.RemoteContextResolution
import ch.epfl.bluebrain.nexus.delta.sdk.Permissions
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Identity.{Subject, User}
import ch.epfl.bluebrain.nexus.delta.sdk.model.permissions.PermissionSet
import ch.epfl.bluebrain.nexus.delta.sdk.model.{BaseUri, Label}
import ch.epfl.bluebrain.nexus.delta.utils.IOSupport
import de.heikoseeberger.akkahttpcirce.FailFastCirceSupport
import monix.execution.Scheduler

// TODO: strip fail fast circe support
class PermissionsRoutes(permissions: Permissions)(implicit s: Scheduler, rcr: RemoteContextResolution, base: BaseUri)
    extends IOSupport with FailFastCirceSupport {

  def routes: Route =
    concat(
      (get & path("v1" / "permissions")) {
        completeUIO(StatusCodes.OK, permissions.fetch)
      },
      (put & path("v1" / "permissions")) {
        entity(as[PermissionSet]) { set =>
          parameter("rev".as[Long]) { rev =>
            implicit val caller: Subject = User("broman", Label.unsafe("bbp"))
            completeIO(StatusCodes.OK, permissions.replace(set.permissions, rev))
          }
        }
      }
    )

}
