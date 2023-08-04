package ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.routes

import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import cats.syntax.all._
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.routes.BlazegraphViewsDirectives
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.model.CompositeViewRejection._
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.model._
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.model.permissions.{read => Read, write => Write}
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.projections.{CompositeIndexingDetails, CompositeProjections}
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.routes.CompositeViewsIndexingRoutes.{FetchProjection, FetchSource, FetchView}
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.routes.ElasticSearchViewsDirectives
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.contexts
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.{ContextValue, RemoteContextResolution}
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.encoder.JsonLdEncoder
import ch.epfl.bluebrain.nexus.delta.rdf.utils.JsonKeyOrdering
import ch.epfl.bluebrain.nexus.delta.sdk.acls.AclCheck
import ch.epfl.bluebrain.nexus.delta.sdk.circe.CirceUnmarshalling
import ch.epfl.bluebrain.nexus.delta.sdk.directives.{AuthDirectives, DeltaDirectives, DeltaSchemeDirectives}
import ch.epfl.bluebrain.nexus.delta.sdk.identities.Identities
import ch.epfl.bluebrain.nexus.delta.sdk.implicits._
import ch.epfl.bluebrain.nexus.delta.sdk.marshalling.RdfMarshalling
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.SearchResults.searchResultsJsonLdEncoder
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.{PaginationConfig, SearchResults}
import ch.epfl.bluebrain.nexus.delta.sdk.model.{BaseUri, IdSegment, IdSegmentRef}
import ch.epfl.bluebrain.nexus.delta.sdk.views.ViewRef
import ch.epfl.bluebrain.nexus.delta.sourcing.model.FailedElemLogRow.FailedElemData
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.Subject
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{FailedElemLogRow, ProjectRef}
import ch.epfl.bluebrain.nexus.delta.sourcing.offset.Offset
import ch.epfl.bluebrain.nexus.delta.sourcing.projections.ProjectionErrors
import monix.bio.IO
import monix.execution.Scheduler
class CompositeViewsIndexingRoutes(
    identities: Identities,
    aclCheck: AclCheck,
    fetchView: FetchView,
    fetchProjection: FetchProjection,
    fetchSource: FetchSource,
    details: CompositeIndexingDetails,
    projections: CompositeProjections,
    projectionErrors: ProjectionErrors,
    schemeDirectives: DeltaSchemeDirectives
)(implicit
    baseUri: BaseUri,
    paginationConfig: PaginationConfig,
    s: Scheduler,
    cr: RemoteContextResolution,
    ordering: JsonKeyOrdering
) extends AuthDirectives(identities, aclCheck)
    with DeltaDirectives
    with CirceUnmarshalling
    with RdfMarshalling
    with ElasticSearchViewsDirectives
    with BlazegraphViewsDirectives {

  import schemeDirectives._

  implicit private val offsetsSearchJsonLdEncoder: JsonLdEncoder[SearchResults[ProjectionOffset]] =
    searchResultsJsonLdEncoder(ContextValue(contexts.offset))

  implicit private val statisticsSearchJsonLdEncoder: JsonLdEncoder[SearchResults[ProjectionStatistics]] =
    searchResultsJsonLdEncoder(ContextValue(contexts.statistics))

  def routes: Route =
    pathPrefix("views") {
      extractCaller { implicit caller =>
        resolveProjectRef.apply { implicit ref =>
          idSegment { id =>
            concat(
              // Manage composite view offsets
              (pathPrefix("offset") & pathEndOrSingleSlash) {
                concat(
                  // Fetch all composite view offsets
                  (get & authorizeFor(ref, Read)) {
                    emit(fetchOffsets(ref, id).rejectOn[ViewNotFound])
                  },
                  // Remove all composite view offsets (restart the view)
                  (delete & authorizeFor(ref, Write)) {
                    emit(fullRestart(ref, id).rejectOn[ViewNotFound])
                  }
                )
              },
              // Fetch composite view statistics
              (get & pathPrefix("statistics") & pathEndOrSingleSlash) {
                authorizeFor(ref, Read).apply {
                  emit(fetchView(id, ref).flatMap(details.statistics).rejectOn[ViewNotFound])
                }
              },
              // Fetch elastic search view indexing failures
              (pathPrefix("failures") & get) {
                authorizeFor(ref, Write).apply {
                  concat(
                    (pathPrefix("sse") & lastEventId) { offset =>
                      emit(
                        fetchView(id, ref)
                          .map { view =>
                            projectionErrors.sses(ref, view.id, offset)
                          }
                      )
                    },
                    (fromPaginated & timeRange("instant") & extractUri & pathEndOrSingleSlash) {
                      (pagination, timeRange, uri) =>
                        implicit val searchJsonLdEncoder: JsonLdEncoder[SearchResults[FailedElemData]] =
                          searchResultsJsonLdEncoder(FailedElemLogRow.context, pagination, uri)
                        emit(
                          fetchView(id, ref)
                            .flatMap { view =>
                              projectionErrors.search(ViewRef(ref, view.id), pagination, timeRange)
                            }
                            .rejectOn[ViewNotFound]
                        )
                    }
                  )
                }
              },
              pathPrefix("projections") {
                concat(
                  // Manage all views' projections offsets
                  (pathPrefix("_") & pathPrefix("offset") & pathEndOrSingleSlash) {
                    concat(
                      // Fetch all composite view projection offsets
                      (get & authorizeFor(ref, Read)) {
                        emit(fetchView(id, ref).flatMap { v => details.offsets(ref, v.id, v.rev) })
                      },
                      // Remove all composite view projection offsets
                      (delete & authorizeFor(ref, Write)) {
                        emit(fullRebuild(ref, id))
                      }
                    )
                  },
                  // Fetch all views' projections statistics
                  (get & pathPrefix("_") & pathPrefix("statistics") & pathEndOrSingleSlash) {
                    authorizeFor(ref, Read).apply {
                      emit(
                        fetchView(id, ref).flatMap { v => details.statistics(v) }
                      )
                    }
                  },
                  // Manage a views' projection offset
                  (idSegment & pathPrefix("offset") & pathEndOrSingleSlash) { projectionId =>
                    concat(
                      // Fetch a composite view projection offset
                      (get & authorizeFor(ref, Read)) {
                        emit(
                          fetchProjection(id, projectionId, ref)
                            .flatMap { v =>
                              details.projectionOffsets(ref, v.id, v.rev, v.value._2.id)
                            }
                            .rejectOn[ViewNotFound]
                        )
                      },
                      // Remove a composite view projection offset
                      (delete & authorizeFor(ref, Write)) {
                        emit(partialRebuild(ref, id, projectionId))
                      }
                    )
                  },
                  // Fetch a views' projection statistics
                  (get & idSegment & pathPrefix("statistics") & pathEndOrSingleSlash) { projectionId =>
                    authorizeFor(ref, Read).apply {
                      emit(
                        fetchProjection(id, projectionId, ref)
                          .flatMap { v =>
                            details.projectionStatistics(v.map(_._1), v.value._2.id)
                          }
                          .rejectOn[ViewNotFound]
                      )
                    }
                  }
                )
              },
              pathPrefix("sources") {
                concat(
                  // Fetch all views' sources statistics
                  (get & pathPrefix("_") & pathPrefix("statistics") & pathEndOrSingleSlash) {
                    authorizeFor(ref, Read).apply {
                      emit(fetchView(id, ref).flatMap {
                        details.statistics
                      })
                    }
                  },
                  // Fetch a views' sources statistics
                  (get & idSegment & pathPrefix("statistics") & pathEndOrSingleSlash) { projectionId =>
                    authorizeFor(ref, Read).apply {
                      emit(fetchSource(id, projectionId, ref).flatMap { v =>
                        details.sourceStatistics(v.map(_._1), v.value._2.id)
                      })
                    }
                  }
                )
              }
            )
          }
        }
      }
    }

  private def fetchOffsets(project: ProjectRef, id: IdSegment) =
    fetchView(id, project).flatMap { v => details.offsets(project, v.id, v.rev) }

  private def fullRestart(project: ProjectRef, id: IdSegment)(implicit s: Subject) =
    for {
      v <- fetchView(id, project)
      o <- details.offsets(project, v.id, v.rev)
      _ <- projections.fullRestart(project, v.id)
    } yield o.map(_.copy(offset = Offset.Start))

  private def fullRebuild(project: ProjectRef, id: IdSegment)(implicit s: Subject) =
    for {
      v <- fetchView(id, project)
      o <- details.offsets(project, v.id, v.rev)
      _ <- projections.fullRebuild(project, v.id)
    } yield o.map(_.copy(offset = Offset.Start))

  private def partialRebuild(project: ProjectRef, id: IdSegment, projectionId: IdSegment)(implicit s: Subject) =
    for {
      res   <- fetchProjection(id, projectionId, project)
      (v, p) = res.value
      o     <- details.projectionOffsets(project, v.id, res.rev, p.id)
      _     <- projections.partialRebuild(project, v.id, p.id)
    } yield o.map(_.copy(offset = Offset.Start))

}

