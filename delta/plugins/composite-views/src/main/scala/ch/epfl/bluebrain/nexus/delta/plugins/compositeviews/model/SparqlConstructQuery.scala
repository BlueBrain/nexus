package ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.model

import cats.syntax.either._
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.client.SparqlQuery
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.model.CompositeViewProjection.idTemplating
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.decoder.JsonLdDecoder
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.decoder.JsonLdDecoderError.ParsingFailure
import io.circe.{Decoder, Encoder}
import org.apache.jena.query.QueryFactory

import java.util.regex.Pattern.quote
import scala.util.Try

/**
  * Sparql construct query representation.
  *
  * @param value  string representation of the query
  */
final case class SparqlConstructQuery private (value: String) extends SparqlQuery {

  /**
    * Replace the templating {resource_id} for the passed ''iri value''
    */
  def replaceId(iri: Iri): SparqlConstructQuery =
    new SparqlConstructQuery(value.replaceAll(quote(idTemplating), s"<$iri>"))
}

object SparqlConstructQuery {
  private val fakeIri = "http://localhost/id"

  /**
    * Constructs a [[SparqlConstructQuery]] verifying that the passed ''value'' contains the id templating and
    * that it is a valid CONSTRUCT query
    */
  final def apply(value: String): Either[String, SparqlConstructQuery] =
    if (!value.contains(idTemplating))
      Left(s"Required templating '$idTemplating' in the provided SPARQL query is not found")
    else {
      Try(QueryFactory.create(value.replaceAll(quote(idTemplating), s"<${fakeIri}>"))).toEither
        .leftMap(_ => "The provided query is not a valid SPARQL query")
        .flatMap {
          case query if query.isConstructType => Right(new SparqlConstructQuery(value))
          case _                              => Left("The provided query is a valid SPARQL query but not a CONSTRUCT query")
        }
    }

  implicit val sparqlConstructQueryEncoder: Encoder[SparqlConstructQuery] =
    Encoder.encodeString.contramap(_.value)
  implicit val sparqlConstructQueryDecoder: Decoder[SparqlConstructQuery] =
    Decoder.decodeString.map(new SparqlConstructQuery(_))

  implicit val sparqlConstructQueryJsonLdDecoder: JsonLdDecoder[SparqlConstructQuery] =
    JsonLdDecoder.stringJsonLdDecoder.andThen { case (cursor, str) =>
      SparqlConstructQuery(str).leftMap(reason => ParsingFailure("SparqlConstructQuery", str, cursor.history, reason))
    }
}
