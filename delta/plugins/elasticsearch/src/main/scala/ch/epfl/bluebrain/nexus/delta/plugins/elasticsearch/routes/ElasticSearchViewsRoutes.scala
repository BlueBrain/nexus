package ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.routes

import akka.http.scaladsl.model.StatusCodes.Created
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server._
import akka.persistence.query.NoOffset
import cats.syntax.all._
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.model.ElasticSearchViewRejection._
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.model._
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.{ElasticSearchViews, ElasticSearchViewsQuery}
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.contexts
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.JsonLdContext.keywords
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.{ContextValue, RemoteContextResolution}
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.encoder.JsonLdEncoder
import ch.epfl.bluebrain.nexus.delta.rdf.utils.JsonKeyOrdering
import ch.epfl.bluebrain.nexus.delta.sdk.Permissions.events
import ch.epfl.bluebrain.nexus.delta.sdk.Projects.FetchProject
import ch.epfl.bluebrain.nexus.delta.sdk._
import ch.epfl.bluebrain.nexus.delta.sdk.circe.CirceUnmarshalling
import ch.epfl.bluebrain.nexus.delta.sdk.directives.AuthDirectives
import ch.epfl.bluebrain.nexus.delta.sdk.directives.DeltaDirectives._
import ch.epfl.bluebrain.nexus.delta.sdk.instances.OffsetInstances._
import ch.epfl.bluebrain.nexus.delta.sdk.marshalling.RdfRejectionHandler._
import ch.epfl.bluebrain.nexus.delta.sdk.model.IdSegment.StringSegment
import ch.epfl.bluebrain.nexus.delta.sdk.model.acls.AclAddress
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Caller
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.ProjectRef
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.ProjectRejection.ProjectNotFound
import ch.epfl.bluebrain.nexus.delta.sdk.model.routes.{JsonSource, Tag, Tags}
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.SearchResults.searchResultsJsonLdEncoder
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.{PaginationConfig, SearchResults}
import ch.epfl.bluebrain.nexus.delta.sdk.model.{permissions => _, _}
import ch.epfl.bluebrain.nexus.delta.sdk.syntax._
import io.circe.generic.semiauto.deriveEncoder
import io.circe.syntax._
import io.circe.{Encoder, Json, JsonObject}
import kamon.instrumentation.akka.http.TracingDirectives.operationName
import monix.bio.UIO
import monix.execution.Scheduler

/**
  * The elasticsearch views routes
  *
  * @param identities         the identity module
  * @param acls               the ACLs module
  * @param orgs               the organizations module
  * @param projects           the projects module
  * @param views              the elasticsearch views operations bundle
  * @param viewsQuery         the elasticsearch views query operations bundle
  * @param progresses         the statistics of the progresses for the elasticsearch views
  * @param restartView          the action to restart a view indexing process triggered by a client
  * @param resourcesToSchemas a collection of root resource segment with their corresponding schema
  * @param sseEventLog        the global eventLog of all view events
  */
