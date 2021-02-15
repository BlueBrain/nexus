package ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.client

import ch.epfl.bluebrain.nexus.delta.sdk.error.FormatError

import java.util.UUID

final case class IndexLabel(value: String) {
  override def toString: String = value
}

object IndexLabel {

  /**
    * Regex created from the ElasticSearch restrictions: https://www.elastic.co/guide/en/elasticsearch/reference/current/indices-create-index.html#indices-create-api-path-params
    */
  private val regex = """^([^-_+\.])([^<> :\*\/"?|#`\\,]{0,200}+)?$""".r

  /**
    * Label formatting error, returned in cases where an IndexLabel could not be constructed from a String.
    */
  final case class IllegalIndexLabel(value: String)
      extends FormatError(s"'$value' did not match the regex '$regex'.", None)

  final def unsafe(string: String): IndexLabel = new IndexLabel(string.toLowerCase)

  /**
    * Constructs an [[IndexLabel]] safely.
    */
  final def apply(string: String): Either[IllegalIndexLabel, IndexLabel] =
    Option
      .when(regex.unapplySeq(string).isDefined)(new IndexLabel(string.toLowerCase))
      .toRight(IllegalIndexLabel(string))

  /**
    * Constructs an [[IndexLabel]] safely from view parameters
    *
    * @param prefix the index prefix retrieved from configuration
    * @param uuid   the view unique identifier
    * @param rev    the view revision
    */
  final def fromView(prefix: String, uuid: UUID, rev: Long): IndexLabel = new IndexLabel(s"${prefix}_${uuid}_$rev")

}
