package ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.client

import cats.effect.IO
import org.http4s.{EntityDecoder, Response, Status}

/**
  * Error that can occur when using an [[SparqlClient]]
  */
sealed abstract class SparqlClientError(val reason: String, val details: Option[String])
    extends Exception
    with Product
    with Serializable {
  override def fillInStackTrace(): SparqlClientError = this

  override def getMessage: String = toString

  override def toString: String =
    s"An error occurred because '$reason'" ++ details.map(d => s"\ndetails '$d'").getOrElse("")

}

object SparqlClientError {

  final case class SparqlActionError(status: Status, action: String)
      extends SparqlClientError(
        s"The sparql $action failed with status $status",
        None
      )

  final case class SparqlQueryError(status: Status, body: String)
      extends SparqlClientError(
        s"The sparql endpoint responded with a status: $status",
        Some(body)
      )

  object SparqlQueryError {
    def apply(response: Response[IO]): IO[SparqlQueryError] =
      EntityDecoder.decodeText(response).map { body =>
        SparqlQueryError(response.status, body)
      }
  }

  final case class SparqlWriteError(status: Status, body: String)
      extends SparqlClientError(
        s"The sparql endpoint responded with a status: $status",
        Some(body)
      )

  object SparqlWriteError {
    def apply(response: Response[IO]): IO[SparqlQueryError] =
      EntityDecoder.decodeText(response).map { body =>
        SparqlQueryError(response.status, body)
      }
  }

  /**
    * Error when trying to perform a count on an index
    */
  final case class InvalidCountRequest(index: String, queryString: String)
      extends SparqlClientError(
        s"Attempting to count the triples the index '$index' with a wrong query '$queryString'",
        None
      )

  /**
    * Error when trying to perform an update and the query passed is wrong.
    */
  final case class InvalidUpdateRequest(index: String, queryString: String, override val details: Option[String])
      extends SparqlClientError(
        s"Attempting to update the index '$index' with a wrong query '$queryString'",
        details
      )
}
