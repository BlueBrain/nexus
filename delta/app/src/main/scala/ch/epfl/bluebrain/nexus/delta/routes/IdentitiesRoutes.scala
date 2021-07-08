package ch.epfl.bluebrain.nexus.delta.routes

import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.RemoteContextResolution
import ch.epfl.bluebrain.nexus.delta.rdf.utils.JsonKeyOrdering
import ch.epfl.bluebrain.nexus.delta.sdk.directives.DeltaDirectives._
import ch.epfl.bluebrain.nexus.delta.sdk.{Acls, Identities}
import ch.epfl.bluebrain.nexus.delta.sdk.directives.AuthDirectives
import ch.epfl.bluebrain.nexus.delta.sdk.model.BaseUri
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Caller._
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Identity._
import kamon.instrumentation.akka.http.TracingDirectives.operationName
import monix.bio.IO
import monix.execution.Scheduler

/**
  * The identities routes
  */
class IdentitiesRoutes(identities: Identities, acls: Acls)(implicit
    override val s: Scheduler,
    baseUri: BaseUri,
    cr: RemoteContextResolution,
    ordering: JsonKeyOrdering
) extends AuthDirectives(identities, acls) {

  def routes: Route = {
    baseUriPrefix(baseUri.prefix) {
      (pathPrefix("identities") & pathEndOrSingleSlash) {
        operationName(s"/${baseUri.prefix}/identities") {
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
    * @return the [[Route]] for identities
    */
  def apply(
      identities: Identities,
      acls: Acls
  )(implicit baseUri: BaseUri, s: Scheduler, cr: RemoteContextResolution, ordering: JsonKeyOrdering): Route =
    new IdentitiesRoutes(identities, acls).routes
}
