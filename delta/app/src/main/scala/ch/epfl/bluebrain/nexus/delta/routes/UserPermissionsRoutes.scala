package ch.epfl.bluebrain.nexus.delta.routes

import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import ch.epfl.bluebrain.nexus.delta.sdk.acls.AclCheck
import ch.epfl.bluebrain.nexus.delta.sdk.circe.CirceUnmarshalling
import ch.epfl.bluebrain.nexus.delta.sdk.directives.AuthDirectives
import ch.epfl.bluebrain.nexus.delta.sdk.directives.DeltaDirectives._
import ch.epfl.bluebrain.nexus.delta.sdk.identities.Identities
import ch.epfl.bluebrain.nexus.delta.sdk.model.BaseUri
import monix.execution.Scheduler

/**
  * The permissions routes.
  *
  * @param identities
  *   the identities operations bundle
  * @param permissions
  *   the permissions operations bundle
  * @param aclCheck
  *   verify the acls for users
  */
final class UserPermissionsRoutes(identities: Identities, aclCheck: AclCheck)(implicit
    baseUri: BaseUri,
    s: Scheduler,
) extends AuthDirectives(identities, aclCheck)
    with CirceUnmarshalling {

  def routes: Route =
    baseUriPrefix(baseUri.prefix) {
      pathPrefix("user") {
        pathPrefix("permissions") {
          projectRef { project =>
            extractCaller { implicit caller =>
              (head & permission) { permission =>
                authorizeFor(project, permission)(caller) {
                  complete(StatusCodes.NoContent)
                }
              }
            }
          }
        }
      }
    }
}

object UserPermissionsRoutes {
  def apply(identities: Identities, aclCheck: AclCheck)(implicit
      baseUri: BaseUri,
      s: Scheduler,
  ): Route =
    new UserPermissionsRoutes(identities, aclCheck: AclCheck).routes

}
