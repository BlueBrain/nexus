package ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.routes

import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import cats.effect.{ContextShift, IO}
import cats.implicits.catsSyntaxApplicativeError
import ch.epfl.bluebrain.nexus.delta.kernel.effect.migration._
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.indexing.IndexingViewDef.ActiveViewDef
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.model.BlazegraphViewRejection._
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.model._
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.model.permissions.{write => Write}
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.routes.BlazegraphViewsIndexingRoutes.FetchIndexingView
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.contexts
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.JsonLdContext.keywords
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
import ch.epfl.bluebrain.nexus.delta.sourcing.ProgressStatistics
import ch.epfl.bluebrain.nexus.delta.sourcing.model.FailedElemLogRow.FailedElemData
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{FailedElemLogRow, ProjectRef}
import ch.epfl.bluebrain.nexus.delta.sourcing.offset.Offset
import ch.epfl.bluebrain.nexus.delta.sourcing.projections.{ProjectionErrors, Projections}
import io.circe.Encoder
import io.circe.generic.semiauto.deriveEncoder
import io.circe.syntax._
import monix.bio.{IO => BIO}
import monix.execution.Scheduler
class BlazegraphViewsIndexingRoutes(
    fetch: FetchIndexingView,
    identities: Identities,
    aclCheck: AclCheck,
    projections: Projections,
    projectionErrors: ProjectionErrors,
    schemeDirectives: DeltaSchemeDirectives
)(implicit
    baseUri: BaseUri,
    s: Scheduler,
    c: ContextShift[IO],
    cr: RemoteContextResolution,
    ordering: JsonKeyOrdering,
    pc: PaginationConfig
) extends AuthDirectives(identities, aclCheck)
    with CirceUnmarshalling
    with DeltaDirectives
    with RdfMarshalling
    with BlazegraphViewsDirectives {

  import schemeDirectives._

  implicit private val viewStatisticEncoder: Encoder.AsObject[ProgressStatistics] =
    deriveEncoder[ProgressStatistics].mapJsonObject(_.add(keywords.tpe, "ViewStatistics".asJson))

  implicit private val viewStatisticJsonLdEncoder: JsonLdEncoder[ProgressStatistics] =
    JsonLdEncoder.computeFromCirce(ContextValue(contexts.statistics))

  def routes: Route =
    pathPrefix("views") {
      extractCaller { implicit caller =>
        resolveProjectRef.apply { implicit ref =>
          idSegment { id =>
            concat(
              // Fetch a blazegraph view statistics
              (pathPrefix("statistics") & get & pathEndOrSingleSlash) {
                authorizeFor(ref, permissions.read).apply {
                  emit(
                    fetch(id, ref)
                      .flatMap(v => projections.statistics(ref, v.selectFilter, v.projection))
                      .rejectOn[ViewNotFound]
                  )
                }
              },
              // Fetch balzegraph view indexing failures
              (pathPrefix("failures") & get) {
                authorizeFor(ref, Write).apply {
                  concat(
                    (pathPrefix("sse") & lastEventId) { offset =>
                      emit(
                        fetch(id, ref).toCatsIO
                          .map { view =>
                            projectionErrors.sses(view.ref.project, view.ref.viewId, offset)
                          }
                          .attemptNarrow[BlazegraphViewRejection]
                      )
                    },
                    (fromPaginated & timeRange("instant") & extractUri & pathEndOrSingleSlash) {
                      (pagination, timeRange, uri) =>
                        implicit val searchJsonLdEncoder: JsonLdEncoder[SearchResults[FailedElemData]] =
                          searchResultsJsonLdEncoder(FailedElemLogRow.context, pagination, uri)
                        emit(
                          fetch(id, ref)
                            .flatMap { view =>
                              projectionErrors.search(view.ref, pagination, timeRange)
                            }
                            .rejectOn[ViewNotFound]
                        )
                    }
                  )
                }
              },
              // Manage an blazegraph view offset
              (pathPrefix("offset") & pathEndOrSingleSlash) {
                concat(
                  // Fetch a blazegraph view offset
                  (get & authorizeFor(ref, permissions.read)) {
                    emit(
                      fetch(id, ref)
                        .flatMap(v => projections.offset(v.projection))
                        .rejectOn[ViewNotFound]
                    )
                  },
                  // Remove an blazegraph view offset (restart the view)
                  (delete & authorizeFor(ref, Write)) {
                    emit(
                      fetch(id, ref)
                        .flatMap { r => projections.scheduleRestart(r.projection) }
                        .as(Offset.start)
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

  type FetchIndexingView = (IdSegment, ProjectRef) => BIO[BlazegraphViewRejection, ActiveViewDef]

  /**
    * @return
    *   the [[Route]] for BlazegraphViews
    */
  def apply(
      fetch: FetchIndexingView,
      identities: Identities,
      aclCheck: AclCheck,
      projections: Projections,
      projectionErrors: ProjectionErrors,
      schemeDirectives: DeltaSchemeDirectives
  )(implicit
      baseUri: BaseUri,
      s: Scheduler,
      c: ContextShift[IO],
      cr: RemoteContextResolution,
      ordering: JsonKeyOrdering,
      pc: PaginationConfig
  ): Route = {
    new BlazegraphViewsIndexingRoutes(
      fetch,
      identities,
      aclCheck,
      projections,
      projectionErrors: ProjectionErrors,
      schemeDirectives
    ).routes
  }
}
