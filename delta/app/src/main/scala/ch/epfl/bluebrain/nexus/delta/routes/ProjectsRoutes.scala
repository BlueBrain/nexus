package ch.epfl.bluebrain.nexus.delta.routes

import akka.http.scaladsl.model._
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.{Directive1, _}
import cats.implicits._
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.{ContextValue, RemoteContextResolution}
import ch.epfl.bluebrain.nexus.delta.rdf.utils.JsonKeyOrdering
import ch.epfl.bluebrain.nexus.delta.routes.marshalling.CirceUnmarshalling
import ch.epfl.bluebrain.nexus.delta.sdk.directives.AuthDirectives
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.ProjectRejection.ProjectNotFound
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.{Project, ProjectFields, ProjectRef, ProjectRejection}
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.PaginationConfig
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.SearchParams.ProjectSearchParams
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.SearchResults.{searchResourceEncoder, SearchEncoder}
import ch.epfl.bluebrain.nexus.delta.sdk.model.{BaseUri, Label}
import ch.epfl.bluebrain.nexus.delta.sdk.syntax._
import ch.epfl.bluebrain.nexus.delta.sdk.{Identities, Lens, ProjectResource, Projects}
import kamon.instrumentation.akka.http.TracingDirectives.operationName
import monix.bio.IO
import monix.execution.Scheduler

/**
  * The project routes
  * @param identities the identity module
  * @param projects   the projects module
  */
final class ProjectsRoutes private (identities: Identities, projects: Projects)(implicit
    baseUri: BaseUri,
    paginationConfig: PaginationConfig,
    s: Scheduler,
    cr: RemoteContextResolution,
    ordering: JsonKeyOrdering
) extends AuthDirectives(identities)
    with DeltaDirectives
    with CirceUnmarshalling {

  import baseUri._

  private val projectsIri = endpoint.toIri / "projects"

  implicit val iriResolver: Lens[ProjectRef, Iri] = (ref: ProjectRef) =>
    projectsIri / ref.organization.value / ref.project.value
  implicit val projectContext: ContextValue       = Project.context

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
            (get & extractUri & paginated & projectsSearchParams & pathEndOrSingleSlash) { (uri, pagination, params) =>
              operationName(s"$prefixSegment/projects") {
                implicit val searchEncoder: SearchEncoder[ProjectResource] = searchResourceEncoder(pagination, uri)
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
                  def checkOrg(p: Option[ProjectResource]): IO[ProjectRejection, Option[ProjectResource]] =
                    if (p.exists(_.value.organizationUuid == orgUuid))
                      IO.pure(p)
                    else
                      IO.raiseError(ProjectNotFound(orgUuid, projectUuid))

                  parameter("rev".as[Long].?) {
                    case Some(rev) => // Fetch project from UUID at specific revision
                      completeIOOpt(projects.fetchAt(projectUuid, rev).flatMap(checkOrg))
                    case None      => // Fetch project from UUID
                      completeIOOpt(projects.fetch(projectUuid).flatMap(checkOrg))
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
  def apply(identities: Identities, projects: Projects)(implicit
      baseUri: BaseUri,
      paginationConfig: PaginationConfig,
      s: Scheduler,
      cr: RemoteContextResolution,
      ordering: JsonKeyOrdering
  ): Route = new ProjectsRoutes(identities, projects).routes

}
