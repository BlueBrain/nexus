package ch.epfl.bluebrain.nexus.iam.routes

import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import ch.epfl.bluebrain.nexus.iam.config.AppConfig.HttpConfig
import ch.epfl.bluebrain.nexus.iam.directives.AuthDirectives.authenticator
import ch.epfl.bluebrain.nexus.iam.marshallers.instances._
import ch.epfl.bluebrain.nexus.iam.realms.Realms
import ch.epfl.bluebrain.nexus.iam.types.Caller
import ch.epfl.bluebrain.nexus.iam.types.Caller.JsonLd._
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
