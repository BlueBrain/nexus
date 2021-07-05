package ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.routes

import akka.http.scaladsl.model.StatusCodes.Created
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.{Directive0, Route}
import akka.persistence.query.NoOffset
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.client.SparqlQuery
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.model.BlazegraphView._
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.model.BlazegraphViewRejection._
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.model._
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.model.permissions.{read => Read, write => Write}
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.routes.BlazegraphViewsRoutes.RestartView
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.{BlazegraphViews, BlazegraphViewsQuery}
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.contexts
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.JsonLdContext.keywords
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.{ContextValue, RemoteContextResolution}
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.encoder.JsonLdEncoder
import ch.epfl.bluebrain.nexus.delta.rdf.utils.JsonKeyOrdering
import ch.epfl.bluebrain.nexus.delta.sdk.circe.CirceUnmarshalling
import ch.epfl.bluebrain.nexus.delta.sdk.directives.{AuthDirectives, DeltaDirectives}
import ch.epfl.bluebrain.nexus.delta.sdk.implicits._
import ch.epfl.bluebrain.nexus.delta.sdk.marshalling.RdfMarshalling
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Caller
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.ProjectRef
import ch.epfl.bluebrain.nexus.delta.sdk.model.routes.{Tag, Tags}
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.SearchResults._
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.{PaginationConfig, SearchResults}
import ch.epfl.bluebrain.nexus.delta.sdk.model.{BaseUri, IdSegment, ProgressStatistics}
import ch.epfl.bluebrain.nexus.delta.sdk.{Acls, Identities, ProgressesStatistics, Projects}
import io.circe.generic.semiauto.deriveEncoder
import io.circe.syntax._
import io.circe.{Encoder, Json}
import kamon.instrumentation.akka.http.TracingDirectives.operationName
import monix.bio.UIO
import monix.execution.Scheduler

/**
  * The Blazegraph views routes
  *
  * @param views      the blazegraph views operations bundle
  * @param identities the identity module
  * @param acls       the ACLs module
  * @param projects   the projects module
  * @param progresses the statistics of the progresses for the blazegraph views
  * @param restartView  the action to restart a view indexing process triggered by a client
  */
