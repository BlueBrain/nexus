package ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.routes

import akka.http.scaladsl.server.Route
import cats.syntax.all._
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.metrics.FetchHistory
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.query.ElasticSearchQueryError
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.RemoteContextResolution
import ch.epfl.bluebrain.nexus.delta.rdf.utils.JsonKeyOrdering
import ch.epfl.bluebrain.nexus.delta.sdk.acls.AclCheck
import ch.epfl.bluebrain.nexus.delta.sdk.directives.AuthDirectives
import ch.epfl.bluebrain.nexus.delta.sdk.directives.DeltaDirectives.{emit, projectRef}
import ch.epfl.bluebrain.nexus.delta.sdk.directives.UriDirectives.iriSegment
import ch.epfl.bluebrain.nexus.delta.sdk.identities.Identities
import ch.epfl.bluebrain.nexus.delta.sdk.marshalling.RdfMarshalling
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.SearchResults
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.SearchResults.searchResultsEncoder
import ch.epfl.bluebrain.nexus.delta.sdk.permissions.Permissions.resources.{read => Read}
import io.circe.syntax.EncoderOps
import io.circe.{Encoder, JsonObject}

/**
  * Routes allowing to get the history of events for resources
  */
class ElasticSearchHistoryRoutes(identities: Identities, aclCheck: AclCheck, fetchHistory: FetchHistory)(implicit
    cr: RemoteContextResolution,
    ordering: JsonKeyOrdering
) extends AuthDirectives(identities, aclCheck)
    with RdfMarshalling {
  implicit private val searchEncoder: Encoder.AsObject[SearchResults[JsonObject]] = searchResultsEncoder(_ => None)

  def routes: Route =
    pathPrefix("history") {
      pathPrefix("resources") {
        extractCaller { implicit caller =>
          projectRef.apply { project =>
            authorizeFor(project, Read).apply {
              (get & iriSegment & pathEndOrSingleSlash) { id =>
                emit(fetchHistory.history(project, id).map(_.asJson).attemptNarrow[ElasticSearchQueryError])
              }
            }
          }
        }
      }
    }
}
