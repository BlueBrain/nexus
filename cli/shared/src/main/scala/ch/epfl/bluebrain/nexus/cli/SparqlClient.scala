package ch.epfl.bluebrain.nexus.cli

import ch.epfl.bluebrain.nexus.cli.SparqlClient.SparqlError
import ch.epfl.bluebrain.nexus.cli.types.SparqlResults

trait SparqlClient[F[_]] {
  /**
   * Performs a SPARQL query
   * @param value the SPARQL query
   */
  def query(value: String): F[Either[SparqlError, SparqlResults]]
}

object SparqlClient {
  sealed trait SparqlError extends Product with Serializable

  object SparqlError {
    // TODO
  }
}
