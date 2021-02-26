package ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.routes

import akka.http.scaladsl.server.Route

object BlazegraphViewsDirectives extends BlazegraphViewsDirectives

trait BlazegraphViewsDirectives {

  /**
    * Completes the current Route with the provided conversion to Json
    */
  def emit(response: ResponseToSparqlJson): Route =
    response()
}
