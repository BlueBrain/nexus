package ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.model

import cats.syntax.either._
import ch.epfl.bluebrain.nexus.delta.sdk.syntax._
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.client.SparqlQuery.SparqlConstructQuery
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.model.CompositeViewProjection.idTemplating
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.decoder.JsonLdDecoder
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.decoder.JsonLdDecoderError.ParsingFailure
import io.circe.{Decoder, Encoder}

import java.util.regex.Pattern.quote

object TemplateSparqlConstructQuery {
  private val fakeIri = iri"http://localhost/id"

  /**
    * Constructs a [[SparqlConstructQuery]] verifying that the passed ''value'' contains the id templating and that it
    * is a valid CONSTRUCT query
    */
  final def apply(value: String): Either[String, SparqlConstructQuery] =
    if (!value.contains(idTemplating))
      Left(s"Required templating '$idTemplating' in the provided SPARQL query is not found")
    else
      SparqlConstructQuery(value.replaceAll(quote(idTemplating), fakeIri.rdfFormat)).map(_.copy(value = value))

  implicit val sparqlConstructQueryEncoder: Encoder[SparqlConstructQuery] =
    Encoder.encodeString.contramap(_.value)

  implicit val sparqlConstructQueryDecoder: Decoder[SparqlConstructQuery] =
    Decoder.decodeString.map(SparqlConstructQuery.unsafe(_))

  implicit val sparqlConstructQueryJsonLdDecoder: JsonLdDecoder[SparqlConstructQuery] =
    JsonLdDecoder.stringJsonLdDecoder.andThen { case (cursor, str) =>
      TemplateSparqlConstructQuery(str).leftMap(reason =>
        ParsingFailure("SparqlConstructQuery", str, cursor.history, reason)
      )
    }
}
