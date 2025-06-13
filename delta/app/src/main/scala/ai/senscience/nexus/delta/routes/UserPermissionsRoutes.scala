package ai.senscience.nexus.delta.routes

import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.server.Route
import ch.epfl.bluebrain.nexus.akka.marshalling.CirceUnmarshalling
import ch.epfl.bluebrain.nexus.delta.sdk.acls.AclCheck
import ch.epfl.bluebrain.nexus.delta.sdk.acls.model.AclAddress
import ch.epfl.bluebrain.nexus.delta.sdk.directives.AuthDirectives
import ch.epfl.bluebrain.nexus.delta.sdk.directives.DeltaDirectives.*
import ch.epfl.bluebrain.nexus.delta.sdk.identities.Identities
import ch.epfl.bluebrain.nexus.delta.sdk.model.{BaseUri, IdSegment, IdSegmentRef}
import ch.epfl.bluebrain.nexus.delta.sdk.permissions.StoragePermissionProvider
import ch.epfl.bluebrain.nexus.delta.sdk.permissions.StoragePermissionProvider.AccessType
import ch.epfl.bluebrain.nexus.delta.sdk.permissions.model.Permission

/**
  * The user permissions routes. Used for checking whether the current logged in user has certain permissions.
  *
  * @param identities
  *   the identities operations bundle
  * @param aclCheck
  *   verify the acls for users
  */
final class UserPermissionsRoutes(identities: Identities, aclCheck: AclCheck, storages: StoragePermissionProvider)(
    implicit baseUri: BaseUri
) extends AuthDirectives(identities, aclCheck)
    with CirceUnmarshalling {

  def routes: Route =
    baseUriPrefix(baseUri.prefix) {
      pathPrefix("user") {
        pathPrefix("permissions") {
          projectRef { project =>
            extractCaller { implicit caller =>
              head {
                concat(
                  parameter("permission".as[Permission]) { permission =>
                    authorizeFor(project, permission)(caller) {
                      complete(StatusCodes.NoContent)
                    }
                  },
                  parameters("storage".as[IdSegment], "type".as[AccessType]) { (storageId, `type`) =>
                    authorizeForIO(
                      AclAddress.fromProject(project),
                      storages.permissionFor(IdSegmentRef(storageId), project, `type`)
                    )(caller) {
                      complete(StatusCodes.NoContent)
                    }
                  }
                )
              }
            }
          }
        }
      }
    }
}

object UserPermissionsRoutes {
  def apply(identities: Identities, aclCheck: AclCheck, storagePermissionProvider: StoragePermissionProvider)(implicit
      baseUri: BaseUri
  ): Route =
    new UserPermissionsRoutes(identities, aclCheck: AclCheck, storagePermissionProvider).routes
}
