package ch.epfl.bluebrain.nexus.rdf.jsonld.parser

import ContextParser.allowedDirection
import ch.epfl.bluebrain.nexus.rdf.jsonld.keyword._

sealed trait ParsingStatus extends Product with Serializable {
  def message: String
}

object ParsingStatus {
  final case object NotMatchObject                      extends ParsingStatus { val message: String = "The Json object does not match" }
  final case object NullObject                          extends ParsingStatus { val message: String = "Null Json Object" }
  final case class InvalidObjectFormat(message: String) extends ParsingStatus

  private[jsonld] def collidingKey(keyword: String) =
    InvalidObjectFormat(s"Colliding keywords detected for $keyword")
  private[jsonld] def invalidKey(keyword: String, block: String) =
    InvalidObjectFormat(s"$keyword should not be present inside $block")
  private[jsonld] def invalidValueObject(term: String) =
    InvalidObjectFormat(
      s"Invalid value object format: term '$term' should not be present inside a value object block. Only allowed keywords are $value, $tpe, $index, $direction and $language"
    )
  private[jsonld] def invalidValue =
    InvalidObjectFormat(s"Invalid value object $value format: array or object not allowed")
  private[jsonld] def invalidJsonValue(keyword: String) =
    InvalidObjectFormat(s"$keyword invalid format: it must be a string")
  private[jsonld] def invalidValueObjectExclusion =
    InvalidObjectFormat(
      s"Invalid value object format: both $tpe and either $direction or $language cannot be simultaneously present"
    )
  private[jsonld] val invalidDirection =
    InvalidObjectFormat(s"$direction keyword invalid format. Allowed values: '${allowedDirection.mkString(",")}'")
  private[jsonld] def invalidDirection(v: String) =
    InvalidObjectFormat(
      s"$direction keyword invalid format. Allowed values: '${allowedDirection.mkString(",")}'. Passed value '$v'"
    )
  private[jsonld] val invalidListObject =
    InvalidObjectFormat(
      s"Invalid list object format: the only allowed keywords in this Json Object are $list and $index"
    )
  private[jsonld] val invalidSetObject =
    InvalidObjectFormat(s"Invalid set object format: the only allowed keywords in this Json Object are $set and $index")
  private[jsonld] val invalidLanguageMapValue =
    InvalidObjectFormat("Language map values must be a string")
  private[jsonld] def invalidIdTerm(term: String) =
    InvalidObjectFormat(s"$id term '$term' couldn't be expanded to a Uri")
  private[jsonld] def invalidTerm(term: String) =
    InvalidObjectFormat(s"term '$term' couldn't be expanded to a Uri")
  private[jsonld] def invalidReverseValue =
    InvalidObjectFormat(s"Invalid $reverse value: it must be a Node Object")
  private[jsonld] def invalidReverseInnerValue =
    InvalidObjectFormat(s"Invalid $reverse value: it must be a Node Object or an array of Node Objects")
  private[jsonld] val invalidIdValue =
    InvalidObjectFormat(s"Invalid $id container value: it must be a Node Object or an array of Node Objects")
  private[jsonld] def invalidNodeObject(term: String) =
    InvalidObjectFormat(s"Invalid node object format: keyword '$term' not allowed")
  private[jsonld] val invalidReverseTerm =
    InvalidObjectFormat(s"Invalid $reverse object: keywords are not allowed")
  private[jsonld] val invalidGraphObject =
    InvalidObjectFormat(s"Invalid $graph object: it must be a object or an array")
  private[jsonld] val invalidGraphArrayObject =
    InvalidObjectFormat(s"Invalid $graph array element: it must be a object")

}
