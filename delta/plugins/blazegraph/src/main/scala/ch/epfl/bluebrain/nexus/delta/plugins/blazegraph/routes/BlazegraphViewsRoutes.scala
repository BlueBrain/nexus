package ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.routes

import akka.http.scaladsl.model.StatusCodes.Created
import akka.http.scaladsl.model.{StatusCode, StatusCodes}
import akka.http.scaladsl.server.{Directive0, Route}
import cats.effect.IO
import cats.implicits.*
import ch.epfl.bluebrain.nexus.delta.kernel.circe.CirceUnmarshalling
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.model.BlazegraphViewRejection.*
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.model.*
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.model.permissions.{query as Query, read as Read, write as Write}
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.query.IncomingOutgoingLinks
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.{BlazegraphViews, BlazegraphViewsQuery}
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.{ContextValue, RemoteContextResolution}
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.encoder.JsonLdEncoder
import ch.epfl.bluebrain.nexus.delta.rdf.query.SparqlQuery
import ch.epfl.bluebrain.nexus.delta.rdf.utils.JsonKeyOrdering
import ch.epfl.bluebrain.nexus.delta.sdk.acls.AclCheck
import ch.epfl.bluebrain.nexus.delta.sdk.directives.{AuthDirectives, DeltaDirectives}
import ch.epfl.bluebrain.nexus.delta.sdk.fusion.FusionConfig
import ch.epfl.bluebrain.nexus.delta.sdk.identities.Identities
import ch.epfl.bluebrain.nexus.delta.sdk.identities.model.Caller
import ch.epfl.bluebrain.nexus.delta.sdk.implicits.*
import ch.epfl.bluebrain.nexus.delta.sdk.jsonld.JsonLdRejection.{DecodingFailed, InvalidJsonLdFormat}
import ch.epfl.bluebrain.nexus.delta.sdk.marshalling.{OriginalSource, RdfMarshalling}
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.SearchResults.*
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.{PaginationConfig, SearchResults}
import ch.epfl.bluebrain.nexus.delta.sdk.model.{BaseUri, IdSegment}
import ch.epfl.bluebrain.nexus.delta.sourcing.model.ProjectRef
import io.circe.Json

/**
  * The Blazegraph views routes
  */
class BlazegraphViewsRoutes(
    views: BlazegraphViews,
    viewsQuery: BlazegraphViewsQuery,
    incomingOutgoingLinks: IncomingOutgoingLinks,
    identities: Identities,
    aclCheck: AclCheck
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

  private val rejectPredicateOnWrite: PartialFunction[BlazegraphViewRejection, Boolean] = {
    case _: ViewNotFound | _: BlazegraphDecodingRejection => true
  }

  private def emitMetadataOrReject(statusCode: StatusCode, io: IO[ViewResource]): Route = {
    emit(
      statusCode,
      io.mapValue(_.metadata)
        .adaptError {
          case d: DecodingFailed      => BlazegraphDecodingRejection(d)
          case i: InvalidJsonLdFormat => BlazegraphDecodingRejection(i)
          case other                  => other
        }
        .attemptNarrow[BlazegraphViewRejection]
        .rejectWhen(rejectPredicateOnWrite)
    )
  }

  private def emitMetadataOrReject(io: IO[ViewResource]): Route = emitMetadataOrReject(StatusCodes.OK, io)

  private def emitFetch(io: IO[ViewResource]): Route =
    emit(io.attemptNarrow[BlazegraphViewRejection].rejectOn[ViewNotFound])

  private def emitSource(io: IO[ViewResource]): Route =
    emit(
      io.map { resource => OriginalSource(resource, resource.value.source) }
        .attemptNarrow[BlazegraphViewRejection]
        .rejectOn[ViewNotFound]
    )

  def routes: Route =
    concat(
      pathPrefix("views") {
        extractCaller { implicit caller =>
          projectRef { implicit project =>
            val authorizeRead  = authorizeFor(project, Read)
            val authorizeWrite = authorizeFor(project, Write)
            // Create a view without id segment
            concat(
              (pathEndOrSingleSlash & post & entity(as[Json]) & noParameter("rev")) { source =>
                authorizeWrite {
                  emitMetadataOrReject(Created, views.create(project, source))
                }
              },
              idSegment { id =>
                concat(
                  pathEndOrSingleSlash {
                    concat(
                      put {
                        authorizeWrite {
                          (parameter("rev".as[Int].?) & pathEndOrSingleSlash & entity(as[Json])) {
                            case (None, source)      =>
                              // Create a view with id segment
                              emitMetadataOrReject(
                                Created,
                                views.create(id, project, source)
                              )
                            case (Some(rev), source) =>
                              // Update a view
                              emitMetadataOrReject(
                                views.update(id, project, rev, source)
                              )
                          }
                        }
                      },
                      (delete & parameter("rev".as[Int])) { rev =>
                        // Deprecate a view
                        authorizeWrite {
                          emitMetadataOrReject(
                            views.deprecate(id, project, rev)
                          )
                        }
                      },
                      // Fetch a view
                      (get & idSegmentRef(id)) { id =>
                        emitOrFusionRedirect(
                          project,
                          id,
                          authorizeRead {
                            emitFetch(views.fetch(id, project))
                          }
                        )
                      }
                    )
                  },
                  // Undeprecate a blazegraph view
                  (pathPrefix("undeprecate") & put & parameter("rev".as[Int]) &
                    authorizeWrite & pathEndOrSingleSlash) { rev =>
                    emitMetadataOrReject(
                      views.undeprecate(id, project, rev)
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
                    authorizeRead {
                      emitSource(views.fetch(id, project))
                    }
                  },
                  // Incoming/outgoing links for views
                  incomingOutgoing(id, project)
                )
              }
            )
          }
        }
      },
      // Handle all other incoming and outgoing links
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

  private def incomingOutgoing(id: IdSegment, project: ProjectRef)(implicit caller: Caller) = {
    val authorizeQuery  = authorizeFor(project, Query)
    val metadataContext = ContextValue(Vocabulary.contexts.metadata)
    concat(
      (pathPrefix("incoming") & fromPaginated & pathEndOrSingleSlash & get & extractUri) { (pagination, uri) =>
        implicit val searchJsonLdEncoder: JsonLdEncoder[SearchResults[SparqlLink]] =
          searchResultsJsonLdEncoder(metadataContext, pagination, uri)
        authorizeQuery {
          emit(incomingOutgoingLinks.incoming(id, project, pagination).attemptNarrow[BlazegraphViewRejection])
        }
      },
      (pathPrefix("outgoing") & fromPaginated & pathEndOrSingleSlash & get & extractUri & parameter(
        "includeExternalLinks".as[Boolean] ? true
      )) { (pagination, uri, includeExternal) =>
        implicit val searchJsonLdEncoder: JsonLdEncoder[SearchResults[SparqlLink]] =
          searchResultsJsonLdEncoder(metadataContext, pagination, uri)
        authorizeQuery {
          emit(
            incomingOutgoingLinks
              .outgoing(id, project, pagination, includeExternal)
              .attemptNarrow[BlazegraphViewRejection]
          )
        }
      }
    )
  }
}

object BlazegraphViewsRoutes {

  def apply(
      views: BlazegraphViews,
      viewsQuery: BlazegraphViewsQuery,
      incomingOutgoingLinks: IncomingOutgoingLinks,
      identities: Identities,
      aclCheck: AclCheck
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
      incomingOutgoingLinks,
      identities,
      aclCheck
    ).routes
  }
}
