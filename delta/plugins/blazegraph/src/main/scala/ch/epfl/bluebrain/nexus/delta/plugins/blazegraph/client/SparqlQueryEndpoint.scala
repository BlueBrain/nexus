package ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.client

import akka.http.scaladsl.model.Uri
import ch.epfl.bluebrain.nexus.delta.sdk.syntax._

trait SparqlQueryEndpoint {

  /**
    * Generates an endpoint where to execute a Sparql query
    *
    * @param index the namespace of the Sparql query
    */
  def apply(index: String): Uri
}

object SparqlQueryEndpoint {
  def blazegraph(base: Uri): SparqlQueryEndpoint = base / "namespace" / _ / "sparql"
}
