package ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.routes

import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.model.StatusCodes.Created
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server._
import akka.persistence.query.NoOffset
import cats.implicits._
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.indexing.ElasticSearchIndexingCoordinator.ElasticSearchIndexingCoordinator
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.model.ElasticSearchViewRejection._
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.model._
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.routes.ElasticSearchViewsRoutes.responseFieldsElasticSearchRejections
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.{ElasticSearchViews, ElasticSearchViewsQuery}
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.contexts
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.JsonLdContext.keywords
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.{ContextValue, RemoteContextResolution}
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.encoder.JsonLdEncoder
import ch.epfl.bluebrain.nexus.delta.rdf.utils.JsonKeyOrdering
import ch.epfl.bluebrain.nexus.delta.sdk.Projects.FetchProject
import ch.epfl.bluebrain.nexus.delta.sdk._
import ch.epfl.bluebrain.nexus.delta.sdk.circe.CirceUnmarshalling
import ch.epfl.bluebrain.nexus.delta.sdk.directives.AuthDirectives
import ch.epfl.bluebrain.nexus.delta.sdk.directives.DeltaDirectives._
import ch.epfl.bluebrain.nexus.delta.sdk.instances.OffsetInstances._
import ch.epfl.bluebrain.nexus.delta.sdk.marshalling.HttpResponseFields
import ch.epfl.bluebrain.nexus.delta.sdk.marshalling.RdfRejectionHandler._
import ch.epfl.bluebrain.nexus.delta.sdk.model.acls.AclAddress
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Caller
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.ProjectRef
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.ProjectRejection.ProjectNotFound
import ch.epfl.bluebrain.nexus.delta.sdk.model.routes.{JsonSource, Tag, Tags}
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.SearchResults.searchResultsJsonLdEncoder
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.{PaginationConfig, SearchResults}
import ch.epfl.bluebrain.nexus.delta.sdk.model.{permissions => _, _}
import ch.epfl.bluebrain.nexus.delta.sdk.syntax._
import ch.epfl.bluebrain.nexus.delta.sourcing.config.ExternalIndexingConfig
import io.circe.generic.semiauto.deriveEncoder
import io.circe.syntax._
import io.circe.{Encoder, Json, JsonObject}
import kamon.instrumentation.akka.http.TracingDirectives.operationName
import monix.execution.Scheduler

/**
  * The elasticsearch views routes
  *
  * @param identities         the identity module
  * @param acls               the ACLs module
  * @param projects           the projects module
  * @param views              the elasticsearch views operations bundle
  * @param viewsQuery         the elasticsearch views query operations bundle
  * @param progresses         the statistics of the progresses for the elasticsearch views
  * @param coordinator        the elasticsearch indexing coordinator in order to restart a view indexing process triggered by a client
  * @param resourcesToSchemas a collection of root resource segment with their corresponding schema
  */
