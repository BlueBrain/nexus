package ch.epfl.bluebrain.nexus.delta.plugins.search

import akka.http.scaladsl.server.Route
import cats.effect.IO
import cats.implicits.catsSyntaxApplicativeError
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.routes.ElasticSearchViewsDirectives.extractQueryParams
import ch.epfl.bluebrain.nexus.delta.plugins.search.model.{SearchConfig, SearchRejection}
import ch.epfl.bluebrain.nexus.delta.plugins.search.model.SearchConfig._
import ch.epfl.bluebrain.nexus.delta.plugins.search.model.SearchRejection.UnknownSuite
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

class SearchRoutes(
    identities: Identities,
    aclCheck: AclCheck,
    search: Search,
    configFields: Json,
    suites: SearchConfig.Suites
)(implicit baseUri: BaseUri, cr: RemoteContextResolution, ordering: JsonKeyOrdering)
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
                emit(IO.pure(configFields))
              }
            },
            // Fetch suite
            (pathPrefix("suites") & get & label & pathEndOrSingleSlash) { suiteName =>
              emit(
                IO.fromOption(suites.get(suiteName))(UnknownSuite(suiteName))
                  .map(s => NamedSuite(suiteName, s))
                  .attemptNarrow[SearchRejection]
              )
            }
          )

        }
      }
    }
}
