package ai.senscience.nexus.delta.routes

import akka.http.scaladsl.server.Directives.*
import akka.http.scaladsl.server.Route
import cats.effect.IO
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.RemoteContextResolution
import ch.epfl.bluebrain.nexus.delta.rdf.utils.JsonKeyOrdering
import ch.epfl.bluebrain.nexus.delta.sdk.directives.DeltaDirectives.{baseUriPrefix, emit}
import ch.epfl.bluebrain.nexus.delta.sdk.error.ServiceError.AuthorizationFailed
import ch.epfl.bluebrain.nexus.delta.sdk.model.BaseUri

/**
  * Route to show errors
  */
final class ErrorRoutes()(implicit
    baseUri: BaseUri,
    cr: RemoteContextResolution,
    ordering: JsonKeyOrdering
) {

  def routes: Route =
    baseUriPrefix(baseUri.prefix) {
      pathPrefix("errors") {
        pathPrefix("invalid") {
          (get & extractRequest & pathEndOrSingleSlash) { request =>
            emit(IO.pure(AuthorizationFailed(request)))
          }
        }
      }
    }
}
