package ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.routes

import akka.http.scaladsl.server.Route

object BlazegraphViewsDirectives {

  /**
    * Completes the current Route with the provided conversion to Json
    */
  def emitSparqlResults(response: ResponseToSparqlJson): Route =
    response()
}
