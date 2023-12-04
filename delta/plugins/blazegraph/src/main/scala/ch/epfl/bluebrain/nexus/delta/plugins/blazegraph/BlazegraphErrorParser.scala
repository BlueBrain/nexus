package ch.epfl.bluebrain.nexus.delta.plugins.blazegraph

import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.client.SparqlClientError
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.client.SparqlClientError.WrappedHttpClientError
import ch.epfl.bluebrain.nexus.delta.sdk.http.HttpClientError

object BlazegraphErrorParser {

  private def parseMalformedQueryException(rawError: String): Option[String] = {
    val javaExecutionExceptionMatcher = "java.util.concurrent.ExecutionException: "
    val malformedSegmentMatcher       = "java.util.concurrent.ExecutionException: org.openrdf.query.MalformedQueryException: "

    rawError.linesIterator
      .find(_.contains(malformedSegmentMatcher))
      .map(str => str.replace(javaExecutionExceptionMatcher, ""))
  }

  private def parse(rawError: String): String = {
    val errors = List(parseMalformedQueryException(rawError)).flatten
    errors match {
      case ::(head, Nil) => head
      case Nil           => rawError
    }
  }

  def details(error: SparqlClientError): String =
    error match {
      case WrappedHttpClientError(httpError) =>
        httpError match {
          case HttpClientError.HttpClientStatusError(_, _, message) => parse(message)
          case error                                                => error.reason
        }
      case sparqlClientError                 => sparqlClientError.toString()
    }

}
