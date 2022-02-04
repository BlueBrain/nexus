package ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.client

import cats.syntax.all._
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.ExpandedJsonLdCursor
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.decoder.JsonLdDecoder
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.decoder.JsonLdDecoderError.ParsingFailure
import ch.epfl.bluebrain.nexus.delta.sdk.error.FormatError
import io.circe.{Decoder, Encoder}

import java.util.UUID

final case class IndexLabel(value: String) {
  override def toString: String = value
}

object IndexLabel {

  /**
    * Regex created from the ElasticSearch restrictions:
    * https://www.elastic.co/guide/en/elasticsearch/reference/current/indices-create-index.html#indices-create-api-path-params
    */
  private def elasticRegex(maxLength: Int) = s"""^([^-_+\\.])([^<> :\\*\\/"?|#`\\\\,]{0,$maxLength}+)?$$""".r

  private val indexLabelRegex = elasticRegex(200)

  /**
    * Allows to build [[IndexLabel]] prefixes and allow to group more easily Elasticsearch indices with wildcards
    * @param value
    */
  final case class IndexGroup(value: String) {
    override def toString: String = value
  }

  object IndexGroup {

    private val indexGroupRegex = elasticRegex(10)

    final def unsafe(string: String): IndexGroup = new IndexGroup(string.toLowerCase)

    final def apply(string: String): Either[IllegalIndexLabel, IndexGroup] =
      Option
        .when(indexGroupRegex.unapplySeq(string).isDefined)(new IndexGroup(string.toLowerCase))
        .toRight(IllegalIndexLabel(string))

    implicit final val indexGroupEncoder: Encoder[IndexGroup] =
      Encoder.encodeString.contramap(_.value)

    implicit final val indexGroupDecoder: Decoder[IndexGroup] =
      Decoder.decodeString.emap(str => IndexGroup(str).leftMap(_.getMessage))

    implicit val indexGroupJsonLdDecoder: JsonLdDecoder[IndexGroup] =
      (cursor: ExpandedJsonLdCursor) =>
        cursor.get[String].flatMap { IndexGroup(_).leftMap { e => ParsingFailure(e.getMessage) } }
  }

  /**
    * Label formatting error, returned in cases where an IndexLabel could not be constructed from a String.
    */
  final case class IllegalIndexLabel(value: String)
      extends FormatError(s"'$value' did not match the regex '$indexLabelRegex'.", None)

  final def unsafe(string: String): IndexLabel = new IndexLabel(string.toLowerCase)

  /**
    * Constructs an [[IndexLabel]] safely.
    */
  final def apply(string: String): Either[IllegalIndexLabel, IndexLabel] =
    Option
      .when(indexLabelRegex.unapplySeq(string).isDefined)(new IndexLabel(string.toLowerCase))
      .toRight(IllegalIndexLabel(string))

  /**
    * Constructs an [[IndexLabel]] safely from view parameters
    *
    * @param prefix
    *   the index prefix retrieved from configuration
    * @param uuid
    *   the view unique identifier
    * @param rev
    *   the view revision
    */
  final def fromView(prefix: String, uuid: UUID, rev: Long): IndexLabel = new IndexLabel(s"${prefix}_${uuid}_$rev")

}
