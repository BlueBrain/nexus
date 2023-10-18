package ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.routes

import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import cats.effect.{ContextShift, IO}
import cats.syntax.all._
import ch.epfl.bluebrain.nexus.delta.kernel.effect.migration._
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.routes.BlazegraphViewsDirectives
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.indexing.CompositeViewDef.ActiveViewDef
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.model.CompositeViewRejection._
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.model._
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.model.permissions.{read => Read, write => Write}
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.projections.{CompositeIndexingDetails, CompositeProjections}
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.{ExpandId, FetchView}
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
import ch.epfl.bluebrain.nexus.delta.sdk.model.{BaseUri, IdSegment}
import ch.epfl.bluebrain.nexus.delta.sourcing.model.FailedElemLogRow.FailedElemData
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.Subject
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{FailedElemLogRow, ProjectRef}
import ch.epfl.bluebrain.nexus.delta.sourcing.offset.Offset
import ch.epfl.bluebrain.nexus.delta.sourcing.projections.ProjectionErrors
import monix.bio.{IO => BIO}
import monix.execution.Scheduler
class CompositeViewsIndexingRoutes(
    identities: Identities,
    aclCheck: AclCheck,
    fetchView: FetchView,
    expandId: ExpandId,
    details: CompositeIndexingDetails,
    projections: CompositeProjections,
    projectionErrors: ProjectionErrors,
    schemeDirectives: DeltaSchemeDirectives
)(implicit
    baseUri: BaseUri,
    paginationConfig: PaginationConfig,
    s: Scheduler,
    c: ContextShift[IO],
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
              // Fetch composite indexing description
              (get & pathPrefix("description") & pathEndOrSingleSlash) {
                authorizeFor(ref, Read).apply {
                  emit(fetchView(id, ref).flatMap(details.description).rejectOn[ViewNotFound])
                }
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
                        fetchView(id, ref).toCatsIO
                          .map { view =>
                            projectionErrors.sses(view.project, view.id, offset)
                          }
                          .attemptNarrow[CompositeViewRejection]
                      )
                    },
                    (fromPaginated & timeRange("instant") & extractUri & pathEndOrSingleSlash) {
                      (pagination, timeRange, uri) =>
                        implicit val searchJsonLdEncoder: JsonLdEncoder[SearchResults[FailedElemData]] =
                          searchResultsJsonLdEncoder(FailedElemLogRow.context, pagination, uri)
                        emit(
                          fetchView(id, ref)
                            .flatMap { view =>
                              projectionErrors.search(view.ref, pagination, timeRange)
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
                        emit(fetchView(id, ref).flatMap { v => details.offsets(v.indexingRef) })
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
                      emit(fetchView(id, ref).flatMap { v => details.statistics(v) })
                    }
                  },
                  // Manage a views' projection offset
                  (idSegment & pathPrefix("offset") & pathEndOrSingleSlash) { projectionId =>
                    concat(
                      // Fetch a composite view projection offset
                      (get & authorizeFor(ref, Read)) {
                        emit(projectionOffsets(ref, id, projectionId).rejectOn[ViewNotFound])
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
                      emit(projectionStatistics(ref, id, projectionId).rejectOn[ViewNotFound])
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
                  (get & idSegment & pathPrefix("statistics") & pathEndOrSingleSlash) { sourceId =>
                    authorizeFor(ref, Read).apply {
                      emit(sourceStatistics(ref, id, sourceId))
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
    fetchView(id, project).flatMap { v => details.offsets(v.indexingRef) }

  private def projectionOffsets(project: ProjectRef, id: IdSegment, projectionId: IdSegment) =
    for {
      view       <- fetchView(id, project)
      projection <- fetchProjection(view, projectionId)
      offsets    <- details.projectionOffsets(view.indexingRef, projection.id)
    } yield offsets

  private def projectionStatistics(project: ProjectRef, id: IdSegment, projectionId: IdSegment) =
    for {
      view       <- fetchView(id, project)
      projection <- fetchProjection(view, projectionId)
      offsets    <- details.projectionStatistics(view, projection.id)
    } yield offsets

  private def sourceStatistics(project: ProjectRef, id: IdSegment, sourceId: IdSegment) =
    for {
      view    <- fetchView(id, project)
      source  <- fetchSource(view, sourceId)
      offsets <- details.sourceStatistics(view, source.id)
    } yield offsets

  private def fullRestart(project: ProjectRef, id: IdSegment)(implicit s: Subject) =
    for {
      view    <- fetchView(id, project)
      offsets <- details.offsets(view.indexingRef)
      _       <- projections.scheduleFullRestart(view.ref)
    } yield offsets.map(_.copy(offset = Offset.Start))

  private def fullRebuild(project: ProjectRef, id: IdSegment)(implicit s: Subject) =
    for {
      view    <- fetchView(id, project)
      offsets <- details.offsets(view.indexingRef)
      _       <- projections.scheduleFullRebuild(view.ref)
    } yield offsets.map(_.copy(offset = Offset.Start))

  private def partialRebuild(project: ProjectRef, id: IdSegment, projectionId: IdSegment)(implicit s: Subject) =
    for {
      view       <- fetchView(id, project)
      projection <- fetchProjection(view, projectionId)
      offsets    <- details.projectionOffsets(view.indexingRef, projection.id)
      _          <- projections.schedulePartialRebuild(view.ref, projection.id)
    } yield offsets.map(_.copy(offset = Offset.Start))

  private def fetchProjection(view: ActiveViewDef, projectionId: IdSegment) =
    expandId(projectionId, view.project).flatMap { id =>
      BIO.fromEither(view.projection(id))
    }

  private def fetchSource(view: ActiveViewDef, sourceId: IdSegment) =
    expandId(sourceId, view.project).flatMap { id =>
      BIO.fromEither(view.source(id))
    }

}

object CompositeViewsIndexingRoutes {

  /**
    * @return
    *   the [[Route]] for composite views.
    */
  def apply(
      identities: Identities,
      aclCheck: AclCheck,
      fetchView: FetchView,
      expandId: ExpandId,
      statistics: CompositeIndexingDetails,
      projections: CompositeProjections,
      projectionErrors: ProjectionErrors,
      schemeDirectives: DeltaSchemeDirectives
  )(implicit
      baseUri: BaseUri,
      paginationConfig: PaginationConfig,
      s: Scheduler,
      c: ContextShift[IO],
      cr: RemoteContextResolution,
      ordering: JsonKeyOrdering
  ): Route =
    new CompositeViewsIndexingRoutes(
      identities,
      aclCheck,
      fetchView,
      expandId,
      statistics,
      projections,
      projectionErrors,
      schemeDirectives
    ).routes
}
