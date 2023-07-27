package ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.routes

import akka.http.scaladsl.server.Directives.concat
import akka.http.scaladsl.server.Route
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.model._
import ch.epfl.bluebrain.nexus.delta.sdk.directives.DeltaSchemeDirectives
import ch.epfl.bluebrain.nexus.delta.sdk.model.BaseUri

class ElasticSearchAllRoutes(schemeDirectives: DeltaSchemeDirectives, underlying: Route*)(implicit baseUri: BaseUri)
    extends ElasticSearchViewsDirectives {

  import schemeDirectives._

  def routes: Route =
    (baseUriPrefix(baseUri.prefix) & replaceUri("views", schema.iri)) {
      concat(underlying: _*)
    }

}

object ElasticSearchAllRoutes {

  def apply(schemeDirectives: DeltaSchemeDirectives, routes: Route*)(implicit baseUri: BaseUri): Route =
    new ElasticSearchAllRoutes(schemeDirectives, routes: _*).routes
}
