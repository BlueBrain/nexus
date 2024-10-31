package ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.routes

import akka.http.scaladsl.server.Route
import cats.effect.IO
import cats.syntax.all._
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
import ch.epfl.bluebrain.nexus.delta.kernel.circe.CirceUnmarshalling
import ch.epfl.bluebrain.nexus.delta.sdk.directives.{AuthDirectives, DeltaDirectives}
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

class CompositeViewsIndexingRoutes(
    identities: Identities,
    aclCheck: AclCheck,
    fetchView: FetchView,
    expandId: ExpandId,
    details: CompositeIndexingDetails,
    projections: CompositeProjections,
    projectionErrors: ProjectionErrors
)(implicit
    baseUri: BaseUri,
    paginationConfig: PaginationConfig,
    cr: RemoteContextResolution,
    ordering: JsonKeyOrdering
) extends AuthDirectives(identities, aclCheck)
    with DeltaDirectives
    with CirceUnmarshalling
    with RdfMarshalling
    with ElasticSearchViewsDirectives
    with BlazegraphViewsDirectives {

  implicit private val offsetsSearchJsonLdEncoder: JsonLdEncoder[SearchResults[ProjectionOffset]] =
    searchResultsJsonLdEncoder(ContextValue(contexts.offset))

  implicit private val statisticsSearchJsonLdEncoder: JsonLdEncoder[SearchResults[ProjectionStatistics]] =
    searchResultsJsonLdEncoder(ContextValue(contexts.statistics))

  def routes: Route =
    pathPrefix("views") {
      extractCaller { implicit caller =>
        projectRef { implicit project =>
          idSegment { id =>
            concat(
              // Manage composite view offsets
              (pathPrefix("offset") & pathEndOrSingleSlash) {
                concat(
                  // Fetch all composite view offsets
                  (get & authorizeFor(project, Read)) {
                    emit(fetchOffsets(project, id).attemptNarrow[CompositeViewRejection].rejectOn[ViewNotFound])
                  },
                  // Remove all composite view offsets (restart the view)
                  (delete & authorizeFor(project, Write)) {
                    emit(fullRestart(project, id).attemptNarrow[CompositeViewRejection].rejectOn[ViewNotFound])
                  }
                )
              },
              // Fetch composite indexing description
              (pathPrefix("description") & pathEndOrSingleSlash & get) {
                authorizeFor(project, Read).apply {
                  emit(
                    fetchView(id, project)
                      .flatMap(details.description)
                      .attemptNarrow[CompositeViewRejection]
                      .rejectOn[ViewNotFound]
                  )
                }
              },
              // Fetch composite view statistics
              (pathPrefix("statistics") & pathEndOrSingleSlash & get) {
                authorizeFor(project, Read).apply {
                  emit(
                    fetchView(id, project)
                      .flatMap(details.statistics)
                      .attemptNarrow[CompositeViewRejection]
                      .rejectOn[ViewNotFound]
                  )
                }
              },
              // Fetch elastic search view indexing failures
              (pathPrefix("failures") & get) {
                authorizeFor(project, Write).apply {
                  (fromPaginated & timeRange("instant") & extractUri & pathEndOrSingleSlash) {
                    (pagination, timeRange, uri) =>
                      implicit val searchJsonLdEncoder: JsonLdEncoder[SearchResults[FailedElemData]] =
                        searchResultsJsonLdEncoder(FailedElemLogRow.context, pagination, uri)
                      emit(
                        fetchView(id, project)
                          .flatMap { view =>
                            projectionErrors.search(view.ref, pagination, timeRange)
                          }
                          .attemptNarrow[CompositeViewRejection]
                          .rejectOn[ViewNotFound]
                      )
                  }
                }
              },
              pathPrefix("projections") {
                concat(
                  // Manage all views' projections offsets
                  (pathPrefix("_") & pathPrefix("offset") & pathEndOrSingleSlash) {
                    concat(
                      // Fetch all composite view projection offsets
                      (get & authorizeFor(project, Read)) {
                        emit(fetchView(id, project).flatMap { v => details.offsets(v.indexingRef) })
                      },
                      // Remove all composite view projection offsets
                      (delete & authorizeFor(project, Write)) {
                        emit(fullRebuild(project, id))
                      }
                    )
                  },
                  // Fetch all views' projections statistics
                  (get & pathPrefix("_") & pathPrefix("statistics") & pathEndOrSingleSlash) {
                    authorizeFor(project, Read).apply {
                      emit(fetchView(id, project).flatMap { v => details.statistics(v) })
                    }
                  },
                  // Manage a views' projection offset
                  (idSegment & pathPrefix("offset") & pathEndOrSingleSlash) { projectionId =>
                    concat(
                      // Fetch a composite view projection offset
                      (get & authorizeFor(project, Read)) {
                        emit(
                          projectionOffsets(project, id, projectionId)
                            .attemptNarrow[CompositeViewRejection]
                            .rejectOn[ViewNotFound]
                        )
                      },
                      // Remove a composite view projection offset
                      (delete & authorizeFor(project, Write)) {
                        emit(partialRebuild(project, id, projectionId))
                      }
                    )
                  },
                  // Fetch a views' projection statistics
                  (get & idSegment & pathPrefix("statistics") & pathEndOrSingleSlash) { projectionId =>
                    authorizeFor(project, Read).apply {
                      emit(
                        projectionStatistics(project, id, projectionId)
                          .attemptNarrow[CompositeViewRejection]
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
                    authorizeFor(project, Read).apply {
                      emit(fetchView(id, project).flatMap {
                        details.statistics
                      })
                    }
                  },
                  // Fetch a views' sources statistics
                  (get & idSegment & pathPrefix("statistics") & pathEndOrSingleSlash) { sourceId =>
                    authorizeFor(project, Read).apply {
                      emit(sourceStatistics(project, id, sourceId))
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
      IO.fromEither(view.projection(id))
    }

  private def fetchSource(view: ActiveViewDef, sourceId: IdSegment) =
    expandId(sourceId, view.project).flatMap { id =>
      IO.fromEither(view.source(id))
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
      projectionErrors: ProjectionErrors
  )(implicit
      baseUri: BaseUri,
      paginationConfig: PaginationConfig,
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
      projectionErrors
    ).routes
}
