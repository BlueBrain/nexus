package ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.routes

import akka.http.scaladsl.model.StatusCodes.Created
import akka.http.scaladsl.server.{Directive0, Route}
import cats.implicits._
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.model.BlazegraphViewRejection._
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.model._
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.model.permissions.{read => Read, write => Write}
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.{BlazegraphViews, BlazegraphViewsQuery}
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.{ContextValue, RemoteContextResolution}
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.encoder.JsonLdEncoder
import ch.epfl.bluebrain.nexus.delta.rdf.query.SparqlQuery
import ch.epfl.bluebrain.nexus.delta.rdf.utils.JsonKeyOrdering
import ch.epfl.bluebrain.nexus.delta.sdk.IndexingAction
import ch.epfl.bluebrain.nexus.delta.sdk.acls.AclCheck
import ch.epfl.bluebrain.nexus.delta.sdk.circe.CirceUnmarshalling
import ch.epfl.bluebrain.nexus.delta.sdk.directives.{AuthDirectives, DeltaDirectives}
import ch.epfl.bluebrain.nexus.delta.sdk.fusion.FusionConfig
import ch.epfl.bluebrain.nexus.delta.sdk.identities.Identities
import ch.epfl.bluebrain.nexus.delta.sdk.identities.model.Caller
import ch.epfl.bluebrain.nexus.delta.sdk.implicits._
import ch.epfl.bluebrain.nexus.delta.sdk.marshalling.RdfMarshalling
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.SearchResults._
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.{PaginationConfig, SearchResults}
import ch.epfl.bluebrain.nexus.delta.sdk.model.{BaseUri, IdSegment}
import ch.epfl.bluebrain.nexus.delta.sourcing.model.ProjectRef
import io.circe.Json

/**
  * The Blazegraph views routes
  *
  * @param views
  *   the blazegraph views operations bundle
  * @param identities
  *   the identity module
  * @param aclCheck
  *   to check the acls
  * @param index
  *   the indexing action on write operations
  */
