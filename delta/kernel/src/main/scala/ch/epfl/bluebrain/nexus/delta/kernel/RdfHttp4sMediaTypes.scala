package ch.epfl.bluebrain.nexus.delta.kernel

import org.http4s.MediaType

/**
  * Collection of media types specific to RDF.
  */
// $COVERAGE-OFF$
object RdfHttp4sMediaTypes {

  private def text(subType: String, fileExtensions: String*) =
    new MediaType("text", subType, compressible = true, fileExtensions = fileExtensions.toList)

  private def application(subType: String, fileExtensions: String*) =
    new MediaType("application", subType, compressible = true, fileExtensions = fileExtensions.toList)

  final val `text/turtle`: MediaType                     = text("turtle", "ttl")
  final val `application/rdf+xml`: MediaType             = application("rdf+xml", "xml")
  final val `application/n-triples`: MediaType           = application("n-triples", "nt")
  final val `application/n-quads`: MediaType             = application("n-quads", "nq")
  final val `application/ld+json`: MediaType             = application("ld+json", "jsonld")
  final val `application/sparql-results+json`: MediaType = application("sparql-results+json", "json")
  final val `application/sparql-results+xml`: MediaType  = application("sparql-results+xml", "xml")
  final val `application/sparql-query`: MediaType        = application("sparql-query")
  final val `application/sparql-update`: MediaType       = application("sparql-update")
  final val `text/vnd.graphviz`: MediaType               = text("vnd.graphviz", "dot")
}
// $COVERAGE-ON$
