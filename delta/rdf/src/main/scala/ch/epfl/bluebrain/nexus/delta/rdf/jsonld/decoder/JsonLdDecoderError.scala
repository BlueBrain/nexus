package ch.epfl.bluebrain.nexus.delta.rdf.jsonld.decoder

import io.circe.CursorOp

/**
  * Enumeration of all possible failures while decoding
  */
sealed abstract class JsonLdDecoderError(val reason: String, details: Option[String] = None) extends Exception {
  override def fillInStackTrace(): JsonLdDecoderError = this
  override def getMessage: String                     = details.fold(reason)(d => s"$reason\nDetails: $d")
}

object JsonLdDecoderError {

  sealed abstract class DecodingFailure(message: String) extends JsonLdDecoderError(message)

  /**
    * A failure decoding a field
    *
    * @param message the error message
    */
  final case class ParsingFailure(message: String) extends DecodingFailure(message)

  object ParsingFailure {

    /**
      * Construct a [[DecodingFailure]] when the passed ''tpe'' on the passed ''path'' could not be extracted
      */
    final def apply(tpe: String, path: List[CursorOp]): DecodingFailure =
      toString(path) match {
        case Some(pathStr) => ParsingFailure(s"Could not extract a '$tpe' from the path '$pathStr'")
        case None          => ParsingFailure(s"Could not extract a '$tpe'")
      }

    /**
      * Construct a [[DecodingFailure]] when the passed ''value'' could not be converted to ''tpe'' on the ''path''
      */
    final def apply(tpe: String, value: String, path: List[CursorOp]): DecodingFailure =
      toString(path) match {
        case Some(pathStr) => ParsingFailure(s"Could not convert '$value' to '$tpe' from the path '$pathStr'")
        case None          => ParsingFailure(s"Could not convert '$value' to '$tpe'")
      }

    final case class KeyMissingFailure(key: String, path: List[CursorOp])
        extends DecodingFailure(s"Key $key was missing under path ${toString(path).getOrElse("")}")

    private def toString(path: List[CursorOp]): Option[String] = {
      val string = path.reverse.mkString(",")
      Option.when(string.trim.nonEmpty)(string)
    }

  }

  /**
    * A failure decoding a field when using derivation
    * @param message the error message
    */
  final case class DecodingDerivationFailure(message: String) extends JsonLdDecoderError(message, None)
}
