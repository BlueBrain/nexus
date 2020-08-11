package ch.epfl.bluebrain.nexus.admin.routes

import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import ch.epfl.bluebrain.nexus.admin.index.{OrganizationCache, ProjectCache}
import ch.epfl.bluebrain.nexus.admin.organizations.Organizations
import ch.epfl.bluebrain.nexus.admin.projects.Projects
import ch.epfl.bluebrain.nexus.delta.config.AppConfig
import ch.epfl.bluebrain.nexus.delta.config.AppConfig.{HttpConfig, PaginationConfig}
import ch.epfl.bluebrain.nexus.iam.acls.Acls
import ch.epfl.bluebrain.nexus.iam.realms.Realms
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
  )(implicit cfg: AppConfig): Route = {
    implicit val hc: HttpConfig        = cfg.http
    implicit val pgc: PaginationConfig = cfg.pagination

    val orgRoutes     = new OrganizationRoutes(orgs, orgCache, acls, realms).routes
    val projectRoutes = new ProjectRoutes(projects, orgCache, projCache, acls, realms).routes

    pathPrefix(cfg.http.prefix) {
      orgRoutes ~ projectRoutes
    }
  }
}
