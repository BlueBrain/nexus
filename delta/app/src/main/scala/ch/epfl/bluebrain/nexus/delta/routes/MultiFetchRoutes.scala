package ch.epfl.bluebrain.nexus.delta.routes

import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.RemoteContextResolution
import ch.epfl.bluebrain.nexus.delta.rdf.utils.JsonKeyOrdering
import ch.epfl.bluebrain.nexus.delta.sdk.acls.AclCheck
import ch.epfl.bluebrain.nexus.delta.sdk.circe.CirceUnmarshalling
import ch.epfl.bluebrain.nexus.delta.sdk.directives.AuthDirectives
import ch.epfl.bluebrain.nexus.delta.sdk.directives.DeltaDirectives._
import ch.epfl.bluebrain.nexus.delta.sdk.directives.UriDirectives.baseUriPrefix
import ch.epfl.bluebrain.nexus.delta.sdk.identities.Identities
import ch.epfl.bluebrain.nexus.delta.sdk.marshalling.RdfMarshalling
import ch.epfl.bluebrain.nexus.delta.sdk.model.BaseUri
import ch.epfl.bluebrain.nexus.delta.sdk.multifetch.MultiFetch
import ch.epfl.bluebrain.nexus.delta.sdk.multifetch.model.MultiFetchRequest
import monix.execution.Scheduler

/**
  * Route allowing to fetch multiple resources in a single request
  */
class MultiFetchRoutes(
    identities: Identities,
    aclCheck: AclCheck,
    multiFetch: MultiFetch
)(implicit
    baseUri: BaseUri,
    cr: RemoteContextResolution,
    ordering: JsonKeyOrdering,
    s: Scheduler
) extends AuthDirectives(identities, aclCheck)
    with CirceUnmarshalling
    with RdfMarshalling {

  def routes: Route =
    baseUriPrefix(baseUri.prefix) {
      pathPrefix("multi-fetch") {
        pathPrefix("resources") {
          extractCaller { implicit caller =>
            (get & entity(as[MultiFetchRequest])) { request =>
              emit(multiFetch(request).flatMap(_.asJson))
            }
          }
        }
      }
    }

}

object MultiFetchRoutes {

  /**
    * @return
    *   the [[Route]] for the multi-fetch operation
    */
  def apply(identities: Identities, aclCheck: AclCheck, multiFetch: MultiFetch)(implicit
      baseUri: BaseUri,
      cr: RemoteContextResolution,
      ordering: JsonKeyOrdering,
      s: Scheduler
  ): Route = new MultiFetchRoutes(identities, aclCheck, multiFetch).routes

}
