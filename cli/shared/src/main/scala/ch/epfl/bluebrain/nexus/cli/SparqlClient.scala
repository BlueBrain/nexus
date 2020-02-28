package ch.epfl.bluebrain.nexus.cli

import ch.epfl.bluebrain.nexus.cli.SparqlClient.SparqlError
import ch.epfl.bluebrain.nexus.cli.types.{Label, SparqlResults}
import org.http4s.Uri

trait SparqlClient[F[_]] {

  private val defaultSparqlView =
    Uri.unsafeFromString("https://bluebrain.github.io/nexus/vocabulary/defaultSparqlIndex")

  /**
    * Performs a SPARQL query on the default view of the passed organization and project.
    *
    * @param organization the organization label
    * @param project      the project label
    * @param value        the SPARQL query
    */
  def query(organization: Label, project: Label, value: String): F[Either[SparqlError, SparqlResults]] =
    query(organization, project, defaultSparqlView, value)

  /**
    * Performs a SPARQL query on the passed view, organization and project.
    *
    * @param organization the organization label
    * @param project      the project label
    * @param viewId       the view @id value
    * @param value        the SPARQL query
    */
  def query(organization: Label, project: Label, viewId: Uri, value: String): F[Either[SparqlError, SparqlResults]]
}

object SparqlClient {
  sealed trait SparqlError extends Product with Serializable

  object SparqlError {
    // TODO
  }
}
