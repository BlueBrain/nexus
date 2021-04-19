package ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.client

import akka.http.scaladsl.model.MediaTypes
import akka.http.scaladsl.unmarshalling.{FromEntityUnmarshaller, FromStringUnmarshaller, PredefinedFromEntityUnmarshallers, Unmarshaller}
import ch.epfl.bluebrain.nexus.delta.rdf.RdfMediaTypes

trait SparqlQuery {

  /**
    * @return string representation of the query
    */
  def value: String
}

object SparqlQuery {

  final private case class AnySparqlQuery(value: String) extends SparqlQuery

  def apply(v: String): SparqlQuery = AnySparqlQuery(v)

  implicit val fromEntitySparqlQueryUnmarshaller: FromEntityUnmarshaller[SparqlQuery] =
    PredefinedFromEntityUnmarshallers.stringUnmarshaller
      .forContentTypes(RdfMediaTypes.`application/sparql-query`, MediaTypes.`text/plain`)
      .map(SparqlQuery(_))

  implicit val fromStringSparqlQueryUnmarshaller: FromStringUnmarshaller[SparqlQuery] =
    Unmarshaller.strict(SparqlQuery(_))

}
