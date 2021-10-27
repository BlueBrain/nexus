package ch.epfl.bluebrain.nexus.delta.rdf.query

import cats.syntax.all._
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.ExpandedJsonLdCursor
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.decoder.JsonLdDecoder
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.decoder.JsonLdDecoderError.ParsingFailure
import ch.epfl.bluebrain.nexus.delta.rdf.query.SparqlQuery.SparqlConstructQuery
import io.circe.{Decoder, Encoder}
import org.apache.jena.query.{Query, QueryFactory}

import scala.util.Try

sealed trait SparqlQuery {

  /**
    * @return
    *   string representation of the query
    */
  def value: String

  /**
    * @return
    *   the construct query, if available
    */
  def asConstruct: Option[SparqlConstructQuery]
}

object SparqlQuery {

  /**
    * Any Sparql query
    */
  final private case class AnySparqlQuery(value: String) extends SparqlQuery {
    override def asConstruct: Option[SparqlConstructQuery] = SparqlConstructQuery(value).toOption
  }

  /**
    * Sparql construct query representation.
    *
    * @param value
    *   string representation of the query
    */
  final case class SparqlConstructQuery private (value: String) extends SparqlQuery {

    lazy val jenaQuery: Query = QueryFactory.create(value)

    override val asConstruct: Option[SparqlConstructQuery] = Some(this)
  }

  object SparqlConstructQuery {

    def unsafe(value: String): SparqlConstructQuery =
      new SparqlConstructQuery(value)

    def apply(value: String): Either[String, SparqlConstructQuery] =
      Try(QueryFactory.create(value)).toEither
        .leftMap(_ => "The provided query is not a valid SPARQL query")
        .flatMap {
          case query if query.isConstructType => Right(new SparqlConstructQuery(value))
          case _                              => Left("The provided query is a valid SPARQL query but not a CONSTRUCT query")
        }

    implicit val sparqlConstructQueryEncoder: Encoder[SparqlConstructQuery] =
      Encoder.encodeString.contramap(_.value)

    implicit val sparqlConstructQueryDecoder: Decoder[SparqlConstructQuery] =
      Decoder.decodeString.map(SparqlConstructQuery.unsafe)

    implicit val sparqlConstructQueryJsonLdDecoder: JsonLdDecoder[SparqlConstructQuery] =
      (cursor: ExpandedJsonLdCursor) => cursor.get[String].flatMap { apply(_).leftMap { e => ParsingFailure(e) } }

  }

  def apply(v: String): SparqlQuery =
    SparqlConstructQuery(v).getOrElse(AnySparqlQuery(v))
}
