package ch.epfl.bluebrain.nexus.commons.sparql.client

import akka.http.scaladsl.model.StatusCode
import akka.http.scaladsl.model.StatusCodes.{ClientError, ServerError}

sealed abstract class SparqlFailure(val message: String) extends Exception(message) {

  /**
    * the HTTP response payload
    */
  def body: String
}

// $COVERAGE-OFF$

object SparqlFailure {

  abstract class SparqlServerOrUnexpectedFailure(message: String) extends SparqlFailure(message)

  /**
    * Generates a SPARQL server failure from the HTTP response status ''code''.
    *
    * @param code the HTTP response status ''code''
    * @param body the HTTP response payload
    */
  def fromStatusCode(code: StatusCode, body: String): SparqlFailure =
    code match {
      case _: ServerError => SparqlServerError(code, body)
      case _: ClientError => SparqlClientError(code, body)
      case _              => SparqlUnexpectedError(code, body)
    }

  /**
    * An unexpected server failure when attempting to communicate with a sparql endpoint.
    *
    * @param status the status returned by the sparql endpoint
    * @param body   the response body returned by the sparql endpoint
    */
  final case class SparqlServerError(status: StatusCode, body: String)
      extends SparqlServerOrUnexpectedFailure(s"Server error with status code '$status' and body '$body'")

  /**
    * A client failure when attempting to communicate with a sparql endpoint.
    *
    * @param status the status returned by the sparql endpoint
    * @param body   the response body returned by the sparql endpoint
    */
  final case class SparqlClientError(status: StatusCode, body: String)
      extends SparqlFailure(s"Client error with status code '$status' and body '$body'")

  /**
    * An unexpected failure when attempting to communicate with a sparql endpoint.
    *
    * @param status the status returned by the sparql endpoint
    * @param body   the response body returned by the sparql endpoint
    */
  final case class SparqlUnexpectedError(status: StatusCode, body: String)
      extends SparqlServerOrUnexpectedFailure(s"Unexpected status code '$status' and body '$body'")

}
// $COVERAGE-ON$