final class ElasticSearchViewsRoutes(
    identities: Identities,
    acls: Acls,
    projects: Projects,
    views: ElasticSearchViews,
    viewsQuery: ElasticSearchViewsQuery,
    progresses: ProgressesStatistics,
    coordinator: ElasticSearchIndexingCoordinator,
    resourcesToSchemas: ResourceToSchemaMappings
)(implicit
    baseUri: BaseUri,
    paginationConfig: PaginationConfig,
    config: ExternalIndexingConfig,
    s: Scheduler,
    cr: RemoteContextResolution,
    ordering: JsonKeyOrdering
) extends AuthDirectives(identities, acls)
    with CirceUnmarshalling
    with ElasticSearchViewsDirectives {

  import baseUri.prefixSegment
  implicit private val fetchProject: FetchProject                                    = projects.fetchProject[ProjectNotFound]
  implicit private val viewStatisticEncoder: Encoder.AsObject[ProgressStatistics]    =
    deriveEncoder[ProgressStatistics].mapJsonObject(_.add(keywords.tpe, "ViewStatistics".asJson))
  implicit private val viewStatisticJsonLdEncoder: JsonLdEncoder[ProgressStatistics] =
    JsonLdEncoder.computeFromCirce(ContextValue(contexts.statistics))

  def routes: Route =
    baseUriPrefix(baseUri.prefix) {
      extractCaller { implicit caller =>
        concat(viewsRoutes, resourcesListings, genericResourcesRoutes)
      }
    }

  private def viewsRoutes(implicit caller: Caller): Route =
    pathPrefix("views") {
      // TODO: SSE for all view events/all view events in an org and all view events in a project needs to happen
      // using some sort of globalEventExchange for SSEs that includes elasticsearch, blazegraph and composite events
      projectRef(projects).apply { implicit ref =>
        concat(
          (pathEndOrSingleSlash & operationName(s"$prefixSegment/views/{org}/{project}")) {
            // Create an elasticsearch view without id segment
            (post & pathEndOrSingleSlash & noParameter("rev") & entity(as[Json])) { source =>
              authorizeFor(AclAddress.Project(ref), permissions.write).apply {
                emit(Created, views.create(ref, source).mapValue(_.metadata).rejectWhen(decodingFailedOrViewNotFound))
              }
            }
          },
          idSegment { id =>
            concat(
              pathEndOrSingleSlash {
                operationName(s"$prefixSegment/views/{org}/{project}/{id}") {
                  concat(
                    // Create or update an elasticsearch view
                    put {
                      authorizeFor(AclAddress.Project(ref), permissions.write).apply {
                        (parameter("rev".as[Long].?) & pathEndOrSingleSlash & entity(as[Json])) {
                          case (None, source)      =>
                            // Create an elasticsearch view with id segment
                            emit(
                              Created,
                              views
                                .create(id, ref, source)
                                .mapValue(_.metadata)
                                .rejectWhen(decodingFailedOrViewNotFound)
                            )
                          case (Some(rev), source) =>
                            // Update a view
                            emit(
                              views
                                .update(id, ref, rev, source)
                                .mapValue(_.metadata)
                                .rejectWhen(decodingFailedOrViewNotFound)
                            )
                        }
                      }
                    },
                    // Deprecate an elasticsearch view
                    (delete & parameter("rev".as[Long])) { rev =>
                      authorizeFor(AclAddress.Project(ref), permissions.write).apply {
                        emit(
                          views.deprecate(id, ref, rev).mapValue(_.metadata).rejectWhen(decodingFailedOrViewNotFound)
                        )
                      }
                    },
                    // Fetch an elasticsearch view
                    get {
                      fetch(id, ref)
                    }
                  )
                }
              },
              // Fetch an elasticsearch view statistics
              (pathPrefix("statistics") & get & pathEndOrSingleSlash) {
                operationName(s"$prefixSegment/views/{org}/{project}/{id}/statistics") {
                  authorizeFor(AclAddress.Project(ref), permissions.read).apply {
                    emit(
                      views
                        .fetchIndexingView(id, ref)
                        .flatMap(v => progresses.statistics(ref, v.projectionId))
                        .rejectWhen(decodingFailedOrViewNotFound)
                    )
                  }
                }
              },
              // Manage an elasticsearch view offset
              (pathPrefix("offset") & pathEndOrSingleSlash) {
                operationName(s"$prefixSegment/views/{org}/{project}/{id}/offset") {
                  concat(
                    // Fetch an elasticsearch view offset
                    (get & authorizeFor(AclAddress.Project(ref), permissions.read)) {
                      emit(
                        views
                          .fetchIndexingView(id, ref)
                          .flatMap(v => progresses.offset(v.projectionId))
                          .rejectWhen(decodingFailedOrViewNotFound)
                      )
                    },
                    // Remove an elasticsearch view offset (restart the view)
                    (delete & authorizeFor(AclAddress.Project(ref), permissions.write)) {
                      emit(
                        views
                          .fetchIndexingView(id, ref)
                          .flatMap(coordinator.restart)
                          .as(NoOffset)
                          .rejectWhen(decodingFailedOrViewNotFound)
                      )
                    }
                  )
                }
              },
              // Query an elasticsearch view
              (pathPrefix("_search") & post & pathEndOrSingleSlash) {
                operationName(s"$prefixSegment/views/{org}/{project}/{id}/_search") {
                  (extractQueryParams & sortList & entity(as[JsonObject])) { (qp, sort, query) =>
                    emit(viewsQuery.query(id, ref, query, qp, sort))
                  }
                }
              },
              // Fetch an elasticsearch view original source
              (pathPrefix("source") & get & pathEndOrSingleSlash) {
                operationName(s"$prefixSegment/views/{org}/{project}/{id}/source") {
                  fetchMap(id, ref, resource => JsonSource(resource.value.source, resource.value.id))
                }
              },
              (pathPrefix("tags") & pathEndOrSingleSlash) {
                operationName(s"$prefixSegment/views/{org}/{project}/{id}/tags") {
                  concat(
                    // Fetch an elasticsearch view tags
                    get {
                      fetchMap(id, ref, resource => Tags(resource.value.tags))
                    },
                    // Tag an elasticsearch view
                    (post & parameter("rev".as[Long])) { rev =>
                      authorizeFor(AclAddress.Project(ref), permissions.write).apply {
                        entity(as[Tag]) { case Tag(tagRev, tag) =>
                          emit(
                            Created,
                            views
                              .tag(id, ref, tag, tagRev, rev)
                              .mapValue(_.metadata)
                              .rejectWhen(decodingFailedOrViewNotFound)
                          )
                        }
                      }
                    }
                  )
                }
              }
            )
          }
        )
      }
    }

  private def genericResourcesRoutes(implicit caller: Caller): Route =
    pathPrefix("resources") {
      projectRef(projects).apply { implicit ref =>
        concat(
          // List all resources
          (pathEndOrSingleSlash & operationName(s"$prefixSegment/resources/{org}/{project}")) {
            list()
          },
          idSegment { schema =>
            // List all resources filtering by its schema type
            (pathEndOrSingleSlash & operationName(s"$prefixSegment/resources/{org}/{project}/{schema}")) {
              list(underscoreToOption(schema))
            }
          }
        )
      }
    }

  private def resourcesListings(implicit caller: Caller): Route =
    concat(resourcesToSchemas.value.map { case (Label(resourceSegment), resourceSchema) =>
      pathPrefix(resourceSegment) {
        projectRef(projects).apply { implicit ref =>
          // List all resource of type resourceSegment
          (pathEndOrSingleSlash & operationName(s"$prefixSegment/$resourceSegment/{org}/{project}")) {
            list(resourceSchema)
          }
        }
      }
    }.toSeq: _*)

  private def fetch(id: IdSegment, ref: ProjectRef)(implicit caller: Caller) =
    fetchMap(id, ref, identity)

  private def fetchMap[A: JsonLdEncoder](
      id: IdSegment,
      ref: ProjectRef,
      f: ViewResource => A
  )(implicit caller: Caller) =
    authorizeFor(AclAddress.Project(ref), permissions.read).apply {
      (parameter("rev".as[Long].?) & parameter("tag".as[TagLabel].?)) {
        case (Some(_), Some(_)) => emit(simultaneousTagAndRevRejection)
        case (Some(rev), _)     => emit(views.fetchAt(id, ref, rev).map(f).rejectOn[ViewNotFound])
        case (_, Some(tag))     => emit(views.fetchBy(id, ref, tag).map(f).rejectOn[ViewNotFound])
        case _                  => emit(views.fetch(id, ref).map(f).rejectOn[ViewNotFound])
      }
    }

  private def list(segment: IdSegment)(implicit ref: ProjectRef, caller: Caller): Route =
    list(Some(segment))

  private def list()(implicit ref: ProjectRef, caller: Caller): Route =
    list(None)

  private def list(schemaSegment: Option[IdSegment])(implicit ref: ProjectRef, caller: Caller): Route =
    (get & searchParametersAndSortList & extractQueryParams & paginated & extractUri) { (params, sort, qp, page, uri) =>
      authorizeFor(AclAddress.Project(ref), permissions.read).apply {

        implicit val searchJsonLdEncoder: JsonLdEncoder[SearchResults[JsonObject]] =
          searchResultsJsonLdEncoder(ContextValue(Vocabulary.contexts.metadataAggregate), page, uri)

        schemaSegment match {
          case Some(segment) => emit(viewsQuery.list(ref, segment, page, params, qp, sort))
          case None          => emit(viewsQuery.list(ref, page, params, qp, sort))
        }
      }
    }

  private val decodingFailedOrViewNotFound: PartialFunction[ElasticSearchViewRejection, Boolean] = {
    case _: DecodingFailed | _: ViewNotFound => true
  }
}

