package ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.routes

import akka.http.scaladsl.model.StatusCodes.Created
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server._
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.model.ElasticSearchViewRejection._
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.model._
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.model.permissions.{read => Read, write => Write}
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.{ElasticSearchViews, ElasticSearchViewsQuery}
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.JsonLdContext.keywords
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.{ContextValue, RemoteContextResolution}
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.encoder.JsonLdEncoder
import ch.epfl.bluebrain.nexus.delta.rdf.utils.JsonKeyOrdering
import ch.epfl.bluebrain.nexus.delta.sdk._
import ch.epfl.bluebrain.nexus.delta.sdk.acls.AclCheck
import ch.epfl.bluebrain.nexus.delta.sdk.circe.CirceUnmarshalling
import ch.epfl.bluebrain.nexus.delta.sdk.directives.DeltaDirectives._
import ch.epfl.bluebrain.nexus.delta.sdk.directives.{AuthDirectives, DeltaSchemeDirectives}
import ch.epfl.bluebrain.nexus.delta.sdk.fusion.FusionConfig
import ch.epfl.bluebrain.nexus.delta.sdk.identities.Identities
import ch.epfl.bluebrain.nexus.delta.sdk.identities.model.Caller
import ch.epfl.bluebrain.nexus.delta.sdk.implicits._
import ch.epfl.bluebrain.nexus.delta.sdk.marshalling.RdfMarshalling
import ch.epfl.bluebrain.nexus.delta.sdk.model._
import ch.epfl.bluebrain.nexus.delta.sdk.model.routes.Tag
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.SearchResults.searchResultsJsonLdEncoder
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.{PaginationConfig, SearchResults}
import ch.epfl.bluebrain.nexus.delta.sourcing.ProgressStatistics
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{Label, ProjectRef}
import ch.epfl.bluebrain.nexus.delta.sourcing.offset.Offset
import ch.epfl.bluebrain.nexus.delta.sourcing.projections.Projections
import io.circe.generic.semiauto.deriveEncoder
import io.circe.syntax._
import io.circe.{Encoder, Json, JsonObject}
import kamon.instrumentation.akka.http.TracingDirectives.operationName
import monix.execution.Scheduler

/**
  * The elasticsearch views routes
  *
  * @param identities
  *   the identity module
  * @param aclCheck
  *   to check acls
  * @param views
  *   the elasticsearch views operations bundle
  * @param viewsQuery
  *   the elasticsearch views query operations bundle
  * @param projections
  *   the projections module
  * @param resourcesToSchemas
  *   a collection of root resource segment with their corresponding schema
  * @param schemeDirectives
  *   directives related to orgs and projects
  * @param index
  *   the indexing action on write operations
  */
