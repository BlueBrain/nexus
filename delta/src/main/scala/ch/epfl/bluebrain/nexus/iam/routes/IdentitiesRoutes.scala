package ch.epfl.bluebrain.nexus.iam.routes

import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import ch.epfl.bluebrain.nexus.iam.acls.Acls
import ch.epfl.bluebrain.nexus.iam.realms.Realms
import ch.epfl.bluebrain.nexus.iam.types.Caller.JsonLd._
import ch.epfl.bluebrain.nexus.delta.directives.AuthDirectives
import ch.epfl.bluebrain.nexus.delta.config.AppConfig.HttpConfig
import ch.epfl.bluebrain.nexus.delta.marshallers.instances._
import kamon.instrumentation.akka.http.TracingDirectives.operationName
import monix.eval.Task
import monix.execution.Scheduler.Implicits.global

/**
  * The identities routes.
  *
  * @param realms the realms api
  */
class IdentitiesRoutes(acls: Acls[Task], realms: Realms[Task])(implicit http: HttpConfig)
    extends AuthDirectives(acls, realms) {

  def routes: Route = {
    (pathPrefix("identities") & pathEndOrSingleSlash) {
      operationName(s"/${http.prefix}/identities") {
        (extractCaller & get) { caller =>
          complete(caller)
        }
      }
    }
  }
}
