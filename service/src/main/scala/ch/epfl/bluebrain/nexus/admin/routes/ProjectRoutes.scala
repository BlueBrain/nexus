package ch.epfl.bluebrain.nexus.admin.routes

import akka.http.scaladsl.model.StatusCodes.Created
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.{Directive0, Route}
import ch.epfl.bluebrain.nexus.admin.config.AppConfig.{HttpConfig, PaginationConfig}
import ch.epfl.bluebrain.nexus.admin.config.Permissions.{projects => pp}
import ch.epfl.bluebrain.nexus.admin.directives.{AuthDirectives, QueryDirectives}
import ch.epfl.bluebrain.nexus.admin.directives.PathDirectives._
import ch.epfl.bluebrain.nexus.admin.index.{OrganizationCache, ProjectCache}
import ch.epfl.bluebrain.nexus.admin.marshallers.instances._
import ch.epfl.bluebrain.nexus.admin.projects.{ProjectDescription, Projects}
import ch.epfl.bluebrain.nexus.admin.routes.SearchParams.Field
import ch.epfl.bluebrain.nexus.admin.types.ResourceF._
import ch.epfl.bluebrain.nexus.iam.client.IamClient
import ch.epfl.bluebrain.nexus.iam.client.config.IamClientConfig
import kamon.instrumentation.akka.http.TracingDirectives.operationName
import ch.epfl.bluebrain.nexus.rdf.Iri.Path
import monix.eval.Task
import monix.execution.Scheduler

class ProjectRoutes(projects: Projects[Task])(
    implicit ic: IamClient[Task],
    orgCache: OrganizationCache[Task],
    projCache: ProjectCache[Task],
    icc: IamClientConfig,
    hc: HttpConfig,
    pagination: PaginationConfig,
    s: Scheduler
) extends AuthDirectives(ic)
    with QueryDirectives {

  def routes: Route = (pathPrefix("projects") & extractToken) { implicit token =>
    concat(
      // fetch
      (get & project & pathEndOrSingleSlash) {
        case (orgLabel, projectLabel) =>
          traceOne {
            authorizeOn(pathOf(orgLabel, projectLabel), pp.read).apply {
              parameter("rev".as[Long].?) {
                case Some(rev) =>
                  complete(projects.fetch(orgLabel, projectLabel, rev).runToFuture)
                case None =>
                  complete(projects.fetch(orgLabel, projectLabel).runNotFound)
              }
            }
          }
      },
      // writes
      extractSubject.apply { implicit subject =>
        concat(
          (project & pathEndOrSingleSlash) {
            case (orgLabel, projectLabel) =>
              traceOne {
                concat(
                  // deprecate
                  (delete & parameter("rev".as[Long]) & authorizeOn(pathOf(orgLabel, projectLabel), pp.write)) { rev =>
                    complete(projects.deprecate(orgLabel, projectLabel, rev).runToFuture)
                  },
                  // update
                  (put & parameter("rev".as[Long]) & authorizeOn(pathOf(orgLabel, projectLabel), pp.write)) { rev =>
                    entity(as[ProjectDescription]) { project =>
                      complete(projects.update(orgLabel, projectLabel, project, rev).runToFuture)
                    }
                  }
                )
              }
          },
          // create
          (pathPrefix(Segment / Segment) & pathEndOrSingleSlash) { (orgLabel, projectLabel) =>
            traceOne {
              (put & authorizeOn(pathOf(orgLabel), pp.create)) {
                entity(as[ProjectDescription]) { project =>
                  complete(projects.create(orgLabel, projectLabel, project).runWithStatus(Created))
                }
              }
            }
          }
        )

      },
      // list all projects
      (get & pathEndOrSingleSlash & paginated & searchParamsProjects & extractCallerAcls(anyProject)) {
        (pagination, params, acls) =>
          traceCol {
            complete(projects.list(params, pagination)(acls).runToFuture)
          }
      },
      // list projects in organization
      (get & org & pathEndOrSingleSlash & paginated & searchParamsProjects & extractCallerAcls(anyProject)) {
        (orgLabel, pagination, params, acls) =>
          traceOrgCol {
            val orgField = Some(Field(orgLabel, exactMatch = true))
            complete(projects.list(params.copy(organizationLabel = orgField), pagination)(acls).runToFuture)
          }
      }
    )
  }

  private def pathOf(orgLabel: String): Path = {
    import ch.epfl.bluebrain.nexus.rdf.Iri.Path._
    Segment(orgLabel, Path./)
  }

  private def pathOf(orgLabel: String, projectLabel: String): Path = {
    import ch.epfl.bluebrain.nexus.rdf.Iri.Path._
    orgLabel / projectLabel
  }

  private def traceCol: Directive0 =
    operationName(s"/${hc.prefix}/projects")

  private def traceOrgCol: Directive0 =
    operationName(s"/${hc.prefix}/projects/{}")

  private def traceOne: Directive0 =
    operationName(s"/${hc.prefix}/projects/{}/{}")
}

object ProjectRoutes {
  def apply(projects: Projects[Task])(
      implicit ic: IamClient[Task],
      orgCache: OrganizationCache[Task],
      projCache: ProjectCache[Task],
      icc: IamClientConfig,
      hc: HttpConfig,
      pagination: PaginationConfig,
      s: Scheduler
  ): ProjectRoutes =
    new ProjectRoutes(projects)
}
