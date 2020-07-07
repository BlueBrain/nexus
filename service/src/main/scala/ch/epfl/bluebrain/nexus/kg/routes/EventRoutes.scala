package ch.epfl.bluebrain.nexus.kg.routes

import akka.actor.ActorSystem
import akka.http.scaladsl.marshalling.sse.EventStreamMarshalling._
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import ch.epfl.bluebrain.nexus.admin.organizations.OrganizationResource
import ch.epfl.bluebrain.nexus.admin.projects.ProjectResource
import ch.epfl.bluebrain.nexus.iam.acls.Acls
import ch.epfl.bluebrain.nexus.iam.realms.Realms
import ch.epfl.bluebrain.nexus.iam.types.Caller
import ch.epfl.bluebrain.nexus.kg.resources.Event.JsonLd._
import ch.epfl.bluebrain.nexus.rdf.Iri.Path._
import ch.epfl.bluebrain.nexus.service.config.AppConfig
import ch.epfl.bluebrain.nexus.service.config.Permissions.resources
import ch.epfl.bluebrain.nexus.service.directives.AuthDirectives
import kamon.instrumentation.akka.http.TracingDirectives.operationName
import monix.eval.Task
import monix.execution.Scheduler.Implicits.global

class EventRoutes(acls: Acls[Task], realms: Realms[Task], caller: Caller)(implicit
    override val as: ActorSystem,
    override val config: AppConfig
) extends AuthDirectives(acls, realms)(config.http, global)
    with EventCommonRoutes {

  def projectRoutes(project: ProjectResource): Route = {

    lastEventId { offset =>
      operationName(s"/${config.http.prefix}/resources/{org}/{project}/events") {
        authorizeFor(project.value.path, resources.read)(caller) {
          complete(source(s"project=${project.uuid}", offset))
        }
      }
    }
  }

  def organizationRoutes(org: OrganizationResource): Route =
    lastEventId { offset =>
      operationName(s"/${config.http.prefix}/resources/{org}/events") {
        authorizeFor(/ + org.value.label, resources.read)(caller) {
          complete(source(s"org=${org.uuid}", offset))
        }
      }
    }
}
