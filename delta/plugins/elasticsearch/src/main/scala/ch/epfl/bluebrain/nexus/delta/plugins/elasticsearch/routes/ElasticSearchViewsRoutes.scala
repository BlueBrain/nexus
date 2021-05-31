package ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.routes

import akka.http.scaladsl.model.StatusCodes.{Created, OK}
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server._
import akka.persistence.query.NoOffset
import cats.syntax.all._
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.model.ElasticSearchViewRejection._
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.model._
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.model.permissions.{read => Read, write => Write}
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.routes.ElasticSearchViewsRoutes.RestartView
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.{ElasticSearchViews, ElasticSearchViewsQuery}
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.JsonLdContext.keywords
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.{ContextValue, RemoteContextResolution}
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.encoder.JsonLdEncoder
import ch.epfl.bluebrain.nexus.delta.rdf.utils.JsonKeyOrdering
import ch.epfl.bluebrain.nexus.delta.sdk.Permissions.events
import ch.epfl.bluebrain.nexus.delta.sdk.Projects.{FetchProject, FetchUuids}
import ch.epfl.bluebrain.nexus.delta.sdk._
import ch.epfl.bluebrain.nexus.delta.sdk.circe.CirceUnmarshalling
import ch.epfl.bluebrain.nexus.delta.sdk.directives.AuthDirectives
import ch.epfl.bluebrain.nexus.delta.sdk.directives.DeltaDirectives._
import ch.epfl.bluebrain.nexus.delta.sdk.marshalling.RdfMarshalling
import ch.epfl.bluebrain.nexus.delta.sdk.implicits._
import ch.epfl.bluebrain.nexus.delta.sdk.model.acls.AclAddress
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Caller
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.ProjectRef
import ch.epfl.bluebrain.nexus.delta.sdk.model.routes.{Tag, Tags}
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.SearchResults.searchResultsJsonLdEncoder
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.{PaginationConfig, SearchResults}
import ch.epfl.bluebrain.nexus.delta.sdk.model._
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
    restartView: RestartView,
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
    with ElasticSearchViewsDirectives
    with RdfMarshalling {

  import baseUri.prefixSegment

  implicit private val viewStatisticEncoder: Encoder.AsObject[ProgressStatistics] =
    deriveEncoder[ProgressStatistics].mapJsonObject(_.add(keywords.tpe, "ViewStatistics".asJson))

  implicit private val viewStatisticJsonLdEncoder: JsonLdEncoder[ProgressStatistics] =
    JsonLdEncoder.computeFromCirce(ContextValue(Vocabulary.contexts.statistics))

  implicit private val fetchProjectUuids: FetchUuids = projects
  implicit private val fetchProject: FetchProject    = projects

  def routes: Route =
    (baseUriPrefix(baseUri.prefix) & replaceUri("views", schema.iri, projects)) {
      concat(viewsRoutes, resourcesListings, genericResourcesRoutes)
    }

  private val viewsRoutes: Route =
    pathPrefix("views") {
      extractCaller { implicit caller =>
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
                authorizeFor(org, events.read).apply {
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
                operationName(s"$prefixSegment/views/{org}/{project}/events") {
                  concat(
                    get {
                      authorizeFor(ref, events.read).apply {
                        lastEventId { offset =>
                          emit(sseEventLog.stream(ref, offset).leftWiden[ElasticSearchViewRejection])
                        }
                      }
                    },
                    (head & authorizeFor(ref, events.read)) {
                      complete(OK)
                    }
                  )
                }
              },
              (pathEndOrSingleSlash & operationName(s"$prefixSegment/views/{org}/{project}")) {
                // Create an elasticsearch view without id segment
                (post & pathEndOrSingleSlash & noParameter("rev") & entity(as[Json])) { source =>
                  authorizeFor(ref, Write).apply {
                    emit(
                      Created,
                      views.create(ref, source).mapValue(_.metadata).rejectWhen(decodingFailedOrViewNotFound)
                    )
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
                          authorizeFor(ref, Write).apply {
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
                          authorizeFor(ref, Write).apply {
                            emit(
                              views
                                .deprecate(id, ref, rev)
                                .mapValue(_.metadata)
                                .rejectWhen(decodingFailedOrViewNotFound)
                            )
                          }
                        },
                        // Fetch an elasticsearch view
                        (get & idSegmentRef(id) & authorizeFor(ref, Read)) { id =>
                          emit(views.fetch(id, ref).rejectOn[ViewNotFound])
                        }
                      )
                    }
                  },
                  // Fetch an elasticsearch view statistics
                  (pathPrefix("statistics") & get & pathEndOrSingleSlash) {
                    operationName(s"$prefixSegment/views/{org}/{project}/{id}/statistics") {
                      authorizeFor(ref, Read).apply {
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
                        (get & authorizeFor(ref, Read)) {
                          emit(
                            views
                              .fetchIndexingView(id, ref)
                              .flatMap(v => progresses.offset(ElasticSearchViews.projectionId(v)))
                              .rejectWhen(decodingFailedOrViewNotFound)
                          )
                        },
                        // Remove an elasticsearch view offset (restart the view)
                        (delete & authorizeFor(ref, Write)) {
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
                      (extractQueryParams & entity(as[JsonObject])) { (qp, query) =>
                        emit(viewsQuery.query(id, ref, query, qp))
                      }
                    }
                  },
                  // Fetch an elasticsearch view original source
                  (pathPrefix("source") & get & pathEndOrSingleSlash & idSegmentRef(id)) { id =>
                    operationName(s"$prefixSegment/views/{org}/{project}/{id}/source") {
                      authorizeFor(ref, Read).apply {
                        emit(views.fetch(id, ref).map(_.value.source).rejectOn[ViewNotFound])
                      }
                    }
                  },
                  (pathPrefix("tags") & pathEndOrSingleSlash) {
                    operationName(s"$prefixSegment/views/{org}/{project}/{id}/tags") {
                      concat(
                        // Fetch an elasticsearch view tags
                        (get & idSegmentRef(id) & authorizeFor(ref, Read)) { id =>
                          emit(views.fetch(id, ref).map(res => Tags(res.value.tags)).rejectOn[ViewNotFound])
                        },
                        // Tag an elasticsearch view
                        (post & parameter("rev".as[Long])) { rev =>
                          authorizeFor(ref, Write).apply {
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
    }

  private val genericResourcesRoutes: Route =
    pathPrefix("resources") {
      extractCaller { implicit caller =>
        projectRef(projects).apply { ref =>
          concat(
            // List all resources
            (pathEndOrSingleSlash & operationName(s"$prefixSegment/resources/{org}/{project}")) {
              list(ref)
            },
            idSegment { schema =>
              // List all resources filtering by its schema type
              (pathEndOrSingleSlash & operationName(s"$prefixSegment/resources/{org}/{project}/{schema}")) {
                list(ref, underscoreToOption(schema))
              }
            }
          )
        }
      }
    }

  private val resourcesListings: Route =
    concat(resourcesToSchemas.value.map { case (Label(resourceSegment), resourceSchema) =>
      pathPrefix(resourceSegment) {
        extractCaller { implicit caller =>
          projectRef(projects).apply { ref =>
            // List all resource of type resourceSegment
            (pathEndOrSingleSlash & operationName(s"$prefixSegment/$resourceSegment/{org}/{project}")) {
              list(ref, resourceSchema)
            }
          }
        }
      }
    }.toSeq: _*)

  private def list(ref: ProjectRef, segment: IdSegment)(implicit caller: Caller): Route =
    list(ref, Some(segment))

  private def list(ref: ProjectRef)(implicit caller: Caller): Route =
    list(ref, None)

  private def list(ref: ProjectRef, schemaSegment: Option[IdSegment])(implicit caller: Caller): Route = {
    implicit val r: ProjectRef = ref
    (get & searchParametersAndSortList & paginated & extractUri) { (params, sort, page, uri) =>
      authorizeFor(ref, Read).apply {
        implicit val searchJsonLdEncoder: JsonLdEncoder[SearchResults[JsonObject]] =
          searchResultsJsonLdEncoder(ContextValue(contexts.searchMetadata), page, uri)

        schemaSegment match {
          case Some(segment) => emit(viewsQuery.list(ref, segment, page, params, sort))
          case None          => emit(viewsQuery.list(ref, page, params, sort))
        }
      }
    }
  }

  private val decodingFailedOrViewNotFound: PartialFunction[ElasticSearchViewRejection, Boolean] = {
    case _: DecodingFailed | _: ViewNotFound | _: InvalidJsonLdFormat => true
  }
}

object ElasticSearchViewsRoutes {

  type RestartView = (Iri, ProjectRef) => UIO[Unit]

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
      restartView: RestartView,
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
