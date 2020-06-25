package ch.epfl.bluebrain.nexus.admin.routes

import akka.actor.ActorSystem
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import ch.epfl.bluebrain.nexus.admin.index.{OrganizationCache, ProjectCache}
import ch.epfl.bluebrain.nexus.admin.organizations.Organizations
import ch.epfl.bluebrain.nexus.admin.projects.Projects
import ch.epfl.bluebrain.nexus.iam.acls.Acls
import ch.epfl.bluebrain.nexus.iam.realms.Realms
import ch.epfl.bluebrain.nexus.iam.routes.EventRoutes
import ch.epfl.bluebrain.nexus.service.config.ServiceConfig
import ch.epfl.bluebrain.nexus.service.config.ServiceConfig.{HttpConfig, PaginationConfig, PersistenceConfig}
import monix.eval.Task
import monix.execution.Scheduler.Implicits.global

object AdminRoutes {

  /**
    * Pulls together all service routes and wraps them with CORS, rejection and exception handling.
    *
    * @param orgs     the organizations api bundle
    * @param projects the projects api bundle
    */
  final def apply(
      orgs: Organizations[Task],
      projects: Projects[Task],
      orgCache: OrganizationCache[Task],
      projCache: ProjectCache[Task],
      acls: Acls[Task],
      realms: Realms[Task]
  )(implicit
      as: ActorSystem,
      cfg: ServiceConfig
  ): Route = {
    implicit val hc: HttpConfig        = cfg.http
    implicit val pc: PersistenceConfig = cfg.persistence
    implicit val pgc: PaginationConfig = cfg.pagination

    val eventsRoutes  = new EventRoutes(acls, realms).routes
    val orgRoutes     = new OrganizationRoutes(orgs, orgCache, acls, realms).routes
    val projectRoutes = new ProjectRoutes(projects, orgCache, projCache, acls, realms).routes

    pathPrefix(cfg.http.prefix) {
      eventsRoutes ~ orgRoutes ~ projectRoutes
    }
  }
}