final class ElasticSearchViewsRoutes(
    identities: Identities,
    aclCheck: AclCheck,
    views: ElasticSearchViews,
    viewsQuery: ElasticSearchViewsQuery,
    projections: Projections,
    resourcesToSchemas: ResourceToSchemaMappings,
    schemeDirectives: DeltaSchemeDirectives,
    index: IndexingAction.Execute[ElasticSearchView]
)(implicit
    baseUri: BaseUri,
    paginationConfig: PaginationConfig,
    s: Scheduler,
    cr: RemoteContextResolution,
    ordering: JsonKeyOrdering,
    fusionConfig: FusionConfig
) extends AuthDirectives(identities, aclCheck)
    with CirceUnmarshalling
    with ElasticSearchViewsDirectives
    with RdfMarshalling {

  import baseUri.prefixSegment
  import schemeDirectives._

  implicit private val viewStatisticEncoder: Encoder.AsObject[ProgressStatistics] =
    deriveEncoder[ProgressStatistics].mapJsonObject(_.add(keywords.tpe, "ViewStatistics".asJson))

  implicit private val viewStatisticJsonLdEncoder: JsonLdEncoder[ProgressStatistics] =
    JsonLdEncoder.computeFromCirce(ContextValue(Vocabulary.contexts.statistics))

  def routes: Route =
    (baseUriPrefix(baseUri.prefix) & replaceUri("views", schema.iri)) {
      concat(viewsRoutes, resourcesListings, genericResourcesRoutes)
    }

  private val viewsRoutes: Route =
    pathPrefix("views") {
      extractCaller { implicit caller =>
        resolveProjectRef.apply { ref =>
          concat(
            (pathEndOrSingleSlash & operationName(s"$prefixSegment/views/{org}/{project}")) {
              // Create an elasticsearch view without id segment
              (post & pathEndOrSingleSlash & noParameter("rev") & entity(as[Json]) & indexingMode) { (source, mode) =>
                authorizeFor(ref, Write).apply {
                  emit(
                    Created,
                    views
                      .create(ref, source)
                      .tapEval(index(ref, _, mode))
                      .mapValue(_.metadata)
                      .rejectWhen(decodingFailedOrViewNotFound)
                  )
                }
              }
            },
            (idSegment & indexingMode) { (id, mode) =>
              concat(
                pathEndOrSingleSlash {
                  operationName(s"$prefixSegment/views/{org}/{project}/{id}") {
                    concat(
                      // Create or update an elasticsearch view
                      put {
                        authorizeFor(ref, Write).apply {
                          (parameter("rev".as[Int].?) & pathEndOrSingleSlash & entity(as[Json])) {
                            case (None, source)      =>
                              // Create an elasticsearch view with id segment
                              emit(
                                Created,
                                views
                                  .create(id, ref, source)
                                  .tapEval(index(ref, _, mode))
                                  .mapValue(_.metadata)
                                  .rejectWhen(decodingFailedOrViewNotFound)
                              )
                            case (Some(rev), source) =>
                              // Update a view
                              emit(
                                views
                                  .update(id, ref, rev, source)
                                  .tapEval(index(ref, _, mode))
                                  .mapValue(_.metadata)
                                  .rejectWhen(decodingFailedOrViewNotFound)
                              )
                          }
                        }
                      },
                      // Deprecate an elasticsearch view
                      (delete & parameter("rev".as[Int])) { rev =>
                        authorizeFor(ref, Write).apply {
                          emit(
                            views
                              .deprecate(id, ref, rev)
                              .tapEval(index(ref, _, mode))
                              .mapValue(_.metadata)
                              .rejectWhen(decodingFailedOrViewNotFound)
                          )
                        }
                      },
                      // Fetch an elasticsearch view
                      (get & idSegmentRef(id)) { id =>
                        emitOrFusionRedirect(
                          ref,
                          id,
                          authorizeFor(ref, Read).apply {
                            emit(views.fetch(id, ref).rejectOn[ViewNotFound])
                          }
                        )
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
                          .flatMap(v =>
                            projections.statistics(ref, v.value.resourceTag, ElasticSearchViews.projectionName(v))
                          )
                          .rejectWhen(decodingFailedOrViewNotFound)
                      )
                    }
                  }
                },
                // Fetch elastic search view indexing failures
                lastEventId { offset =>
                  (pathPrefix("failures") & get & pathEndOrSingleSlash) {
                    operationName(s"$prefixSegment/views/{org}/{project}/{id}/failures") {
                      authorizeFor(ref, Write).apply {
                        emit(
                          views
                            .fetch(id, ref)
                            .map { view =>
                              projections
                                .failedElemSses(view.value.project, view.value.id, offset)
                            }
                        )
                      }
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
                            .flatMap(v => projections.offset(ElasticSearchViews.projectionName(v)))
                            .rejectWhen(decodingFailedOrViewNotFound)
                        )
                      },
                      // Remove an elasticsearch view offset (restart the view)
                      (delete & authorizeFor(ref, Write)) {
                        emit(
                          views
                            .fetchIndexingView(id, ref)
                            .flatMap { v => projections.scheduleRestart(ElasticSearchViews.projectionName(v)) }
                            .as(Offset.start)
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
                        emit(views.fetch(id, ref).map(_.value.tags).rejectOn[ViewNotFound])
                      },
                      // Tag an elasticsearch view
                      (post & parameter("rev".as[Int])) { rev =>
                        authorizeFor(ref, Write).apply {
                          entity(as[Tag]) { case Tag(tagRev, tag) =>
                            emit(
                              Created,
                              views
                                .tag(id, ref, tag, tagRev, rev)
                                .tapEval(index(ref, _, mode))
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
    }

  private val genericResourcesRoutes: Route =
    pathPrefix("resources") {
      extractCaller { implicit caller =>
        concat(
          // List all resources
          (pathEndOrSingleSlash & operationName(s"$prefixSegment/resources")) {
            list()
          },
          // List all resources inside an organization
          (label & pathEndOrSingleSlash & operationName(s"$prefixSegment/resources")) { org =>
            list(org)
          },
          resolveProjectRef.apply { ref =>
            concat(
              // List all resources inside a project
              (pathEndOrSingleSlash & operationName(s"$prefixSegment/resources/{org}/{project}")) {
                list(ref)
              },
              idSegment { schema =>
                // List all resources inside a project filtering by its schema type
                (pathEndOrSingleSlash & operationName(s"$prefixSegment/resources/{org}/{project}/{schema}")) {
                  list(ref, underscoreToOption(schema))
                }
              }
            )
          }
        )
      }
    }

  private val resourcesListings: Route =
    concat(resourcesToSchemas.value.map { case (Label(resourceSegment), resourceSchema) =>
      pathPrefix(resourceSegment) {
        extractCaller { implicit caller =>
          concat(
            // List all resources of type resourceSegment
            (pathEndOrSingleSlash & operationName(s"$prefixSegment/$resourceSegment")) {
              list(resourceSchema)
            },
            // List all resources of type resourceSegment inside an organization
            (label & pathEndOrSingleSlash & operationName(s"$prefixSegment/$resourceSegment/{org}")) { org =>
              list(org, resourceSchema)
            },
            resolveProjectRef.apply { ref =>
              // List all resources of type resourceSegment inside a project
              (pathEndOrSingleSlash & operationName(s"$prefixSegment/$resourceSegment/{org}/{project}")) {
                list(ref, resourceSchema)
              }
            }
          )
        }
      }
    }.toSeq: _*)

  private def list(segment: IdSegment)(implicit caller: Caller): Route =
    list(Some(segment))

  private def list()(implicit caller: Caller): Route =
    list(None)

  private def list(org: Label, segment: IdSegment)(implicit caller: Caller): Route =
    list(org, Some(segment))

  private def list(org: Label)(implicit caller: Caller): Route =
    list(org, None)

  private def list(ref: ProjectRef, segment: IdSegment)(implicit caller: Caller): Route =
    list(ref, Some(segment))

  private def list(ref: ProjectRef)(implicit caller: Caller): Route =
    list(ref, None)

  private def list(ref: ProjectRef, schemaSegment: Option[IdSegment])(implicit caller: Caller): Route = {
    projectContext(ref) { implicit pc =>
      (get & searchParametersInProject & paginated & extractUri) { (params, sort, page, uri) =>
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
  }

  private def list(schemaSegment: Option[IdSegment])(implicit caller: Caller): Route =
    (get & searchParametersAndSortList(baseUri) & paginated & extractUri) { (params, sort, page, uri) =>
      implicit val searchJsonLdEncoder: JsonLdEncoder[SearchResults[JsonObject]] =
        searchResultsJsonLdEncoder(ContextValue(contexts.searchMetadata), page, uri)
      schemaSegment match {
        case Some(segment) => emit(viewsQuery.list(segment, page, params, sort))
        case None          => emit(viewsQuery.list(page, params, sort))
      }
    }

  private def list(org: Label, schemaSegment: Option[IdSegment])(implicit caller: Caller): Route =
    (get & searchParametersAndSortList(baseUri) & paginated & extractUri) { (params, sort, page, uri) =>
      implicit val searchJsonLdEncoder: JsonLdEncoder[SearchResults[JsonObject]] =
        searchResultsJsonLdEncoder(ContextValue(contexts.searchMetadata), page, uri)
      schemaSegment match {
        case Some(segment) => emit(viewsQuery.list(org, segment, page, params, sort))
        case None          => emit(viewsQuery.list(org, page, params, sort))
      }
    }

  private val decodingFailedOrViewNotFound: PartialFunction[ElasticSearchViewRejection, Boolean] = {
    case _: DecodingFailed | _: ViewNotFound | _: InvalidJsonLdFormat => true
  }
}

object ElasticSearchViewsRoutes {

  /**
    * @return
    *   the [[Route]] for elasticsearch views
    */
  def apply(
      identities: Identities,
      aclCheck: AclCheck,
      views: ElasticSearchViews,
      viewsQuery: ElasticSearchViewsQuery,
      projections: Projections,
      resourcesToSchemas: ResourceToSchemaMappings,
      schemeDirectives: DeltaSchemeDirectives,
      index: IndexingAction.Execute[ElasticSearchView]
  )(implicit
      baseUri: BaseUri,
      paginationConfig: PaginationConfig,
      s: Scheduler,
      cr: RemoteContextResolution,
      ordering: JsonKeyOrdering,
      fusionConfig: FusionConfig
  ): Route =
    new ElasticSearchViewsRoutes(
      identities,
      aclCheck,
      views,
      viewsQuery,
      projections,
      resourcesToSchemas,
      schemeDirectives,
      index
    ).routes
}
