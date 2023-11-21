package ch.epfl.bluebrain.nexus.delta.plugins.graph.analytics.routes

import akka.http.scaladsl.server.Route
import cats.effect.IO
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.routes.ElasticSearchViewsDirectives.extractQueryParams
import ch.epfl.bluebrain.nexus.delta.plugins.graph.analytics.permissions.query
import ch.epfl.bluebrain.nexus.delta.plugins.graph.analytics.{GraphAnalytics, GraphAnalyticsViewsQuery}
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.RemoteContextResolution
import ch.epfl.bluebrain.nexus.delta.rdf.utils.JsonKeyOrdering
import ch.epfl.bluebrain.nexus.delta.sdk.acls.AclCheck
import ch.epfl.bluebrain.nexus.delta.sdk.circe.CirceUnmarshalling
import ch.epfl.bluebrain.nexus.delta.sdk.directives.DeltaDirectives._
import ch.epfl.bluebrain.nexus.delta.sdk.directives.{AuthDirectives, DeltaSchemeDirectives}
import ch.epfl.bluebrain.nexus.delta.sdk.identities.Identities
import ch.epfl.bluebrain.nexus.delta.sdk.marshalling.RdfMarshalling
import ch.epfl.bluebrain.nexus.delta.sdk.model.BaseUri
import ch.epfl.bluebrain.nexus.delta.sdk.permissions.Permissions.resources.{read => Read}
import ch.epfl.bluebrain.nexus.delta.sourcing.ProgressStatistics
import ch.epfl.bluebrain.nexus.delta.sourcing.model.ProjectRef
import io.circe.JsonObject

/**
  * The graph analytics routes.
  *
  * @param identities
  *   the identity module
  * @param aclCheck
  *   to check acls
  * @param graphAnalytics
  *   analytics the graph analytics module
  * @param fetchStatistics
  *   how to fetch the statistics for the graph analytics for a given project
  * @param schemeDirectives
  *   directives related to orgs and projects
  */
class GraphAnalyticsRoutes(
    identities: Identities,
    aclCheck: AclCheck,
    graphAnalytics: GraphAnalytics,
    fetchStatistics: ProjectRef => IO[ProgressStatistics],
    schemeDirectives: DeltaSchemeDirectives,
    viewsQuery: GraphAnalyticsViewsQuery
)(implicit baseUri: BaseUri, cr: RemoteContextResolution, ordering: JsonKeyOrdering)
    extends AuthDirectives(identities, aclCheck)
    with CirceUnmarshalling
    with RdfMarshalling {
  import schemeDirectives._

  def routes: Route =
    baseUriPrefix(baseUri.prefix) {
      pathPrefix("graph-analytics") {
        extractCaller { implicit caller =>
          resolveProjectRef { projectRef =>
            concat(
              get {
                concat(
                  // Fetch relationships
                  (pathPrefix("relationships") & pathEndOrSingleSlash) {
                    authorizeFor(projectRef, Read).apply {
                      emit(graphAnalytics.relationships(projectRef))
                    }
                  },
                  // Fetch properties for a type
                  (pathPrefix("properties") & idSegment & pathEndOrSingleSlash) { tpe =>
                    authorizeFor(projectRef, Read).apply {
                      emit(graphAnalytics.properties(projectRef, tpe))
                    }
                  },
                  // Fetch the statistics
                  (pathPrefix("statistics") & pathEndOrSingleSlash) {
                    authorizeFor(projectRef, Read).apply {
                      emit(fetchStatistics(projectRef))
                    }
                  }
                )
              },
              post {
                // Search a graph analytics view
                (pathPrefix("_search") & pathEndOrSingleSlash) {
                  authorizeFor(projectRef, query).apply {
                    (extractQueryParams & entity(as[JsonObject])) { (qp, query) =>
                      emit(viewsQuery.query(projectRef, query, qp))
                    }
                  }
                }
              }
            )
          }
        }
      }
    }
}
