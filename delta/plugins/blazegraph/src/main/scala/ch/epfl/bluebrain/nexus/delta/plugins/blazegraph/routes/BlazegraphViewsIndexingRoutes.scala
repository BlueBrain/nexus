package ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.routes

import akka.http.scaladsl.server.Route
import cats.effect.IO
import cats.implicits.*
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.indexing.IndexingViewDef.ActiveViewDef
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.model.BlazegraphViewRejection.*
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.model.*
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.model.permissions.write as Write
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.routes.BlazegraphViewsIndexingRoutes.FetchIndexingView
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.contexts
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.JsonLdContext.keywords
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.{ContextValue, RemoteContextResolution}
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.encoder.JsonLdEncoder
import ch.epfl.bluebrain.nexus.delta.rdf.utils.JsonKeyOrdering
import ch.epfl.bluebrain.nexus.delta.sdk.acls.AclCheck
import ch.epfl.bluebrain.nexus.delta.kernel.circe.CirceUnmarshalling
import ch.epfl.bluebrain.nexus.delta.sdk.directives.{AuthDirectives, DeltaDirectives}
import ch.epfl.bluebrain.nexus.delta.sdk.identities.Identities
import ch.epfl.bluebrain.nexus.delta.sdk.implicits.*
import ch.epfl.bluebrain.nexus.delta.sdk.marshalling.RdfMarshalling
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.SearchResults.searchResultsJsonLdEncoder
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.{PaginationConfig, SearchResults}
import ch.epfl.bluebrain.nexus.delta.sdk.model.{BaseUri, IdSegment}
import ch.epfl.bluebrain.nexus.delta.sourcing.ProgressStatistics
import ch.epfl.bluebrain.nexus.delta.sourcing.model.FailedElemLogRow.FailedElemData
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{FailedElemLogRow, ProjectRef}
import ch.epfl.bluebrain.nexus.delta.sourcing.offset.Offset
import ch.epfl.bluebrain.nexus.delta.sourcing.projections.{ProjectionErrors, Projections}
import io.circe.Encoder
import io.circe.generic.semiauto.deriveEncoder
import io.circe.syntax.*

class BlazegraphViewsIndexingRoutes(
    fetch: FetchIndexingView,
    identities: Identities,
    aclCheck: AclCheck,
    projections: Projections,
    projectionErrors: ProjectionErrors
)(implicit
    baseUri: BaseUri,
    cr: RemoteContextResolution,
    ordering: JsonKeyOrdering,
    pc: PaginationConfig
) extends AuthDirectives(identities, aclCheck)
    with CirceUnmarshalling
    with DeltaDirectives
    with RdfMarshalling
    with BlazegraphViewsDirectives {

  implicit private val viewStatisticEncoder: Encoder.AsObject[ProgressStatistics] =
    deriveEncoder[ProgressStatistics].mapJsonObject(_.add(keywords.tpe, "ViewStatistics".asJson))

  implicit private val viewStatisticJsonLdEncoder: JsonLdEncoder[ProgressStatistics] =
    JsonLdEncoder.computeFromCirce(ContextValue(contexts.statistics))

  def routes: Route =
    pathPrefix("views") {
      extractCaller { implicit caller =>
        projectRef { implicit project =>
          idSegment { id =>
            concat(
              // Fetch a blazegraph view statistics
              (pathPrefix("statistics") & get & pathEndOrSingleSlash) {
                authorizeFor(project, permissions.read).apply {
                  emit(
                    fetch(id, project)
                      .flatMap(v => projections.statistics(project, v.selectFilter, v.projection))
                      .attemptNarrow[BlazegraphViewRejection]
                      .rejectOn[ViewNotFound]
                  )
                }
              },
              // Fetch blazegraph view indexing failures
              (pathPrefix("failures") & get) {
                authorizeFor(project, Write).apply {
                  (fromPaginated & timeRange("instant") & extractUri & pathEndOrSingleSlash) {
                    (pagination, timeRange, uri) =>
                      implicit val searchJsonLdEncoder: JsonLdEncoder[SearchResults[FailedElemData]] =
                        searchResultsJsonLdEncoder(FailedElemLogRow.context, pagination, uri)
                      emit(
                        fetch(id, project)
                          .flatMap { view =>
                            projectionErrors.search(view.ref, pagination, timeRange)
                          }
                          .attemptNarrow[BlazegraphViewRejection]
                          .rejectOn[ViewNotFound]
                      )
                  }
                }
              },
              // Manage an blazegraph view offset
              (pathPrefix("offset") & pathEndOrSingleSlash) {
                concat(
                  // Fetch a blazegraph view offset
                  (get & authorizeFor(project, permissions.read)) {
                    emit(
                      fetch(id, project)
                        .flatMap(v => projections.offset(v.projection))
                        .attemptNarrow[BlazegraphViewRejection]
                        .rejectOn[ViewNotFound]
                    )
                  },
                  // Remove an blazegraph view offset (restart the view)
                  (delete & authorizeFor(project, Write)) {
                    emit(
                      fetch(id, project)
                        .flatMap { r => projections.scheduleRestart(r.projection) }
                        .as(Offset.start)
                        .attemptNarrow[BlazegraphViewRejection]
                        .rejectOn[ViewNotFound]
                    )
                  }
                )
              }
            )
          }
        }
      }
    }
}

object BlazegraphViewsIndexingRoutes {

  type FetchIndexingView = (IdSegment, ProjectRef) => IO[ActiveViewDef]

  /**
    * @return
    *   the [[Route]] for BlazegraphViews
    */
  def apply(
      fetch: FetchIndexingView,
      identities: Identities,
      aclCheck: AclCheck,
      projections: Projections,
      projectionErrors: ProjectionErrors
  )(implicit
      baseUri: BaseUri,
      cr: RemoteContextResolution,
      ordering: JsonKeyOrdering,
      pc: PaginationConfig
  ): Route = {
    new BlazegraphViewsIndexingRoutes(
      fetch,
      identities,
      aclCheck,
      projections,
      projectionErrors
    ).routes
  }
}
