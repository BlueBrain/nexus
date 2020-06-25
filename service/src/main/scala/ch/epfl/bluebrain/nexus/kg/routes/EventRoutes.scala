package ch.epfl.bluebrain.nexus.kg.routes

import akka.actor.ActorSystem
import akka.http.scaladsl.marshalling.sse.EventStreamMarshalling._
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import ch.epfl.bluebrain.nexus.admin.client.types.{Organization, Project}
import ch.epfl.bluebrain.nexus.iam.acls.Acls
import ch.epfl.bluebrain.nexus.iam.realms.Realms
import ch.epfl.bluebrain.nexus.iam.types.{Caller, Permission}
import ch.epfl.bluebrain.nexus.kg.resources.Event.JsonLd._
import ch.epfl.bluebrain.nexus.rdf.Iri.Path._
import ch.epfl.bluebrain.nexus.service.config.ServiceConfig
import ch.epfl.bluebrain.nexus.service.directives.AuthDirectives
import kamon.instrumentation.akka.http.TracingDirectives.operationName
import monix.eval.Task
import monix.execution.Scheduler.Implicits.global

class EventRoutes(acls: Acls[Task], realms: Realms[Task], caller: Caller)(implicit
    override val as: ActorSystem,
    override val config: ServiceConfig
) extends AuthDirectives(acls, realms)
    with EventCommonRoutes {

  private val read: Permission = Permission.unsafe("resources/read")

  def routes(project: Project): Route = {

    lastEventId { offset =>
      operationName(s"/${config.http.prefix}/resources/{org}/{project}/events") {
        authorizeFor(project.organizationLabel / project.label, read)(caller) {
          complete(source(s"project=${project.uuid}", offset))
        }
      }
    }
  }

  def routes(org: Organization): Route =
    lastEventId { offset =>
      operationName(s"/${config.http.prefix}/resources/{org}/events") {
        authorizeFor(/ + org.label, read)(caller) {
          complete(source(s"org=${org.uuid}", offset))
        }
      }
    }
}
