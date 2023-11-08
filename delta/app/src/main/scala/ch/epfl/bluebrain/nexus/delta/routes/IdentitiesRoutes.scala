package ch.epfl.bluebrain.nexus.delta.routes

import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import cats.effect.IO
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.RemoteContextResolution
import ch.epfl.bluebrain.nexus.delta.rdf.utils.JsonKeyOrdering
import ch.epfl.bluebrain.nexus.delta.sdk.acls.AclCheck
import ch.epfl.bluebrain.nexus.delta.sdk.directives.AuthDirectives
import ch.epfl.bluebrain.nexus.delta.sdk.directives.DeltaDirectives._
import ch.epfl.bluebrain.nexus.delta.sdk.identities.Identities
import ch.epfl.bluebrain.nexus.delta.sdk.model.BaseUri
import ch.epfl.bluebrain.nexus.delta.sdk.identities.model.Caller._
import kamon.instrumentation.akka.http.TracingDirectives.operationName

/**
  * The identities routes
  */
class IdentitiesRoutes(identities: Identities, aclCheck: AclCheck)(implicit
    baseUri: BaseUri,
    cr: RemoteContextResolution,
    ordering: JsonKeyOrdering
) extends AuthDirectives(identities, aclCheck) {

  import baseUri.prefixSegment

  def routes: Route = {
    baseUriPrefix(baseUri.prefix) {
      (pathPrefix("identities") & pathEndOrSingleSlash) {
        operationName(s"/$prefixSegment/identities") {
          (extractCaller & get) { caller =>
            emit(IO.pure(caller))
          }
        }
      }
    }
  }
}

object IdentitiesRoutes {

  /**
    * @return
    *   the [[Route]] for identities
    */
  def apply(
      identities: Identities,
      aclCheck: AclCheck
  )(implicit baseUri: BaseUri, cr: RemoteContextResolution, ordering: JsonKeyOrdering): Route =
    new IdentitiesRoutes(identities, aclCheck).routes
}
