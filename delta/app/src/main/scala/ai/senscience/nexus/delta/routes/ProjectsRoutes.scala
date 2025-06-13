package ai.senscience.nexus.delta.routes

import akka.http.scaladsl.model.*
import akka.http.scaladsl.server.*
import cats.data.OptionT
import cats.implicits.*
import ch.epfl.bluebrain.nexus.akka.marshalling.CirceUnmarshalling
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.RemoteContextResolution
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.encoder.JsonLdEncoder
import ch.epfl.bluebrain.nexus.delta.rdf.utils.JsonKeyOrdering
import ch.epfl.bluebrain.nexus.delta.sdk.*
import ch.epfl.bluebrain.nexus.delta.sdk.acls.AclCheck
import ch.epfl.bluebrain.nexus.delta.sdk.directives.AuthDirectives
import ch.epfl.bluebrain.nexus.delta.sdk.directives.DeltaDirectives.*
import ch.epfl.bluebrain.nexus.delta.sdk.fusion.FusionConfig
import ch.epfl.bluebrain.nexus.delta.sdk.identities.Identities
import ch.epfl.bluebrain.nexus.delta.sdk.identities.model.Caller
import ch.epfl.bluebrain.nexus.delta.sdk.implicits.*
import ch.epfl.bluebrain.nexus.delta.sdk.model.BaseUri
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.SearchParams.ProjectSearchParams
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.SearchResults.searchResultsJsonLdEncoder
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.{PaginationConfig, SearchResults}
import ch.epfl.bluebrain.nexus.delta.sdk.permissions.Permissions.projects.{create as CreateProjects, delete as DeleteProjects, read as ReadProjects, write as WriteProjects}
import ch.epfl.bluebrain.nexus.delta.sdk.permissions.Permissions.resources.read as ReadResources
import ch.epfl.bluebrain.nexus.delta.sdk.projects.model.*
import ch.epfl.bluebrain.nexus.delta.sdk.projects.model.ProjectRejection.ProjectNotFound
import ch.epfl.bluebrain.nexus.delta.sdk.projects.{ProjectScopeResolver, Projects, ProjectsConfig, ProjectsStatistics}
import ch.epfl.bluebrain.nexus.delta.sourcing.Scope
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Label

/**
  * The project routes
  */
final class ProjectsRoutes(
    identities: Identities,
    aclCheck: AclCheck,
    projects: Projects,
    projectScopeResolver: ProjectScopeResolver,
    projectsStatistics: ProjectsStatistics
)(implicit
    baseUri: BaseUri,
    config: ProjectsConfig,
    cr: RemoteContextResolution,
    ordering: JsonKeyOrdering,
    fusionConfig: FusionConfig
) extends AuthDirectives(identities, aclCheck)
    with CirceUnmarshalling {

  implicit val paginationConfig: PaginationConfig = config.pagination

  private def projectsSearchParams(org: Option[Label])(implicit caller: Caller): Directive1[ProjectSearchParams] = {
    (searchParams & parameter("label".?)).tmap { case (deprecated, rev, createdBy, updatedBy, label) =>
      val filter = projectScopeResolver.access(org.fold(Scope.root)(Scope.Org), ReadProjects).memoize
      ProjectSearchParams(
        org,
        deprecated,
        rev,
        createdBy,
        updatedBy,
        label,
        proj => filter.flatMap(_.map(_.grant(proj.ref)))
      )
    }
  }

  private def revisionParam: Directive[Tuple1[Int]] = parameter("rev".as[Int])

  def routes: Route =
    baseUriPrefix(baseUri.prefix) {
      pathPrefix("projects") {
        extractCaller { implicit caller =>
          concat(
            // List projects
            (get & pathEndOrSingleSlash & extractHttp4sUri & fromPaginated & projectsSearchParams(None) &
              sort[Project]) { (uri, pagination, params, order) =>
              implicit val searchJsonLdEncoder: JsonLdEncoder[SearchResults[ProjectResource]] =
                searchResultsJsonLdEncoder(Project.context, pagination, uri)

              emit(projects.list(pagination, params, order).widen[SearchResults[ProjectResource]])
            },
            projectRef.apply { project =>
              concat(
                concat(
                  (put & pathEndOrSingleSlash) {
                    parameter("rev".as[Int].?) {
                      case None      =>
                        // Create project
                        authorizeFor(project, CreateProjects).apply {
                          entity(as[ProjectFields]) { fields =>
                            emit(
                              StatusCodes.Created,
                              projects.create(project, fields).mapValue(_.metadata).attemptNarrow[ProjectRejection]
                            )
                          }
                        }
                      case Some(rev) =>
                        // Update project
                        authorizeFor(project, WriteProjects).apply {
                          entity(as[ProjectFields]) { fields =>
                            emit(
                              projects
                                .update(project, rev, fields)
                                .mapValue(_.metadata)
                                .attemptNarrow[ProjectRejection]
                            )
                          }
                        }
                    }
                  },
                  (get & pathEndOrSingleSlash) {
                    parameter("rev".as[Int].?) {
                      case Some(rev) => // Fetch project at specific revision
                        authorizeFor(project, ReadProjects).apply {
                          emit(projects.fetchAt(project, rev).attemptNarrow[ProjectRejection])
                        }
                      case None      => // Fetch project
                        emitOrFusionRedirect(
                          project,
                          authorizeFor(project, ReadProjects).apply {
                            emit(projects.fetch(project).attemptNarrow[ProjectRejection])
                          }
                        )
                    }
                  },
                  // Deprecate/delete project
                  (delete & pathEndOrSingleSlash) {
                    parameters("rev".as[Int], "prune".?(false)) {
                      case (rev, true)  =>
                        authorizeFor(project, DeleteProjects).apply {
                          emit(projects.delete(project, rev).mapValue(_.metadata).attemptNarrow[ProjectRejection])
                        }
                      case (rev, false) =>
                        authorizeFor(project, WriteProjects).apply {
                          emit(projects.deprecate(project, rev).mapValue(_.metadata).attemptNarrow[ProjectRejection])
                        }
                    }
                  }
                ),
                (pathPrefix("undeprecate") & put & revisionParam) { revision =>
                  authorizeFor(project, WriteProjects).apply {
                    emit(projects.undeprecate(project, revision).attemptNarrow[ProjectRejection])
                  }
                },
                // Project statistics
                (pathPrefix("statistics") & get & pathEndOrSingleSlash) {
                  authorizeFor(project, ReadResources).apply {
                    val stats = projectsStatistics.get(project)
                    emit(
                      OptionT(stats).toRight[ProjectRejection](ProjectNotFound(project)).value
                    )
                  }
                }
              )
            },
            // list projects for an organization
            (get & label & pathEndOrSingleSlash) { org =>
              (extractHttp4sUri & fromPaginated & projectsSearchParams(Some(org)) & sort[Project]) {
                (uri, pagination, params, order) =>
                  implicit val searchJsonLdEncoder: JsonLdEncoder[SearchResults[ProjectResource]] =
                    searchResultsJsonLdEncoder(Project.context, pagination, uri)
                  emit(projects.list(pagination, params, order).widen[SearchResults[ProjectResource]])
              }
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
      projectScopeResolver: ProjectScopeResolver,
      projectsStatistics: ProjectsStatistics
  )(implicit
      baseUri: BaseUri,
      config: ProjectsConfig,
      cr: RemoteContextResolution,
      ordering: JsonKeyOrdering,
      fusionConfig: FusionConfig
  ): Route =
    new ProjectsRoutes(identities, aclCheck, projects, projectScopeResolver, projectsStatistics).routes

}
