package ch.epfl.bluebrain.nexus.delta.plugins.search

import akka.http.scaladsl.server.Directives.{as, concat, entity, get, pathEndOrSingleSlash, pathPrefix, post}
import akka.http.scaladsl.server.Route
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.routes.ElasticSearchViewsDirectives.extractQueryParams
import ch.epfl.bluebrain.nexus.delta.plugins.search.model.SearchConfig
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.RemoteContextResolution
import ch.epfl.bluebrain.nexus.delta.rdf.utils.JsonKeyOrdering
import ch.epfl.bluebrain.nexus.delta.sdk.acls.AclCheck
import ch.epfl.bluebrain.nexus.delta.sdk.circe.CirceUnmarshalling
import ch.epfl.bluebrain.nexus.delta.sdk.directives.AuthDirectives
import ch.epfl.bluebrain.nexus.delta.sdk.directives.DeltaDirectives.{baseUriPrefix, emit}
import ch.epfl.bluebrain.nexus.delta.sdk.identities.Identities
import ch.epfl.bluebrain.nexus.delta.sdk.marshalling.RdfMarshalling
import ch.epfl.bluebrain.nexus.delta.sdk.model.BaseUri
import io.circe.JsonObject
import io.circe.syntax.EncoderOps
import kamon.instrumentation.akka.http.TracingDirectives.operationName
import monix.bio.UIO
import monix.execution.Scheduler

class SearchRoutes(
    identities: Identities,
    aclCheck: AclCheck,
    search: Search,
    config: SearchConfig
)(implicit baseUri: BaseUri, s: Scheduler, cr: RemoteContextResolution, ordering: JsonKeyOrdering)
    extends AuthDirectives(identities, aclCheck)
    with CirceUnmarshalling
    with RdfMarshalling {

  import baseUri.prefixSegment

  def routes: Route =
    baseUriPrefix(baseUri.prefix) {
      pathPrefix("search") {
        extractCaller { implicit caller =>
          concat(
            // Query the underlying aggregate elasticsearch view for global search
            (pathPrefix("query") & post & pathEndOrSingleSlash) {
              operationName(s"$prefixSegment/search/query") {
                (extractQueryParams & entity(as[JsonObject])) { (qp, payload) =>
                  emit(search.query(payload, qp))
                }
              }
            },
            // Get fields config
            (pathPrefix("config") & get & pathEndOrSingleSlash) {
              operationName(s"$prefixSegment/search/config") {
                emit(UIO.pure(config.fields.asJson))
              }
            }
          )

        }
      }
    }
}
