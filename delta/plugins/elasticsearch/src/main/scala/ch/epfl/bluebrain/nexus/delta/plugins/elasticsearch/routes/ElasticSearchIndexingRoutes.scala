package ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.routes

import akka.http.scaladsl.server.*
import cats.effect.IO
import cats.implicits.*
import ch.epfl.bluebrain.nexus.delta.kernel.circe.CirceUnmarshalling
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.ElasticSearchViewsQuery
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.indexing.IndexingViewDef.ActiveViewDef
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.model.ElasticSearchViewRejection.*
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.model.*
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.model.permissions.{read as Read, write as Write}
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.routes.ElasticSearchIndexingRoutes.FetchIndexingView
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.JsonLdContext.keywords
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.{ContextValue, RemoteContextResolution}
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.encoder.JsonLdEncoder
import ch.epfl.bluebrain.nexus.delta.rdf.utils.JsonKeyOrdering
import ch.epfl.bluebrain.nexus.delta.sdk.acls.AclCheck
import ch.epfl.bluebrain.nexus.delta.sdk.directives.AuthDirectives
import ch.epfl.bluebrain.nexus.delta.sdk.directives.DeltaDirectives.*
import ch.epfl.bluebrain.nexus.delta.sdk.identities.Identities
import ch.epfl.bluebrain.nexus.delta.sdk.implicits.*
import ch.epfl.bluebrain.nexus.delta.sdk.marshalling.RdfMarshalling
import ch.epfl.bluebrain.nexus.delta.sdk.model.*
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.SearchResults.searchResultsJsonLdEncoder
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.{PaginationConfig, SearchResults}
import ch.epfl.bluebrain.nexus.delta.sourcing.ProgressStatistics
import ch.epfl.bluebrain.nexus.delta.sourcing.model.FailedElemLogRow.FailedElemData
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{FailedElemLogRow, ProjectRef}
import ch.epfl.bluebrain.nexus.delta.sourcing.offset.Offset
import ch.epfl.bluebrain.nexus.delta.sourcing.projections.{ProjectionErrors, Projections}
import io.circe.Encoder
import io.circe.generic.semiauto.deriveEncoder
import io.circe.syntax.*

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
  */
final class ElasticSearchIndexingRoutes(
    identities: Identities,
    aclCheck: AclCheck,
    fetch: FetchIndexingView,
    projections: Projections,
    projectionErrors: ProjectionErrors,
    viewsQuery: ElasticSearchViewsQuery
)(implicit
    baseUri: BaseUri,
    paginationConfig: PaginationConfig,
    cr: RemoteContextResolution,
    ordering: JsonKeyOrdering
) extends AuthDirectives(identities, aclCheck)
    with CirceUnmarshalling
    with RdfMarshalling {

  implicit private val viewStatisticEncoder: Encoder.AsObject[ProgressStatistics] =
    deriveEncoder[ProgressStatistics].mapJsonObject(_.add(keywords.tpe, "ViewStatistics".asJson))

  implicit private val viewStatisticJsonLdEncoder: JsonLdEncoder[ProgressStatistics] =
    JsonLdEncoder.computeFromCirce(ContextValue(Vocabulary.contexts.statistics))

  def routes: Route =
    pathPrefix("views") {
      extractCaller { implicit caller =>
        projectRef { project =>
          concat(
            idSegment { id =>
              concat(
                // Fetch an elasticsearch view statistics
                (pathPrefix("statistics") & get & pathEndOrSingleSlash) {
                  authorizeFor(project, Read).apply {
                    emit(
                      fetch(id, project)
                        .flatMap(v => projections.statistics(project, v.selectFilter, v.projection))
                        .attemptNarrow[ElasticSearchViewRejection]
                        .rejectOn[ViewNotFound]
                    )
                  }
                },
                // Fetch elastic search view indexing failures
                (pathPrefix("failures") & get) {
                  authorizeFor(project, Write).apply {
                    (fromPaginated & timeRange("instant") & extractHttp4sUri & pathEndOrSingleSlash) {
                      (pagination, timeRange, uri) =>
                        implicit val searchJsonLdEncoder: JsonLdEncoder[SearchResults[FailedElemData]] =
                          searchResultsJsonLdEncoder(FailedElemLogRow.context, pagination, uri)
                        emit(
                          fetch(id, project)
                            .flatMap { view =>
                              projectionErrors.search(view.ref, pagination, timeRange)
                            }
                            .attemptNarrow[ElasticSearchViewRejection]
                            .rejectOn[ViewNotFound]
                        )
                    }
                  }
                },
                // Manage an elasticsearch view offset
                (pathPrefix("offset") & pathEndOrSingleSlash) {
                  concat(
                    // Fetch an elasticsearch view offset
                    (get & authorizeFor(project, Read)) {
                      emit(
                        fetch(id, project)
                          .flatMap(v => projections.offset(v.projection))
                          .attemptNarrow[ElasticSearchViewRejection]
                          .rejectOn[ViewNotFound]
                      )
                    },
                    // Remove an elasticsearch view offset (restart the view)
                    (delete & authorizeFor(project, Write)) {
                      emit(
                        fetch(id, project)
                          .flatMap { v => projections.scheduleRestart(v.projection) }
                          .as(Offset.start)
                          .attemptNarrow[ElasticSearchViewRejection]
                          .rejectOn[ViewNotFound]
                      )
                    }
                  )
                },
                // Get elasticsearch view mapping
                (pathPrefix("_mapping") & get & pathEndOrSingleSlash) {
                  emit(viewsQuery.mapping(id, project).attemptNarrow[ElasticSearchViewRejection])
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
      viewsQuery: ElasticSearchViewsQuery
  )(implicit
      baseUri: BaseUri,
      paginationConfig: PaginationConfig,
      cr: RemoteContextResolution,
      ordering: JsonKeyOrdering
  ): Route =
    new ElasticSearchIndexingRoutes(
      identities,
      aclCheck,
      fetch,
      projections,
      projectionErrors,
      viewsQuery
    ).routes
}
