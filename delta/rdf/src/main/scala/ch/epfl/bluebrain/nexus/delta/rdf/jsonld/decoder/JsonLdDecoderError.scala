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

  /**
    * A failure decoding a field
    *
    * @param message the error message
    */
  final case class DecodingFailure(message: String) extends JsonLdDecoderError(message)

  object DecodingFailure {

    /**
      * Construct a [[DecodingFailure]] when the passed ''tpe'' on the passed ''path'' could not be extracted
      */
    final def apply(tpe: String, path: List[CursorOp]): DecodingFailure =
      toString(path) match {
        case Some(pathStr) => DecodingFailure(s"Could not extract a '$tpe' from the path '$pathStr'")
        case None          => DecodingFailure(s"Could not extract a '$tpe'")
      }

    /**
      * Construct a [[DecodingFailure]] when the passed ''value'' could not be converted to ''tpe'' on the ''path''
      */
    final def apply(tpe: String, value: String, path: List[CursorOp]): DecodingFailure =
      toString(path) match {
        case Some(pathStr) => DecodingFailure(s"Could not convert '$value' to '$tpe' from the path '$pathStr'")
        case None          => DecodingFailure(s"Could not convert '$value' to '$tpe'")
      }

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
