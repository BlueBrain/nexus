package ch.epfl.bluebrain.nexus.delta.routes

import java.util.UUID

import akka.http.scaladsl.model._
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server._
import cats.implicits._
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.{ContextValue, RemoteContextResolution}
import ch.epfl.bluebrain.nexus.delta.rdf.utils.JsonKeyOrdering
import ch.epfl.bluebrain.nexus.delta.routes.marshalling.CirceUnmarshalling
import ch.epfl.bluebrain.nexus.delta.routes.marshalling.HttpResponseFields._
import ch.epfl.bluebrain.nexus.delta.sdk.Permissions.{events, projects => projectsPermissions}
import ch.epfl.bluebrain.nexus.delta.sdk.directives.AuthDirectives
import ch.epfl.bluebrain.nexus.delta.sdk.error.ServiceError.AuthorizationFailed
import ch.epfl.bluebrain.nexus.delta.sdk.model.acls.AclAddress
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Caller
import ch.epfl.bluebrain.nexus.delta.sdk.model.permissions.Permission
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.ProjectRejection.ProjectNotFound
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.{Project, ProjectFields, ProjectRejection}
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.PaginationConfig
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.SearchParams.ProjectSearchParams
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.SearchResults.{searchResultsEncoder, SearchEncoder}
import ch.epfl.bluebrain.nexus.delta.sdk.model.{BaseUri, Label}
import ch.epfl.bluebrain.nexus.delta.sdk.{Acls, Identities, ProjectResource, Projects}
import kamon.instrumentation.akka.http.TracingDirectives.operationName
import monix.execution.Scheduler

/**
  * The project routes
  * @param identities the identity module
  * @param acls       the ACLs module
  * @param projects   the projects module
  */
final class ProjectsRoutes(identities: Identities, acls: Acls, projects: Projects)(implicit
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

  private def fetchByUUID(orgUuid: UUID, projectUuid: UUID, permission: Permission)(implicit
      caller: Caller
  ): Directive1[ProjectResource] =
    onSuccess(projects.fetch(projectUuid).runToFuture).flatMap {
      case Some(project) =>
        authorizeFor(AclAddress.Project(project.value.ref), permission).tflatMap { _ =>
          if (project.value.organizationUuid == orgUuid)
            provide(project)
          else
            Directive(_ => discardEntityAndComplete[ProjectRejection](ProjectNotFound(orgUuid, projectUuid)))
        }
      case None          => failWith(AuthorizationFailed)
    }

  private def fetchByUUIDAndRev(orgUuid: UUID, projectUuid: UUID, permission: Permission, rev: Long)(implicit
      caller: Caller
  ): Directive1[ProjectResource] =
    onSuccess(projects.fetchAt(projectUuid, rev).leftWiden[ProjectRejection].attempt.runToFuture).flatMap {
      case Right(Some(project)) =>
        authorizeFor(AclAddress.Project(project.value.ref), permission).tflatMap { _ =>
          if (project.value.organizationUuid == orgUuid)
            provide(project)
          else
            Directive(_ => discardEntityAndComplete[ProjectRejection](ProjectNotFound(orgUuid, projectUuid)))
        }
      case Right(None)          => failWith(AuthorizationFailed)
      case Left(r)              => Directive(_ => discardEntityAndComplete(r))
    }

  def routes: Route =
    baseUriPrefix(baseUri.prefix) {
      extractCaller { implicit caller =>
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
                authorizeFor(AclAddress.Root, events.read).apply {
                  lastEventId { offset =>
                    completeStream(projects.events(offset))
                  }
                }
              }
            },
            (projectRef & pathEndOrSingleSlash) { ref =>
              operationName(s"$prefixSegment/projects/{ref}") {
                concat(
                  put {
                    authorizeFor(AclAddress.Project(ref), projectsPermissions.write).apply {
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
                    }
                  },
                  get {
                    authorizeFor(AclAddress.Project(ref), projectsPermissions.read).apply {
                      parameter("rev".as[Long].?) {
                        case Some(rev) => // Fetch project at specific revision
                          completeIOOpt(projects.fetchAt(ref, rev).leftWiden[ProjectRejection])
                        case None      => // Fetch project
                          completeUIOOpt(projects.fetch(ref))
                      }
                    }
                  },
                  // Deprecate project
                  delete {
                    authorizeFor(AclAddress.Project(ref), projectsPermissions.write).apply {
                      parameter("rev".as[Long]) { rev => completeIO(projects.deprecate(ref, rev).map(_.void)) }
                    }
                  }
                )
              }
            },
            (uuid & uuid & pathEndOrSingleSlash) { (orgUuid, projectUuid) =>
              operationName(s"$prefixSegment/project/{orgUuid}/{projectUuid}") {
                get {
                  parameter("rev".as[Long].?) {
                    case Some(rev) => // Fetch project from UUID at specific revision
                      fetchByUUIDAndRev(orgUuid, projectUuid, projectsPermissions.read, rev).apply { project =>
                        completePure(project)
                      }
                    case None      => // Fetch project from UUID
                      fetchByUUID(orgUuid, projectUuid, projectsPermissions.read).apply { project =>
                        completePure(project)
                      }
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
  def apply(identities: Identities, acls: Acls, projects: Projects)(implicit
      baseUri: BaseUri,
      paginationConfig: PaginationConfig,
      s: Scheduler,
      cr: RemoteContextResolution,
      ordering: JsonKeyOrdering
  ): Route = new ProjectsRoutes(identities, acls, projects).routes

}
