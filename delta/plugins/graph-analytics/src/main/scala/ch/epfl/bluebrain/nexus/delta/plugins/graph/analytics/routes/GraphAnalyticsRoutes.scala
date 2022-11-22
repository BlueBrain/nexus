package ch.epfl.bluebrain.nexus.delta.plugins.graph.analytics.routes

import akka.http.scaladsl.server.Directives.{concat, get, pathEndOrSingleSlash, pathPrefix}
import akka.http.scaladsl.server.Route
import ch.epfl.bluebrain.nexus.delta.plugins.graph.analytics.GraphAnalytics
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.RemoteContextResolution
import ch.epfl.bluebrain.nexus.delta.rdf.utils.JsonKeyOrdering
import ch.epfl.bluebrain.nexus.delta.sdk.acls.AclCheck
import ch.epfl.bluebrain.nexus.delta.sdk.circe.CirceUnmarshalling
import ch.epfl.bluebrain.nexus.delta.sdk.directives.DeltaDirectives.{baseUriPrefix, emit, idSegment}
import ch.epfl.bluebrain.nexus.delta.sdk.directives.{AuthDirectives, DeltaSchemeDirectives}
import ch.epfl.bluebrain.nexus.delta.sdk.identities.Identities
import ch.epfl.bluebrain.nexus.delta.sdk.marshalling.RdfMarshalling
import ch.epfl.bluebrain.nexus.delta.sdk.model.BaseUri
import ch.epfl.bluebrain.nexus.delta.sdk.permissions.Permissions.resources.{read => Read}
import ch.epfl.bluebrain.nexus.delta.sourcing.projections.Projections
import kamon.instrumentation.akka.http.TracingDirectives.operationName
import monix.execution.Scheduler

/**
  * The graph analytics routes.
  *
  * @param identities
  *   the identity module
  * @param aclCheck
  *   to check acls
  * @param graphAnalytics
  *   analytics the graph analytics module
  * @param projections
  *   the projections module
  * @param schemeDirectives
  *   directives related to orgs and projects
  */
class GraphAnalyticsRoutes(
    identities: Identities,
    aclCheck: AclCheck,
    graphAnalytics: GraphAnalytics,
    projections: Projections,
    schemeDirectives: DeltaSchemeDirectives
)(implicit baseUri: BaseUri, s: Scheduler, cr: RemoteContextResolution, ordering: JsonKeyOrdering)
    extends AuthDirectives(identities, aclCheck)
    with CirceUnmarshalling
    with RdfMarshalling {
  import baseUri.prefixSegment
  import schemeDirectives._

  def routes: Route =
    baseUriPrefix(baseUri.prefix) {
      pathPrefix("graph-analytics") {
        extractCaller { implicit caller =>
          (get & resolveProjectRef) { projectRef =>
            concat(
              // Fetch relationships
              (pathPrefix("relationships") & pathEndOrSingleSlash) {
                operationName(s"$prefixSegment/graph-analytics/{org}/{project}/relationships") {
                  authorizeFor(projectRef, Read).apply {
                    emit(graphAnalytics.relationships(projectRef))
                  }
                }
              },
              // Fetch properties for a type
              (pathPrefix("properties") & idSegment & pathEndOrSingleSlash) { tpe =>
                operationName(s"$prefixSegment/graph-analytics/{org}/{project}/properties/{type}") {
                  authorizeFor(projectRef, Read).apply {
                    emit(graphAnalytics.properties(projectRef, tpe))
                  }
                }
              },
              // Fetch the indexing progress
              // TODO: Other endpoints have this as statistics, but in this case that word would be dup. See what to do
              (pathPrefix("progress") & get & pathEndOrSingleSlash) {
                operationName(s"$prefixSegment/graph-analytics/{org}/{project}/progress") {
                  authorizeFor(projectRef, Read).apply {
                    emit(projections.statistics(projectRef, None, GraphAnalytics.projectionId(projectRef)))
                  }
                }
              }
            )
          }
        }
      }
    }
}
