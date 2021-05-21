package ch.epfl.bluebrain.nexus.delta.routes

import akka.http.scaladsl.model._
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server._
import cats.implicits._
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.RemoteContextResolution
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.encoder.JsonLdEncoder
import ch.epfl.bluebrain.nexus.delta.rdf.utils.JsonKeyOrdering
import ch.epfl.bluebrain.nexus.delta.sdk.Permissions.{events, resources, projects => projectsPermissions}
import ch.epfl.bluebrain.nexus.delta.sdk.Projects.FetchUuids
import ch.epfl.bluebrain.nexus.delta.sdk.circe.CirceUnmarshalling
import ch.epfl.bluebrain.nexus.delta.sdk.directives.AuthDirectives
import ch.epfl.bluebrain.nexus.delta.sdk.directives.DeltaDirectives._
import ch.epfl.bluebrain.nexus.delta.sdk.marshalling.HttpResponseFields._
import ch.epfl.bluebrain.nexus.delta.sdk.model.acls.AclAddress
import ch.epfl.bluebrain.nexus.delta.sdk.model.acls.AclAddressFilter.AnyOrganizationAnyProject
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Caller
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.ProjectRejection.ProjectNotFound
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.{ApiMappings, Project, ProjectFields, ProjectRejection}
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.SearchParams.ProjectSearchParams
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.SearchResults.searchResultsJsonLdEncoder
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.{PaginationConfig, SearchResults}
import ch.epfl.bluebrain.nexus.delta.sdk.model.BaseUri
import ch.epfl.bluebrain.nexus.delta.sdk.syntax._
import ch.epfl.bluebrain.nexus.delta.sdk._
import kamon.instrumentation.akka.http.TracingDirectives.operationName
import monix.bio.{IO, UIO}
import monix.execution.Scheduler

/**
  * The project routes
  * @param identities the identity module
  * @param acls       the ACLs module
  * @param projects   the projects module
  */
final class ProjectsRoutes(identities: Identities, acls: Acls, projects: Projects, projectsCounts: ProjectsCounts)(
    implicit
    baseUri: BaseUri,
    defaultApiMappings: ApiMappings,
    paginationConfig: PaginationConfig,
    s: Scheduler,
    cr: RemoteContextResolution,
    ordering: JsonKeyOrdering
) extends AuthDirectives(identities, acls)
    with CirceUnmarshalling {

  import baseUri.prefixSegment

  implicit private val fetchProjectUuids: FetchUuids = _ => UIO.none

  private def projectsSearchParams(implicit caller: Caller): Directive1[ProjectSearchParams] =
    (searchParams & parameter("label".?)).tflatMap { case (deprecated, rev, createdBy, updatedBy, label) =>
      onSuccess(acls.listSelf(AnyOrganizationAnyProject(true)).runToFuture).map { aclsCol =>
        ProjectSearchParams(
          None,
          deprecated,
          rev,
          createdBy,
          updatedBy,
          label,
          proj => aclsCol.exists(caller.identities, projectsPermissions.read, proj.ref)
        )
      }
    }

  def routes: Route =
    baseUriPrefix(baseUri.prefix) {
      pathPrefix("projects") {
        extractCaller { implicit caller =>
          concat(
            // List projects
            (get & pathEndOrSingleSlash & extractUri & fromPaginated & projectsSearchParams & sort[Project]) {
              (uri, pagination, params, order) =>
                operationName(s"$prefixSegment/projects") {
                  implicit val searchJsonLdEncoder: JsonLdEncoder[SearchResults[ProjectResource]] =
                    searchResultsJsonLdEncoder(Project.context, pagination, uri)

                  emit(projects.list(pagination, params, order).widen[SearchResults[ProjectResource]])
                }
            },
            // SSE projects
            (pathPrefix("events") & pathEndOrSingleSlash) {
              operationName(s"$prefixSegment/projects/events") {
                authorizeFor(AclAddress.Root, events.read).apply {
                  lastEventId { offset =>
                    emit(projects.events(offset))
                  }
                }
              }
            },
            projectRef(projects).apply { ref =>
              operationName(s"$prefixSegment/projects/{org}/{project}") {
                concat(
                  (put & pathEndOrSingleSlash) {
                    parameter("rev".as[Long].?) {
                      case None      =>
                        // Create project
                        authorizeFor(ref, projectsPermissions.create).apply {
                          entity(as[ProjectFields]) { fields =>
                            emit(StatusCodes.Created, projects.create(ref, fields).mapValue(_.metadata))
                          }
                        }
                      case Some(rev) =>
                        // Update project
                        authorizeFor(ref, projectsPermissions.write).apply {
                          entity(as[ProjectFields]) { fields =>
                            emit(projects.update(ref, rev, fields).mapValue(_.metadata))
                          }
                        }
                    }
                  },
                  (get & pathEndOrSingleSlash) {
                    authorizeFor(ref, projectsPermissions.read).apply {
                      parameter("rev".as[Long].?) {
                        case Some(rev) => // Fetch project at specific revision
                          emit(projects.fetchAt(ref, rev).leftWiden[ProjectRejection])
                        case None      => // Fetch project
                          emit(projects.fetch(ref).leftWiden[ProjectRejection])
                      }
                    }
                  },
                  // Deprecate project
                  (delete & pathEndOrSingleSlash) {
                    authorizeFor(ref, projectsPermissions.write).apply {
                      parameter("rev".as[Long]) { rev => emit(projects.deprecate(ref, rev).mapValue(_.metadata)) }
                    }
                  },
                  // Project statistics
                  (pathPrefix("statistics") & get & pathEndOrSingleSlash) {
                    authorizeFor(ref, resources.read).apply {
                      emit(IO.fromOptionEval(projectsCounts.get(ref), ProjectNotFound(ref)).leftWiden[ProjectRejection])
                    }
                  }
                )
              }
            },
            // list projects for an organization
            (get & label & pathEndOrSingleSlash & extractUri & fromPaginated & projectsSearchParams & sort[Project]) {
              (organization, uri, pagination, params, order) =>
                implicit val searchJsonLdEncoder: JsonLdEncoder[SearchResults[ProjectResource]] =
                  searchResultsJsonLdEncoder(Project.context, pagination, uri)

                val filter = params.copy(organization = Some(organization))
                emit(projects.list(pagination, filter, order).widen[SearchResults[ProjectResource]])
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
  def apply(identities: Identities, acls: Acls, projects: Projects, projectsCounts: ProjectsCounts)(implicit
      baseUri: BaseUri,
      defaultApiMappings: ApiMappings,
      paginationConfig: PaginationConfig,
      s: Scheduler,
      cr: RemoteContextResolution,
      ordering: JsonKeyOrdering
  ): Route = new ProjectsRoutes(identities, acls, projects, projectsCounts).routes

}