object ElasticSearchViewsRoutes {

  /**
    * @return the [[Route]] for elasticsearch views
    */
  def apply(
      identities: Identities,
      acls: Acls,
      projects: Projects,
      views: ElasticSearchViews,
      viewsQuery: ElasticSearchViewsQuery,
      progresses: ProgressesStatistics,
      coordinator: ElasticSearchIndexingCoordinator,
      resourcesToSchemas: ResourceToSchemaMappings
  )(implicit
      baseUri: BaseUri,
      paginationConfig: PaginationConfig,
      config: ExternalIndexingConfig,
      s: Scheduler,
      cr: RemoteContextResolution,
      ordering: JsonKeyOrdering
  ): Route =
    new ElasticSearchViewsRoutes(
      identities,
      acls,
      projects,
      views,
      viewsQuery,
      progresses,
      coordinator,
      resourcesToSchemas
    ).routes

  implicit val responseFieldsElasticSearchRejections: HttpResponseFields[ElasticSearchViewRejection] =
    HttpResponseFields {
      case RevisionNotFound(_, _)                 => StatusCodes.NotFound
      case TagNotFound(_)                         => StatusCodes.NotFound
      case ViewNotFound(_, _)                     => StatusCodes.NotFound
      case ViewAlreadyExists(_, _)                => StatusCodes.Conflict
      case IncorrectRev(_, _)                     => StatusCodes.Conflict
      case WrappedProjectRejection(rej)           => rej.status
      case AuthorizationFailed                    => StatusCodes.Forbidden
      case UnexpectedInitialState(_, _)           => StatusCodes.InternalServerError
      case ElasticSearchViewEvaluationError(_)    => StatusCodes.InternalServerError
      case WrappedElasticSearchClientError(error) => error.errorCode.getOrElse(StatusCodes.InternalServerError)
      case _                                      => StatusCodes.BadRequest
    }

}
