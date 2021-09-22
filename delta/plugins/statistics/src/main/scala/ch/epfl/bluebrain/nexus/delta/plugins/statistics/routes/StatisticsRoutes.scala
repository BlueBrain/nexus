package ch.epfl.bluebrain.nexus.delta.plugins.statistics.routes

import akka.http.scaladsl.server.Directives.{concat, get, pathEndOrSingleSlash, pathPrefix}
import akka.http.scaladsl.server.Route
import ch.epfl.bluebrain.nexus.delta.plugins.statistics.Statistics
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.RemoteContextResolution
import ch.epfl.bluebrain.nexus.delta.rdf.utils.JsonKeyOrdering
import ch.epfl.bluebrain.nexus.delta.sdk.Permissions.resources.{read => Read}
import ch.epfl.bluebrain.nexus.delta.sdk.circe.CirceUnmarshalling
import ch.epfl.bluebrain.nexus.delta.sdk.directives.AuthDirectives
import ch.epfl.bluebrain.nexus.delta.sdk.directives.DeltaDirectives.{baseUriPrefix, emit, idSegment, projectRef}
import ch.epfl.bluebrain.nexus.delta.sdk.marshalling.RdfMarshalling
import ch.epfl.bluebrain.nexus.delta.sdk.model.BaseUri
import ch.epfl.bluebrain.nexus.delta.sdk.{Acls, Identities, ProgressesStatistics, Projects}
import kamon.instrumentation.akka.http.TracingDirectives.operationName
import monix.execution.Scheduler

/**
  * The statistics routes.
  *
  * @param identities
  *   the identity module
  * @param acls
  *   the acls module
  * @param projects
  *   the projects module
  * @param statistics
  *   the statistics module
  * @param progresses
  *   the progresses for statistics
  */
class StatisticsRoutes(
    identities: Identities,
    acls: Acls,
    projects: Projects,
    statistics: Statistics,
    progresses: ProgressesStatistics
)(implicit baseUri: BaseUri, s: Scheduler, cr: RemoteContextResolution, ordering: JsonKeyOrdering)
    extends AuthDirectives(identities, acls)
    with CirceUnmarshalling
    with RdfMarshalling {
  import baseUri.prefixSegment

  def routes: Route =
    baseUriPrefix(baseUri.prefix) {
      pathPrefix("statistics") {
        extractCaller { implicit caller =>
          (get & projectRef(projects)) { projectRef =>
            concat(
              // Fetch relationships
              (pathPrefix("relationships") & pathEndOrSingleSlash) {
                operationName(s"$prefixSegment/statistics/{org}/{project}/relationships") {
                  authorizeFor(projectRef, Read).apply {
                    emit(statistics.relationships(projectRef))
                  }
                }
              },
              // Fetch properties for a type
              (pathPrefix("properties") & idSegment & pathEndOrSingleSlash) { tpe =>
                operationName(s"$prefixSegment/statistics/{org}/{project}/properties/{type}") {
                  authorizeFor(projectRef, Read).apply {
                    emit(statistics.properties(projectRef, tpe))
                  }
                }
              },
              // Fetch the indexing progress
              // TODO: Other endpoints have this as statistics, but in this case that word would be dup. See what to do
              (pathPrefix("progress") & get & pathEndOrSingleSlash) {
                operationName(s"$prefixSegment/statistics/{org}/{project}/progress") {
                  authorizeFor(projectRef, Read).apply {
                    emit(progresses.statistics(projectRef, Statistics.projectionId(projectRef)))
                  }
                }
              }
            )
          }
        }
      }
    }
}