object CompositeViewsIndexingRoutes {

  type FetchView       = (IdSegmentRef, ProjectRef) => IO[CompositeViewRejection, ViewResource]
  type FetchSource     = (IdSegment, IdSegment, ProjectRef) => IO[CompositeViewRejection, ViewSourceResource]
  type FetchProjection = (IdSegment, IdSegment, ProjectRef) => IO[CompositeViewRejection, ViewProjectionResource]

  /**
    * @return
    *   the [[Route]] for composite views.
    */
  def apply(
      identities: Identities,
      aclCheck: AclCheck,
      fetchView: FetchView,
      fetchProjection: FetchProjection,
      fetchSource: FetchSource,
      statistics: CompositeIndexingDetails,
      projections: CompositeProjections,
      projectionErrors: ProjectionErrors,
      schemeDirectives: DeltaSchemeDirectives
  )(implicit
      baseUri: BaseUri,
      paginationConfig: PaginationConfig,
      s: Scheduler,
      cr: RemoteContextResolution,
      ordering: JsonKeyOrdering
  ): Route =
    new CompositeViewsIndexingRoutes(
      identities,
      aclCheck,
      fetchView,
      fetchProjection,
      fetchSource,
      statistics,
      projections,
      projectionErrors,
      schemeDirectives
    ).routes
}
