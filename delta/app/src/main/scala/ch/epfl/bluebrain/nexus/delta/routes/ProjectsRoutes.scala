package ch.epfl.bluebrain.nexus.delta.routes

import akka.http.scaladsl.model._
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server._
import cats.implicits._
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.{ContextValue, RemoteContextResolution}
import ch.epfl.bluebrain.nexus.delta.rdf.utils.JsonKeyOrdering
import ch.epfl.bluebrain.nexus.delta.routes.marshalling.CirceUnmarshalling
import ch.epfl.bluebrain.nexus.delta.sdk.directives.AuthDirectives
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.{Project, ProjectFields, ProjectRejection}
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.PaginationConfig
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.SearchParams.ProjectSearchParams
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.SearchResults.searchResultsEncoder
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.SearchResults.SearchEncoder
import ch.epfl.bluebrain.nexus.delta.sdk.model.{BaseUri, Label}
import ch.epfl.bluebrain.nexus.delta.sdk.{Acls, Identities, ProjectResource, Projects}
import kamon.instrumentation.akka.http.TracingDirectives.operationName
import monix.execution.Scheduler

/**
  * The project routes
  * @param identities the identity module
  * @param projects   the projects module
  * @param acls       the ACLs module
  */
final class ProjectsRoutes(identities: Identities, projects: Projects, acls: Acls)(implicit
    baseUri: BaseUri,
    paginationConfig: PaginationConfig,
    s: Scheduler,
    cr: RemoteContextResolution,
    ordering: JsonKeyOrdering
) extends AuthDirectives(identities, acls)
    with DeltaDirectives
    with CirceUnmarshalling {

  import baseUri.prefixSegment
  implicit val projectContext: ContextValue = Project.context

  private def projectsSearchParams: Directive1[ProjectSearchParams] =
    parameter("label".as[Label].?).flatMap { organization =>
      searchParams.tmap { case (deprecated, rev, createdBy, updatedBy) =>
        ProjectSearchParams(organization, deprecated, rev, createdBy, updatedBy)
      }
    }

  def routes: Route =
    baseUriPrefix(baseUri.prefix) {
      extractSubject { implicit subject =>
        pathPrefix("projects") {
          concat(
            // List projects
            (get & pathEndOrSingleSlash & extractUri & paginated & projectsSearchParams) { (uri, pagination, params) =>
              operationName(s"$prefixSegment/projects") {
                implicit val searchEncoder: SearchEncoder[ProjectResource] = searchResultsEncoder(pagination, uri)
                completeSearch(projects.list(pagination, params))
              }
            },
            // SSE projects
            (pathPrefix("events") & pathEndOrSingleSlash) {
              operationName(s"$prefixSegment/projects/events") {
                lastEventId { offset =>
                  completeStream(projects.events(offset))
                }
              }
            },
            (projectRef & pathEndOrSingleSlash) { ref =>
              operationName(s"$prefixSegment/projects/{ref}") {
                concat(
                  put {
                    parameter("rev".as[Long].?) {
                      case Some(rev) =>
                        // Update project
                        entity(as[ProjectFields]) { fields =>
                          completeIO(projects.update(ref, rev, fields).map(_.void))
                        }
                      case None      =>
                        // Create project
                        entity(as[ProjectFields]) { fields =>
                          completeIO(StatusCodes.Created, projects.create(ref, fields).map(_.void))
                        }
                    }
                  },
                  get {
                    parameter("rev".as[Long].?) {
                      case Some(rev) => // Fetch project at specific revision
                        completeIOOpt(projects.fetchAt(ref, rev).leftMap[ProjectRejection](identity))
                      case None      => // Fetch project
                        completeUIOOpt(projects.fetch(ref))
                    }
                  },
                  // Deprecate project
                  delete {
                    parameter("rev".as[Long]) { rev => completeIO(projects.deprecate(ref, rev).map(_.void)) }
                  }
                )
              }
            },
            (uuid & uuid & pathEndOrSingleSlash) { (orgUuid, projectUuid) =>
              operationName(s"$prefixSegment/project/{orgUuid}/{projectUuid}") {
                get {
                  parameter("rev".as[Long].?) {
                    case Some(rev) => // Fetch project from UUID at specific revision
                      completeIOOpt(projects.fetchAt(orgUuid, projectUuid, rev))
                    case None      => // Fetch project from UUID
                      completeIOOpt(projects.fetch(orgUuid, projectUuid).leftMap[ProjectRejection](identity))
                  }
                }
              }
            }
          )
        }
      }
    }
}

object ProjectsRoutes {

  /**
    * @return the [[Route]] for projects
    */
  def apply(identities: Identities, projects: Projects, acls: Acls)(implicit
      baseUri: BaseUri,
      paginationConfig: PaginationConfig,
      s: Scheduler,
      cr: RemoteContextResolution,
      ordering: JsonKeyOrdering
  ): Route = new ProjectsRoutes(identities, projects, acls).routes

}
