package ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.routes

import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server._
import cats.effect.{ContextShift, IO}
import cats.implicits.catsSyntaxApplicativeError
import ch.epfl.bluebrain.nexus.delta.kernel.effect.migration.toCatsIOOps
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.ElasticSearchViewsQuery
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.indexing.IndexingViewDef.ActiveViewDef
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.model.ElasticSearchViewRejection._
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.model._
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.model.permissions.{read => Read, write => Write}
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.routes.ElasticSearchIndexingRoutes.FetchIndexingView
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.JsonLdContext.keywords
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.{ContextValue, RemoteContextResolution}
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.encoder.JsonLdEncoder
import ch.epfl.bluebrain.nexus.delta.rdf.utils.JsonKeyOrdering
import ch.epfl.bluebrain.nexus.delta.sdk.acls.AclCheck
import ch.epfl.bluebrain.nexus.delta.sdk.ce.DeltaDirectives._
import ch.epfl.bluebrain.nexus.delta.sdk.circe.CirceUnmarshalling
import ch.epfl.bluebrain.nexus.delta.sdk.directives.{AuthDirectives, DeltaSchemeDirectives}
import ch.epfl.bluebrain.nexus.delta.sdk.identities.Identities
import ch.epfl.bluebrain.nexus.delta.sdk.implicits._
import ch.epfl.bluebrain.nexus.delta.sdk.marshalling.RdfMarshalling
import ch.epfl.bluebrain.nexus.delta.sdk.model._
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.SearchResults.searchResultsJsonLdEncoder
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.{PaginationConfig, SearchResults}
import ch.epfl.bluebrain.nexus.delta.sourcing.ProgressStatistics
import ch.epfl.bluebrain.nexus.delta.sourcing.model.FailedElemLogRow.FailedElemData
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{FailedElemLogRow, ProjectRef}
import ch.epfl.bluebrain.nexus.delta.sourcing.offset.Offset
import ch.epfl.bluebrain.nexus.delta.sourcing.projections.{ProjectionErrors, Projections}
import io.circe.Encoder
import io.circe.generic.semiauto.deriveEncoder
import io.circe.syntax._

/**
  * The elasticsearch views routes
  *
  * @param identities
  *   the identity module
  * @param aclCheck
  *   to check acls
  * @param fetch
  *   how to fetch an Elasticsearch view
  * @param projections
  *   the projections module
  * @param projectionErrors
  *   the projection errors module
  * @param schemeDirectives
  *   directives related to orgs and projects
  */
final class ElasticSearchIndexingRoutes(
    identities: Identities,
    aclCheck: AclCheck,
    fetch: FetchIndexingView,
    projections: Projections,
    projectionErrors: ProjectionErrors,
    schemeDirectives: DeltaSchemeDirectives,
    viewsQuery: ElasticSearchViewsQuery
)(implicit
    baseUri: BaseUri,
    paginationConfig: PaginationConfig,
    c: ContextShift[IO],
    cr: RemoteContextResolution,
    ordering: JsonKeyOrdering
) extends AuthDirectives(identities, aclCheck)
    with CirceUnmarshalling
    with RdfMarshalling {

  import schemeDirectives._

  implicit private val viewStatisticEncoder: Encoder.AsObject[ProgressStatistics] =
    deriveEncoder[ProgressStatistics].mapJsonObject(_.add(keywords.tpe, "ViewStatistics".asJson))

  implicit private val viewStatisticJsonLdEncoder: JsonLdEncoder[ProgressStatistics] =
    JsonLdEncoder.computeFromCirce(ContextValue(Vocabulary.contexts.statistics))

  def routes: Route =
    pathPrefix("views") {
      extractCaller { implicit caller =>
        resolveProjectRef.apply { ref =>
          concat(
            idSegment { id =>
              concat(
                // Fetch an elasticsearch view statistics
                (pathPrefix("statistics") & get & pathEndOrSingleSlash) {
                  authorizeFor(ref, Read).apply {
                    emit(
                      fetch(id, ref)
                        .flatMap(v => projections.statistics(ref, v.selectFilter, v.projection).toCatsIO)
                        .attemptNarrow[ElasticSearchViewRejection]
                        .rejectOn[ViewNotFound]
                    )
                  }
                },
                // Fetch elastic search view indexing failures
                (pathPrefix("failures") & get) {
                  authorizeFor(ref, Write).apply {
                    concat(
                      (pathPrefix("sse") & lastEventId) { offset =>
                        emit(
                          fetch(id, ref)
                            .map { view =>
                              projectionErrors.sses(view.ref.project, view.ref.viewId, offset)
                            }
                            .attemptNarrow[ElasticSearchViewRejection]
                        )
                      },
                      (fromPaginated & timeRange("instant") & extractUri & pathEndOrSingleSlash) {
                        (pagination, timeRange, uri) =>
                          implicit val searchJsonLdEncoder: JsonLdEncoder[SearchResults[FailedElemData]] =
                            searchResultsJsonLdEncoder(FailedElemLogRow.context, pagination, uri)
                          emit(
                            fetch(id, ref)
                              .flatMap { view =>
                                projectionErrors.search(view.ref, pagination, timeRange).toCatsIO
                              }
                              .attemptNarrow[ElasticSearchViewRejection]
                              .rejectOn[ViewNotFound]
                          )
                      }
                    )
                  }
                },
                // Manage an elasticsearch view offset
                (pathPrefix("offset") & pathEndOrSingleSlash) {
                  concat(
                    // Fetch an elasticsearch view offset
                    (get & authorizeFor(ref, Read)) {
                      emit(
                        fetch(id, ref)
                          .flatMap(v => projections.offset(v.projection).toCatsIO)
                          .attemptNarrow[ElasticSearchViewRejection]
                          .rejectOn[ViewNotFound]
                      )
                    },
                    // Remove an elasticsearch view offset (restart the view)
                    (delete & authorizeFor(ref, Write)) {
                      emit(
                        fetch(id, ref)
                          .flatMap { v => projections.scheduleRestart(v.projection).toCatsIO }
                          .as(Offset.start)
                          .attemptNarrow[ElasticSearchViewRejection]
                          .rejectOn[ViewNotFound]
                      )
                    }
                  )
                },
                // Get elasticsearch view mapping
                (pathPrefix("_mapping") & get & pathEndOrSingleSlash) {
                  emit(viewsQuery.mapping(id, ref).attemptNarrow[ElasticSearchViewRejection])
                }
              )
            }
          )
        }
      }
    }
}

object ElasticSearchIndexingRoutes {

  type FetchIndexingView = (IdSegment, ProjectRef) => IO[ActiveViewDef]

  /**
    * @return
    *   the [[Route]] for elasticsearch views
    */
  def apply(
      identities: Identities,
      aclCheck: AclCheck,
      fetch: FetchIndexingView,
      projections: Projections,
      projectionErrors: ProjectionErrors,
      schemeDirectives: DeltaSchemeDirectives,
      viewsQuery: ElasticSearchViewsQuery
  )(implicit
      baseUri: BaseUri,
      paginationConfig: PaginationConfig,
      c: ContextShift[IO],
      cr: RemoteContextResolution,
      ordering: JsonKeyOrdering
  ): Route =
    new ElasticSearchIndexingRoutes(
      identities,
      aclCheck,
      fetch,
      projections,
      projectionErrors,
      schemeDirectives,
      viewsQuery
    ).routes
}
