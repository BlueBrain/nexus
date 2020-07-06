package ch.epfl.bluebrain.nexus.admin.routes

import akka.http.scaladsl.model.StatusCodes.Created
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.{Directive0, Route}
import ch.epfl.bluebrain.nexus.admin.config.Permissions.{projects => pp}
import ch.epfl.bluebrain.nexus.admin.directives.QueryDirectives
import ch.epfl.bluebrain.nexus.admin.directives.PathDirectives._
import ch.epfl.bluebrain.nexus.admin.index.{OrganizationCache, ProjectCache}
import ch.epfl.bluebrain.nexus.admin.projects.{ProjectDescription, Projects}
import ch.epfl.bluebrain.nexus.admin.routes.SearchParams.Field
import ch.epfl.bluebrain.nexus.admin.types.ResourceF._
import ch.epfl.bluebrain.nexus.iam.acls.Acls
import ch.epfl.bluebrain.nexus.iam.realms.Realms
import ch.epfl.bluebrain.nexus.iam.types.Identity.Subject
import kamon.instrumentation.akka.http.TracingDirectives.operationName
import ch.epfl.bluebrain.nexus.rdf.Iri.Path
import ch.epfl.bluebrain.nexus.service.config.AppConfig.{HttpConfig, PaginationConfig}
import ch.epfl.bluebrain.nexus.service.directives.AuthDirectives
import ch.epfl.bluebrain.nexus.service.marshallers.instances._
import monix.eval.Task
import monix.execution.Scheduler

class ProjectRoutes(
    projects: Projects[Task],
    orgCache: OrganizationCache[Task],
    projCache: ProjectCache[Task],
    acls: Acls[Task],
    realms: Realms[Task]
)(implicit
    hc: HttpConfig,
    pagination: PaginationConfig,
    s: Scheduler
) extends AuthDirectives(acls, realms)
    with QueryDirectives {

  implicit private val oc: OrganizationCache[Task] = orgCache
  implicit private val pc: ProjectCache[Task]      = projCache

  def routes: Route =
    (pathPrefix("projects") & extractCaller) { implicit caller =>
      implicit val subject: Subject = caller.subject
      concat(
        // fetch
        (get & project & pathEndOrSingleSlash) {
          case (orgLabel, projectLabel) =>
            traceOne {
              authorizeFor(pathOf(orgLabel, projectLabel), pp.read)(caller) {
                parameter("rev".as[Long].?) {
                  case Some(rev) =>
                    complete(projects.fetch(orgLabel, projectLabel, rev).runToFuture)
                  case None      =>
                    complete(projects.fetch(orgLabel, projectLabel).runNotFound)
                }
              }
            }
        },
        // writes
        (project & pathEndOrSingleSlash) {
          case (orgLabel, projectLabel) =>
            traceOne {
              concat(
                // deprecate
                (delete & parameter("rev".as[Long]) & authorizeFor(pathOf(orgLabel, projectLabel), pp.write)) { rev =>
                  complete(projects.deprecate(orgLabel, projectLabel, rev).runToFuture)
                },
                // update
                (put & parameter("rev".as[Long]) & authorizeFor(pathOf(orgLabel, projectLabel), pp.write)) { rev =>
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
            (put & authorizeFor(pathOf(orgLabel), pp.create)) {
              entity(as[ProjectDescription]) { project =>
                complete(projects.create(orgLabel, projectLabel, project).runWithStatus(Created))
              }
            }
          }
        },
        // list all projects
        (get & pathEndOrSingleSlash & paginated & searchParamsProjects & extractCallerAcls(anyProject)(caller)) {
          (pagination, params, acls) =>
            traceCol {
              complete(projects.list(params, pagination)(acls).runToFuture)
            }
        },
        // list projects in organization
        (get & org & pathEndOrSingleSlash & paginated & searchParamsProjects & extractCallerAcls(anyProject)(caller)) {
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