class BlazegraphViewsRoutes(
    views: BlazegraphViews,
    viewsQuery: BlazegraphViewsQuery,
    identities: Identities,
    acls: Acls,
    projects: Projects,
    progresses: ProgressesStatistics,
    restartView: RestartView
)(implicit
    baseUri: BaseUri,
    s: Scheduler,
    cr: RemoteContextResolution,
    ordering: JsonKeyOrdering,
    pc: PaginationConfig
) extends AuthDirectives(identities, acls)
    with CirceUnmarshalling
    with DeltaDirectives
    with RdfMarshalling
    with BlazegraphViewsDirectives {

  import baseUri.prefixSegment

  implicit private val viewStatisticEncoder: Encoder.AsObject[ProgressStatistics] =
    deriveEncoder[ProgressStatistics].mapJsonObject(_.add(keywords.tpe, "ViewStatistics".asJson))

  implicit private val viewStatisticJsonLdEncoder: JsonLdEncoder[ProgressStatistics] =
    JsonLdEncoder.computeFromCirce(ContextValue(contexts.statistics))

  def routes: Route =
    (baseUriPrefix(baseUri.prefix) & replaceUri("views", schema.iri, projects)) {
      concat(
        pathPrefix("views") {
          extractCaller { implicit caller =>
            projectRef(projects).apply { implicit ref =>
              // Create a view without id segment
              concat(
                (post & entity(as[Json]) & noParameter("rev") & pathEndOrSingleSlash & executionType) {
                  (source, execution) =>
                    operationName(s"$prefixSegment/views/{org}/{project}") {
                      authorizeFor(ref, Write).apply {
                        emit(
                          Created,
                          views
                            .create(ref, source, execution)
                            .mapValue(_.metadata)
                            .rejectWhen(decodingFailedOrViewNotFound)
                        )
                      }
                    }
                },
                (idSegment & executionType) { (id, execution) =>
                  concat(
                    (pathEndOrSingleSlash & operationName(s"$prefixSegment/views/{org}/{project}/{id}")) {
                      concat(
                        put {
                          authorizeFor(ref, Write).apply {
                            (parameter("rev".as[Long].?) & pathEndOrSingleSlash & entity(as[Json])) {
                              case (None, source)      =>
                                // Create a view with id segment
                                emit(
                                  Created,
                                  views
                                    .create(id, ref, source, execution)
                                    .mapValue(_.metadata)
                                    .rejectWhen(decodingFailedOrViewNotFound)
                                )
                              case (Some(rev), source) =>
                                // Update a view
                                emit(
                                  views
                                    .update(id, ref, rev, source, execution)
                                    .mapValue(_.metadata)
                                    .rejectWhen(decodingFailedOrViewNotFound)
                                )
                            }
                          }
                        },
                        (delete & parameter("rev".as[Long])) { rev =>
                          // Deprecate a view
                          authorizeFor(ref, Write).apply {
                            emit(views.deprecate(id, ref, rev, execution).mapValue(_.metadata).rejectOn[ViewNotFound])
                          }
                        },
                        // Fetch a view
                        (get & idSegmentRef(id) & authorizeFor(ref, Read)) { id =>
                          emit(views.fetch(id, ref).rejectOn[ViewNotFound])
                        }
                      )
                    },
                    // Query a blazegraph view
                    (pathPrefix("sparql") & pathEndOrSingleSlash) {
                      operationName(s"$prefixSegment/views/{org}/{project}/{id}/sparql") {
                        concat(
                          // Query
                          ((get & parameter("query".as[SparqlQuery])) | (post & entity(as[SparqlQuery]))) { query =>
                            queryResponseType.apply { responseType =>
                              emit(viewsQuery.query(id, ref, query, responseType).rejectOn[ViewNotFound])
                            }
                          }
                        )
                      }
                    },
                    // Fetch a blazegraph view statistics
                    (pathPrefix("statistics") & get & pathEndOrSingleSlash) {
                      operationName(s"$prefixSegment/views/{org}/{project}/{id}/statistics") {
                        authorizeFor(ref, permissions.read).apply {
                          emit(
                            views
                              .fetchIndexingView(id, ref)
                              .flatMap(v => progresses.statistics(ref, BlazegraphViews.projectionId(v)))
                              .rejectOn[ViewNotFound]
                          )
                        }
                      }
                    },
                    // Manage an blazegraph view offset
                    (pathPrefix("offset") & pathEndOrSingleSlash) {
                      operationName(s"$prefixSegment/views/{org}/{project}/{id}/offset") {
                        concat(
                          // Fetch a blazegraph view offset
                          (get & authorizeFor(ref, permissions.read)) {
                            emit(
                              views
                                .fetchIndexingView(id, ref)
                                .flatMap(v => progresses.offset(BlazegraphViews.projectionId(v)))
                                .rejectOn[ViewNotFound]
                            )
                          },
                          // Remove an blazegraph view offset (restart the view)
                          (delete & authorizeFor(ref, Write)) {
                            emit(
                              views
                                .fetchIndexingView(id, ref)
                                .flatMap { r => restartView(r.value.id, r.value.project) }
                                .as(NoOffset)
                                .rejectOn[ViewNotFound]
                            )
                          }
                        )
                      }
                    },
                    (pathPrefix("tags") & pathEndOrSingleSlash) {
                      operationName(s"$prefixSegment/views/{org}/{project}/{id}/tags") {
                        concat(
                          // Fetch tags for a view
                          (get & idSegmentRef(id) & authorizeFor(ref, Read)) { id =>
                            emit(views.fetch(id, ref).map(res => Tags(res.value.tags)).rejectOn[ViewNotFound])
                          },
                          // Tag a view
                          (post & parameter("rev".as[Long])) { rev =>
                            authorizeFor(ref, Write).apply {
                              entity(as[Tag]) { case Tag(tagRev, tag) =>
                                emit(
                                  Created,
                                  views
                                    .tag(id, ref, tag, tagRev, rev, execution)
                                    .mapValue(_.metadata)
                                    .rejectOn[ViewNotFound]
                                )
                              }
                            }
                          }
                        )
                      }
                    },
                    // Fetch a view original source
                    (pathPrefix("source") & get & pathEndOrSingleSlash & idSegmentRef(id)) { id =>
                      operationName(s"$prefixSegment/views/{org}/{project}/{id}/source") {
                        authorizeFor(ref, Read).apply {
                          emit(views.fetch(id, ref).map(_.value.source).rejectOn[ViewNotFound])
                        }
                      }
                    },
                    //Incoming/outgoing links for views
                    incomingOutgoing(id, ref)
                  )
                }
              )
            }
          }
        },
        //Handle all other incoming and outgoing links
        pathPrefix(Segment) { segment =>
          extractCaller { implicit caller =>
            projectRef(projects).apply { ref =>
              // if we are on the path /resources/{org}/{proj}/ we need to consume the {schema} segment before consuming the {id}
              consumeIdSegmentIf(segment == "resources") {
                idSegment { id =>
                  incomingOutgoing(id, ref)
                }
              }
            }
          }
        }
      )
    }

  private def consumeIdSegmentIf(condition: Boolean): Directive0 =
    if (condition) idSegment.flatMap(_ => pass)
    else pass

  private def incomingOutgoing(id: IdSegment, ref: ProjectRef)(implicit caller: Caller) =
    concat(
      (pathPrefix("incoming") & fromPaginated & pathEndOrSingleSlash & extractUri) { (pagination, uri) =>
        implicit val searchJsonLdEncoder: JsonLdEncoder[SearchResults[SparqlLink]] =
          searchResultsJsonLdEncoder(ContextValue(Vocabulary.contexts.metadata), pagination, uri)

        authorizeFor(ref, Read).apply {
          emit(viewsQuery.incoming(id, ref, pagination))
        }
      },
      (pathPrefix("outgoing") & fromPaginated & pathEndOrSingleSlash & extractUri & parameter(
        "includeExternalLinks".as[Boolean] ? true
      )) { (pagination, uri, includeExternal) =>
        implicit val searchJsonLdEncoder: JsonLdEncoder[SearchResults[SparqlLink]] =
          searchResultsJsonLdEncoder(ContextValue(Vocabulary.contexts.metadata), pagination, uri)

        authorizeFor(ref, Read).apply {
          emit(viewsQuery.outgoing(id, ref, pagination, includeExternal))
        }
      }
    )

  private val decodingFailedOrViewNotFound: PartialFunction[BlazegraphViewRejection, Boolean] = {
    case _: DecodingFailed | _: ViewNotFound | _: InvalidJsonLdFormat => true
  }

}

object BlazegraphViewsRoutes {

  type RestartView = (Iri, ProjectRef) => UIO[Unit]

  /**
    * @return the [[Route]] for BlazegraphViews
    */
  def apply(
      views: BlazegraphViews,
      viewsQuery: BlazegraphViewsQuery,
      identities: Identities,
      acls: Acls,
      projects: Projects,
      progresses: ProgressesStatistics,
      restartView: RestartView
  )(implicit
      baseUri: BaseUri,
      s: Scheduler,
      cr: RemoteContextResolution,
      ordering: JsonKeyOrdering,
      pc: PaginationConfig
  ): Route = {
    new BlazegraphViewsRoutes(views, viewsQuery, identities, acls, projects, progresses, restartView).routes
  }
}
