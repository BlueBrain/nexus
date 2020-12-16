package ch.epfl.bluebrain.nexus.delta.rdf

import akka.http.scaladsl.model.HttpCharsets._
import akka.http.scaladsl.model.MediaType

/**
  * Collection of media types specific to RDF.
  */
// $COVERAGE-OFF$
object RdfMediaTypes {
  final val `text/turtle`: MediaType.WithOpenCharset =
    MediaType.applicationWithOpenCharset("turtle", "ttl")

  final val `application/rdf+xml`: MediaType.WithOpenCharset =
    MediaType.applicationWithOpenCharset("rdf+xml", "rdf")

  final val `application/n-triples`: MediaType.WithFixedCharset =
    MediaType.applicationWithFixedCharset("n-triples", `UTF-8`, "nt")

  final val `application/ld+json`: MediaType.WithFixedCharset =
    MediaType.applicationWithFixedCharset("ld+json", `UTF-8`, "jsonld")

  final val `application/sparql-results+json`: MediaType.WithFixedCharset =
    MediaType.applicationWithFixedCharset("sparql-results+json", `UTF-8`, "json")

  final val `application/sparql-query`: MediaType.WithFixedCharset =
    MediaType.applicationWithFixedCharset("sparql-query", `UTF-8`)

  final val `text/vnd.graphviz`: MediaType.WithFixedCharset =
    MediaType.textWithFixedCharset("vnd.graphviz", `UTF-8`, "dot")
}
// $COVERAGE-ON$
