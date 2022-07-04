package ch.epfl.bluebrain.nexus.delta.routes

import akka.http.scaladsl.model._
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server._
import cats.implicits._
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.RemoteContextResolution
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.encoder.JsonLdEncoder
import ch.epfl.bluebrain.nexus.delta.rdf.utils.JsonKeyOrdering
import ch.epfl.bluebrain.nexus.delta.sdk._
import ch.epfl.bluebrain.nexus.delta.sdk.acls.AclCheck
import ch.epfl.bluebrain.nexus.delta.sdk.acls.model.AclAddress
import ch.epfl.bluebrain.nexus.delta.sdk.circe.CirceUnmarshalling
import ch.epfl.bluebrain.nexus.delta.sdk.directives.AuthDirectives
import ch.epfl.bluebrain.nexus.delta.sdk.directives.DeltaDirectives._
import ch.epfl.bluebrain.nexus.delta.sdk.fusion.FusionConfig
import ch.epfl.bluebrain.nexus.delta.sdk.identities.Identities
import ch.epfl.bluebrain.nexus.delta.sdk.identities.model.Caller
import ch.epfl.bluebrain.nexus.delta.sdk.implicits._
import ch.epfl.bluebrain.nexus.delta.sdk.model.BaseUri
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.SearchParams.ProjectSearchParams
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.SearchResults.searchResultsJsonLdEncoder
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.{PaginationConfig, SearchResults}
import ch.epfl.bluebrain.nexus.delta.sdk.permissions.Permissions.{events, projects => projectsPermissions, resources}
import ch.epfl.bluebrain.nexus.delta.sdk.projects.model.ProjectRejection.ProjectNotFound
import ch.epfl.bluebrain.nexus.delta.sdk.projects.model._
import ch.epfl.bluebrain.nexus.delta.sdk.projects.{Projects, ProjectsConfig, ProjectsStatistics}
import ch.epfl.bluebrain.nexus.delta.sdk.provisioning.ProjectProvisioning
import ch.epfl.bluebrain.nexus.delta.sdk.sse.SseConverter
import kamon.instrumentation.akka.http.TracingDirectives.operationName
import monix.bio.IO
import monix.execution.Scheduler

/**
  * The project routes
  * @param identities
  *   the identity module
  * @param aclCheck
  *   verify the acls for users
  * @param projects
  *   the projects module
  */
final class ProjectsRoutes(
    identities: Identities,
    aclCheck: AclCheck,
    projects: Projects,
    projectsStatistics: ProjectsStatistics,
    projectProvisioning: ProjectProvisioning
)(implicit
    baseUri: BaseUri,
    config: ProjectsConfig,
    s: Scheduler,
    cr: RemoteContextResolution,
    ordering: JsonKeyOrdering,
    fusionConfig: FusionConfig
) extends AuthDirectives(identities, aclCheck)
    with CirceUnmarshalling {

  import baseUri.prefixSegment

  implicit val sseConverter: SseConverter[ProjectEvent] = SseConverter(ProjectEvent.sseEncoder)

  implicit val paginationConfig: PaginationConfig = config.pagination

  private def projectsSearchParams(implicit caller: Caller): Directive1[ProjectSearchParams] =
    (searchParams & parameter("label".?)).tmap { case (deprecated, rev, createdBy, updatedBy, label) =>
      val fetchAllCached = aclCheck.fetchAll.memoizeOnSuccess
      ProjectSearchParams(
        None,
        deprecated,
        rev,
        createdBy,
        updatedBy,
        label,
        proj => aclCheck.authorizeFor(proj.ref, projectsPermissions.read, fetchAllCached)
      )
    }

  private def provisionProject(implicit caller: Caller): Directive0 = onSuccess(
    projectProvisioning(caller.subject).runToFuture
  )

  def routes: Route =
    baseUriPrefix(baseUri.prefix) {
      pathPrefix("projects") {
        extractCaller { implicit caller =>
          concat(
            // List projects
            (get & pathEndOrSingleSlash & extractUri & fromPaginated & provisionProject & projectsSearchParams &
              sort[Project]) { (uri, pagination, params, order) =>
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
                  lastEventIdNew { offset =>
                    emit(projects.events(offset))
                  }
                }
              }
            },
            projectRef(projects).apply { ref =>
              concat(
                operationName(s"$prefixSegment/projects/{org}/{project}") {
                  concat(
                    (put & pathEndOrSingleSlash) {
                      parameter("rev".as[Int].?) {
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
                      parameter("rev".as[Int].?) {
                        case Some(rev) => // Fetch project at specific revision
                          authorizeFor(ref, projectsPermissions.read).apply {
                            emit(projects.fetchAt(ref, rev).leftWiden[ProjectRejection])
                          }
                        case None      => // Fetch project
                          emitOrFusionRedirect(
                            ref,
                            authorizeFor(ref, projectsPermissions.read).apply {
                              emit(projects.fetch(ref).leftWiden[ProjectRejection])
                            }
                          )
                      }
                    },
                    // Deprecate/delete project
                    (delete & pathEndOrSingleSlash) {
                      parameters("rev".as[Int]) { rev =>
                        authorizeFor(ref, projectsPermissions.write).apply {
                          emit(projects.deprecate(ref, rev).mapValue(_.metadata))
                        }
                      }
                    }
                  )
                },
                operationName(s"$prefixSegment/projects/{org}/{project}/statistics") {
                  // Project statistics
                  (pathPrefix("statistics") & get & pathEndOrSingleSlash) {
                    authorizeFor(ref, resources.read).apply {
                      emit(
                        IO.fromOptionEval(projectsStatistics.get(ref), ProjectNotFound(ref)).leftWiden[ProjectRejection]
                      )
                    }
                  }
                }
              )
            },
            // list projects for an organization
            (get & label & pathEndOrSingleSlash & extractUri & fromPaginated & provisionProject & projectsSearchParams &
              sort[Project]) { (organization, uri, pagination, params, order) =>
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
    * @return
    *   the [[Route]] for projects
    */
  def apply(
      identities: Identities,
      aclCheck: AclCheck,
      projects: Projects,
      projectsStatistics: ProjectsStatistics,
      projectProvisioning: ProjectProvisioning
  )(implicit
      baseUri: BaseUri,
      config: ProjectsConfig,
      s: Scheduler,
      cr: RemoteContextResolution,
      ordering: JsonKeyOrdering,
      fusionConfig: FusionConfig
  ): Route = new ProjectsRoutes(identities, aclCheck, projects, projectsStatistics, projectProvisioning).routes

}
