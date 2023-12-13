package ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.client

import ch.epfl.bluebrain.nexus.delta.sdk.http.HttpClientError

/**
  * Error that can occur when using an [[SparqlClient]]
  */
sealed abstract class SparqlClientError(val reason: String, val details: Option[String])
    extends Exception
    with Product
    with Serializable {
  override def fillInStackTrace(): SparqlClientError = this

  override def getMessage: String = toString()

  override def toString(): String =
    s"An error occurred because '$reason'" ++ details.map(d => s"\ndetails '$d'").getOrElse("")

}

object SparqlClientError {

  /**
    * Error on the underlying [[HttpClient]]
    */
  final case class WrappedHttpClientError(http: HttpClientError) extends SparqlClientError(http.reason, http.details) {
    override def getMessage: String = http.getMessage

    def getOriginal: HttpClientError = http
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
