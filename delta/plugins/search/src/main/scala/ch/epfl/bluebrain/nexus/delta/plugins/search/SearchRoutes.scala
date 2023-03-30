package ch.epfl.bluebrain.nexus.delta.plugins.search

import akka.http.scaladsl.server.Directives.{as, concat, entity, get, pathEndOrSingleSlash, pathPrefix, post}
import akka.http.scaladsl.server.Route
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.routes.ElasticSearchViewsDirectives.extractQueryParams
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.RemoteContextResolution
import ch.epfl.bluebrain.nexus.delta.rdf.utils.JsonKeyOrdering
import ch.epfl.bluebrain.nexus.delta.sdk.acls.AclCheck
import ch.epfl.bluebrain.nexus.delta.sdk.circe.CirceUnmarshalling
import ch.epfl.bluebrain.nexus.delta.sdk.directives.{AuthDirectives, DeltaDirectives}
import ch.epfl.bluebrain.nexus.delta.sdk.identities.Identities
import ch.epfl.bluebrain.nexus.delta.sdk.marshalling.RdfMarshalling
import ch.epfl.bluebrain.nexus.delta.sdk.model.BaseUri
import io.circe.{Json, JsonObject}
import kamon.instrumentation.akka.http.TracingDirectives.operationName
import monix.bio.UIO
import monix.execution.Scheduler

class SearchRoutes(
    identities: Identities,
    aclCheck: AclCheck,
    search: Search,
    configFields: Json
)(implicit baseUri: BaseUri, s: Scheduler, cr: RemoteContextResolution, ordering: JsonKeyOrdering)
    extends AuthDirectives(identities, aclCheck)
    with CirceUnmarshalling
    with RdfMarshalling
    with DeltaDirectives {

  import baseUri.prefixSegment

  def routes: Route =
    baseUriPrefix(baseUri.prefix) {
      pathPrefix("search") {
        extractCaller { implicit caller =>
          concat(
            // Query the underlying aggregate elasticsearch view for global search
            (pathPrefix("query") & post) {
              (extractQueryParams & entity(as[JsonObject])) { (qp, payload) =>
                concat(
                  pathEndOrSingleSlash {
                    emit(search.query(payload, qp))
                  },
                  (pathPrefix("suite") & label & pathEndOrSingleSlash) { suite =>
                    emit(search.query(suite, payload, qp))
                  }
                )
              }
            },
            // Get fields config
            (pathPrefix("config") & get & pathEndOrSingleSlash) {
              operationName(s"$prefixSegment/search/config") {
                emit(UIO.pure(configFields: Json))
              }
            }
          )

        }
      }
    }
}
