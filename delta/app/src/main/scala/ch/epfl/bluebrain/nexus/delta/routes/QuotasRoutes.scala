package ch.epfl.bluebrain.nexus.delta.routes

import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server._
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.RemoteContextResolution
import ch.epfl.bluebrain.nexus.delta.rdf.utils.JsonKeyOrdering
import ch.epfl.bluebrain.nexus.delta.sdk.acls.AclCheck
import ch.epfl.bluebrain.nexus.delta.sdk.ce.DeltaDirectives._
import ch.epfl.bluebrain.nexus.delta.sdk.directives.AuthDirectives
import ch.epfl.bluebrain.nexus.delta.sdk.identities.Identities
import ch.epfl.bluebrain.nexus.delta.sdk.marshalling.RdfMarshalling
import ch.epfl.bluebrain.nexus.delta.sdk.model.BaseUri
import ch.epfl.bluebrain.nexus.delta.sdk.permissions.Permissions.quotas.{read => Read}
import ch.epfl.bluebrain.nexus.delta.sdk.quotas.Quotas

/**
  * The quotas routes
  *
  * @param identities
  *   the identity module
  * @param aclCheck
  *   verify the acls for users
  * @param quotas
  *   the quotas module
  */
final class QuotasRoutes(
    identities: Identities,
    aclCheck: AclCheck,
    quotas: Quotas
)(implicit baseUri: BaseUri, cr: RemoteContextResolution, ordering: JsonKeyOrdering)
    extends AuthDirectives(identities, aclCheck)
    with RdfMarshalling {

  def routes: Route =
    (baseUriPrefix(baseUri.prefix) & pathPrefix("quotas")) {
      extractCaller { implicit caller =>
        projectRef.apply { ref =>
          pathEndOrSingleSlash {
            // Get quotas for a project
            (get & authorizeFor(ref, Read)) {
              emit(quotas.fetch(ref))
            }
          }
        }
      }
    }
}