class BlazegraphViewsRoutes(
    views: BlazegraphViews,
    viewsQuery: BlazegraphViewsQuery,
    identities: Identities,
    aclCheck: AclCheck,
    index: IndexingAction.Execute[BlazegraphView]
)(implicit
    baseUri: BaseUri,
    cr: RemoteContextResolution,
    ordering: JsonKeyOrdering,
    pc: PaginationConfig,
    fusionConfig: FusionConfig
) extends AuthDirectives(identities, aclCheck)
    with CirceUnmarshalling
    with DeltaDirectives
    with RdfMarshalling
    with BlazegraphViewsDirectives {

  def routes: Route =
    concat(
      pathPrefix("views") {
        extractCaller { implicit caller =>
          projectRef { implicit project =>
            // Create a view without id segment
            concat(
              (post & entity(as[Json]) & noParameter("rev") & pathEndOrSingleSlash & indexingMode) { (source, mode) =>
                authorizeFor(project, Write).apply {
                  emit(
                    Created,
                    views
                      .create(project, source)
                      .flatTap(index(project, _, mode))
                      .mapValue(_.metadata)
                      .attemptNarrow[BlazegraphViewRejection]
                      .rejectWhen(decodingFailedOrViewNotFound)
                  )
                }
              },
              (idSegment & indexingMode) { (id, mode) =>
                concat(
                  pathEndOrSingleSlash {
                    concat(
                      put {
                        authorizeFor(project, Write).apply {
                          (parameter("rev".as[Int].?) & pathEndOrSingleSlash & entity(as[Json])) {
                            case (None, source)      =>
                              // Create a view with id segment
                              emit(
                                Created,
                                views
                                  .create(id, project, source)
                                  .flatTap(index(project, _, mode))
                                  .mapValue(_.metadata)
                                  .attemptNarrow[BlazegraphViewRejection]
                                  .rejectWhen(decodingFailedOrViewNotFound)
                              )
                            case (Some(rev), source) =>
                              // Update a view
                              emit(
                                views
                                  .update(id, project, rev, source)
                                  .flatTap(index(project, _, mode))
                                  .mapValue(_.metadata)
                                  .attemptNarrow[BlazegraphViewRejection]
                                  .rejectWhen(decodingFailedOrViewNotFound)
                              )
                          }
                        }
                      },
                      (delete & parameter("rev".as[Int])) { rev =>
                        // Deprecate a view
                        authorizeFor(project, Write).apply {
                          emit(
                            views
                              .deprecate(id, project, rev)
                              .flatTap(index(project, _, mode))
                              .mapValue(_.metadata)
                              .attemptNarrow[BlazegraphViewRejection]
                              .rejectOn[ViewNotFound]
                          )
                        }
                      },
                      // Fetch a view
                      (get & idSegmentRef(id)) { id =>
                        emitOrFusionRedirect(
                          project,
                          id,
                          authorizeFor(project, Read).apply {
                            emit(
                              views
                                .fetch(id, project)
                                .attemptNarrow[BlazegraphViewRejection]
                                .rejectOn[ViewNotFound]
                            )
                          }
                        )
                      }
                    )
                  },
                  // Undeprecate a blazegraph view
                  (put & pathPrefix("undeprecate") & parameter("rev".as[Int]) &
                    authorizeFor(project, Write) & pathEndOrSingleSlash) { rev =>
                    emit(
                      views
                        .undeprecate(id, project, rev)
                        .flatTap(index(project, _, mode))
                        .mapValue(_.metadata)
                        .attemptNarrow[BlazegraphViewRejection]
                        .rejectOn[ViewNotFound]
                    )
                  },
                  // Query a blazegraph view
                  (pathPrefix("sparql") & pathEndOrSingleSlash) {
                    concat(
                      // Query
                      ((get & parameter("query".as[SparqlQuery])) | (post & entity(as[SparqlQuery]))) { query =>
                        queryResponseType.apply { responseType =>
                          emit(
                            viewsQuery
                              .query(id, project, query, responseType)
                              .attemptNarrow[BlazegraphViewRejection]
                              .rejectOn[ViewNotFound]
                          )
                        }
                      }
                    )
                  },
                  // Fetch a view original source
                  (pathPrefix("source") & get & pathEndOrSingleSlash & idSegmentRef(id)) { id =>
                    authorizeFor(project, Read).apply {
                      emit(
                        views
                          .fetch(id, project)
                          .map(_.value.source)
                          .attemptNarrow[BlazegraphViewRejection]
                          .rejectOn[ViewNotFound]
                      )
                    }
                  },
                  //Incoming/outgoing links for views
                  incomingOutgoing(id, project)
                )
              }
            )
          }
        }
      },
      //Handle all other incoming and outgoing links
      pathPrefix(Segment) { segment =>
        extractCaller { implicit caller =>
          projectRef { project =>
            // if we are on the path /resources/{org}/{proj}/ we need to consume the {schema} segment before consuming the {id}
            consumeIdSegmentIf(segment == "resources") {
              idSegment { id =>
                incomingOutgoing(id, project)
              }
            }
          }
        }
      }
    )

  private def consumeIdSegmentIf(condition: Boolean): Directive0 =
    if (condition) idSegment.flatMap(_ => pass)
    else pass

  private def incomingOutgoing(id: IdSegment, ref: ProjectRef)(implicit caller: Caller) =
    concat(
      (pathPrefix("incoming") & fromPaginated & pathEndOrSingleSlash & extractUri) { (pagination, uri) =>
        implicit val searchJsonLdEncoder: JsonLdEncoder[SearchResults[SparqlLink]] =
          searchResultsJsonLdEncoder(ContextValue(Vocabulary.contexts.metadata), pagination, uri)

        authorizeFor(ref, Read).apply {
          emit(viewsQuery.incoming(id, ref, pagination).attemptNarrow[BlazegraphViewRejection])
        }
      },
      (pathPrefix("outgoing") & fromPaginated & pathEndOrSingleSlash & extractUri & parameter(
        "includeExternalLinks".as[Boolean] ? true
      )) { (pagination, uri, includeExternal) =>
        implicit val searchJsonLdEncoder: JsonLdEncoder[SearchResults[SparqlLink]] =
          searchResultsJsonLdEncoder(ContextValue(Vocabulary.contexts.metadata), pagination, uri)

        authorizeFor(ref, Read).apply {
          emit(
            viewsQuery.outgoing(id, ref, pagination, includeExternal).attemptNarrow[BlazegraphViewRejection]
          )
        }
      }
    )
}

object BlazegraphViewsRoutes {

  /**
    * @return
    *   the [[Route]] for BlazegraphViews
    */
  def apply(
      views: BlazegraphViews,
      viewsQuery: BlazegraphViewsQuery,
      identities: Identities,
      aclCheck: AclCheck,
      index: IndexingAction.Execute[BlazegraphView]
  )(implicit
      baseUri: BaseUri,
      cr: RemoteContextResolution,
      ordering: JsonKeyOrdering,
      pc: PaginationConfig,
      fusionConfig: FusionConfig
  ): Route = {
    new BlazegraphViewsRoutes(
      views,
      viewsQuery,
      identities,
      aclCheck,
      index
    ).routes
  }
}
