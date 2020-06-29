package ch.epfl.bluebrain.nexus.kg.routes

import akka.actor.ActorSystem
import akka.http.scaladsl.marshalling.sse.EventStreamMarshalling._
import akka.http.scaladsl.server.Directives.complete
import akka.http.scaladsl.server.Route
import ch.epfl.bluebrain.nexus.iam.acls.Acls
import ch.epfl.bluebrain.nexus.iam.realms.Realms
import ch.epfl.bluebrain.nexus.iam.types.{Caller, Permission}
import ch.epfl.bluebrain.nexus.kg.persistence.TaggingAdapter
import ch.epfl.bluebrain.nexus.kg.resources.Event.JsonLd._
import ch.epfl.bluebrain.nexus.service.config.ServiceConfig
import ch.epfl.bluebrain.nexus.service.directives.AuthDirectives
import kamon.instrumentation.akka.http.TracingDirectives.operationName
import monix.eval.Task
import monix.execution.Scheduler.Implicits.global

class GlobalEventRoutes(acls: Acls[Task], realms: Realms[Task], caller: Caller)(implicit
    override val as: ActorSystem,
    override val config: ServiceConfig
) extends AuthDirectives(acls, realms)
    with EventCommonRoutes {

  private val read: Permission = Permission.unsafe("events/read")

  def routes: Route =
    lastEventId { offset =>
      operationName(s"/${config.http.prefix}/events") {
        authorizeFor(permission = read)(caller) {
          complete(source(TaggingAdapter.EventTag, offset))
        }
      }
    }
}
