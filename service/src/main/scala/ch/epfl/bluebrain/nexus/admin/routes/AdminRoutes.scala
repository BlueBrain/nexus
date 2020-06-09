package ch.epfl.bluebrain.nexus.admin.routes

import akka.actor.ActorSystem
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import ch.epfl.bluebrain.nexus.admin.config.AdminConfig.PaginationConfig
import ch.epfl.bluebrain.nexus.admin.index.{OrganizationCache, ProjectCache}
import ch.epfl.bluebrain.nexus.admin.organizations.Organizations
import ch.epfl.bluebrain.nexus.admin.projects.Projects
import ch.epfl.bluebrain.nexus.iam.client.IamClient
import ch.epfl.bluebrain.nexus.iam.client.config.IamClientConfig
import ch.epfl.bluebrain.nexus.service.config.ServiceConfig
import ch.epfl.bluebrain.nexus.service.config.ServiceConfig.{HttpConfig, PersistenceConfig}
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
      ic: IamClient[Task]
  )(
      implicit as: ActorSystem,
      cfg: ServiceConfig
  ): Route = {
    implicit val hc: HttpConfig        = cfg.http
    implicit val pc: PersistenceConfig = cfg.persistence
    implicit val icc: IamClientConfig  = cfg.admin.iam
    implicit val pgc: PaginationConfig = cfg.admin.pagination

    val eventsRoutes  = EventRoutes(ic).routes
    val orgRoutes     = OrganizationRoutes(orgs, orgCache, ic).routes
    val projectRoutes = ProjectRoutes(projects, orgCache, projCache, ic).routes

    pathPrefix(cfg.http.prefix) {
      eventsRoutes ~ orgRoutes ~ projectRoutes
    }
  }
}
