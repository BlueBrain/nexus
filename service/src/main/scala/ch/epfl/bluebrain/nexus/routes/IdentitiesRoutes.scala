package ch.epfl.bluebrain.nexus.routes

import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import ch.epfl.bluebrain.nexus.auth.Caller
import ch.epfl.bluebrain.nexus.auth.Caller.JsonLd._
import ch.epfl.bluebrain.nexus.config.AppConfig.HttpConfig
import ch.epfl.bluebrain.nexus.directives.AuthDirectives.authenticator
import ch.epfl.bluebrain.nexus.marshallers.instances._
import ch.epfl.bluebrain.nexus.realms.Realms
import kamon.instrumentation.akka.http.TracingDirectives.operationName
import monix.eval.Task
import monix.execution.Scheduler.Implicits.global

/**
  * The identities routes.
  *
  * @param realms the realms api
  */
class IdentitiesRoutes(realms: Realms[Task])(implicit http: HttpConfig) {

  def routes: Route = {
    (pathPrefix("identities") & pathEndOrSingleSlash) {
      operationName(s"/${http.prefix}/identities") {
        authenticateOAuth2Async("*", authenticator(realms)).withAnonymousUser(Caller.anonymous) { implicit caller =>
          get {
            complete(caller)
          }
        }
      }
    }
  }
}