final class ElasticSearchViewsRoutes(
    identities: Identities,
    acls: Acls,
    orgs: Organizations,
    projects: Projects,
    views: ElasticSearchViews,
    viewsQuery: ElasticSearchViewsQuery,
    progresses: ProgressesStatistics,
    restartView: (Iri, ProjectRef) => UIO[Unit],
    resourcesToSchemas: ResourceToSchemaMappings,
    sseEventLog: SseEventLog
)(implicit
    baseUri: BaseUri,
    paginationConfig: PaginationConfig,
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
    (baseUriPrefix(baseUri.prefix) & replaceUriOnUnderscore("views")) {
      extractCaller { implicit caller =>
        concat(viewsRoutes, resourcesListings, genericResourcesRoutes)
      }
    }

  private def viewsRoutes(implicit caller: Caller): Route =
    pathPrefix("views") {
      concat(
        // SSE views for all events
        (pathPrefix("events") & pathEndOrSingleSlash) {
          get {
            operationName(s"$prefixSegment/views/events") {
              authorizeFor(AclAddress.Root, events.read).apply {
                lastEventId { offset =>
                  emit(sseEventLog.stream(offset))
                }
              }
            }
          }
        },
        // SSE views for all events belonging to an organization
        (orgLabel(orgs) & pathPrefix("events") & pathEndOrSingleSlash) { org =>
          get {
            operationName(s"$prefixSegment/views/{org}/events") {
              authorizeFor(AclAddress.Organization(org), events.read).apply {
                lastEventId { offset =>
                  emit(sseEventLog.stream(org, offset).leftWiden[ElasticSearchViewRejection])
                }
              }
            }
          }
        },
        projectRef(projects).apply { ref =>
          concat(
            // SSE views for all events belonging to a project
            (pathPrefix("events") & pathEndOrSingleSlash) {
              get {
                operationName(s"$prefixSegment/views/{org}/{project}/events") {
                  authorizeFor(AclAddress.Project(ref), events.read).apply {
                    lastEventId { offset =>
                      emit(sseEventLog.stream(ref, offset).leftWiden[ElasticSearchViewRejection])
                    }
                  }
                }
              }
            },
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
                          .flatMap(v => progresses.statistics(ref, ElasticSearchViews.projectionId(v)))
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
                            .flatMap(v => progresses.offset(ElasticSearchViews.projectionId(v)))
                            .rejectWhen(decodingFailedOrViewNotFound)
                        )
                      },
                      // Remove an elasticsearch view offset (restart the view)
                      (delete & authorizeFor(AclAddress.Project(ref), permissions.write)) {
                        emit(
                          views
                            .fetchIndexingView(id, ref)
                            .flatMap { v => restartView(v.id, v.value.project) }
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
      )
    }

  private def genericResourcesRoutes(implicit caller: Caller): Route =
    pathPrefix("resources") {
      projectRef(projects).apply {
        case ProjectRef(_, project) if project.value == "events" => reject()
        case ref                                                 =>
          concat(
            // List all resources
            (pathEndOrSingleSlash & operationName(s"$prefixSegment/resources/{org}/{project}")) {
              list(ref)
            },
            idSegment {
              case StringSegment("events") => reject()
              case schema                  =>
                // List all resources filtering by its schema type
                (pathEndOrSingleSlash & operationName(s"$prefixSegment/resources/{org}/{project}/{schema}")) {
                  list(ref, underscoreToOption(schema))
                }
            }
          )
      }
    }

  private def resourcesListings(implicit caller: Caller): Route =
    concat(resourcesToSchemas.value.map { case (Label(resourceSegment), resourceSchema) =>
      pathPrefix(resourceSegment) {
        projectRef(projects).apply {
          case ProjectRef(_, project) if project.value == "events" => reject()
          case ref                                                 =>
            // List all resource of type resourceSegment
            (pathEndOrSingleSlash & operationName(s"$prefixSegment/$resourceSegment/{org}/{project}")) {
              list(ref, resourceSchema)
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

  private def list(ref: ProjectRef, segment: IdSegment)(implicit caller: Caller): Route =
    list(ref, Some(segment))

  private def list(ref: ProjectRef)(implicit caller: Caller): Route =
    list(ref, None)

  private def list(ref: ProjectRef, schemaSegment: Option[IdSegment])(implicit caller: Caller): Route = {
    implicit val r: ProjectRef = ref
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
      orgs: Organizations,
      projects: Projects,
      views: ElasticSearchViews,
      viewsQuery: ElasticSearchViewsQuery,
      progresses: ProgressesStatistics,
      restartView: (Iri, ProjectRef) => UIO[Unit],
      resourcesToSchemas: ResourceToSchemaMappings,
      sseEventLog: SseEventLog
  )(implicit
      baseUri: BaseUri,
      paginationConfig: PaginationConfig,
      s: Scheduler,
      cr: RemoteContextResolution,
      ordering: JsonKeyOrdering
  ): Route =
    new ElasticSearchViewsRoutes(
      identities,
      acls,
      orgs,
      projects,
      views,
      viewsQuery,
      progresses,
      restartView,
      resourcesToSchemas,
      sseEventLog
    ).routes
}
