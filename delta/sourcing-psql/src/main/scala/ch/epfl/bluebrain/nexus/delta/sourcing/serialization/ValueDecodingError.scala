package ch.epfl.bluebrain.nexus.delta.sourcing.serialization

import ch.epfl.bluebrain.nexus.delta.sourcing.model.EntityType
import io.circe.{CursorOp, DecodingFailure}

sealed abstract class ValueDecodingError(val reason: String, details: Option[String] = None)
    extends Exception
    with Product
    with Serializable {
  override def fillInStackTrace(): ValueDecodingError = this
  override def getMessage: String                     = details.fold(reason)(d => s"$reason\nDetails: $d")
}

object ValueDecodingError {

  final case class UnexpectedEntityType(expected: EntityType, provided: EntityType)
      extends ValueDecodingError(
        s"Incorrect entity type '$provided' provided, expected '$expected'."
      )

  final case class UnknownEntityType(expected: EntityType)
      extends ValueDecodingError(
        s"Missing entity type: '$expected'."
      )

  final case class ParsingError(message: String) extends ValueDecodingError(message)

  object ParsingError {
    private def toString(path: List[CursorOp]): Option[String] = {
      val string = path.reverse.mkString(",")
      Option.when(string.trim.nonEmpty)(string)
    }

    def apply(entityType: EntityType, failure: DecodingFailure): ParsingError =
      toString(failure.history) match {
        case Some(pathStr) =>
          ParsingError(
            s"Could not decode payload of type '$entityType' from path '$pathStr' with message ${failure.message}"
          )
        case None          =>
          ParsingError(s"Could not decode payload of type '$entityType' with message ${failure.message}")
      }
  }

}
