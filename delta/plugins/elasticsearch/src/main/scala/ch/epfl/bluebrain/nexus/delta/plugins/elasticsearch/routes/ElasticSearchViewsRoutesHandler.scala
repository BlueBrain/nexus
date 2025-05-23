package ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.routes

import akka.http.scaladsl.server.Directives.concat
import akka.http.scaladsl.server.Route
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.model.*
import ch.epfl.bluebrain.nexus.delta.sdk.directives.DeltaSchemeDirectives
import ch.epfl.bluebrain.nexus.delta.sdk.directives.UriDirectives.baseUriPrefix
import ch.epfl.bluebrain.nexus.delta.sdk.model.BaseUri

/**
  * Transforms the incoming request to consume the baseUri prefix and rewrite the generic resource endpoint
  */
object ElasticSearchViewsRoutesHandler extends {

  def apply(schemeDirectives: DeltaSchemeDirectives, routes: Route*)(implicit baseUri: BaseUri): Route =
    (baseUriPrefix(baseUri.prefix) & schemeDirectives.replaceUri("views", schema.iri)) {
      concat(routes*)
    }
}
