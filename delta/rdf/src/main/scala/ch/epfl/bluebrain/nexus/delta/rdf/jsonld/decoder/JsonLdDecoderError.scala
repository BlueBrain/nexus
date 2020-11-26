package ch.epfl.bluebrain.nexus.delta.rdf.jsonld.decoder

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
    final def apply(tpe: String, path: String): DecodingFailure =
      if (path.trim.isEmpty) DecodingFailure(s"Could not extract a '$tpe'")
      else DecodingFailure(s"Could not extract a '$tpe' from the path '$path'")

    /**
      * Construct a [[DecodingFailure]] when the passed ''value'' could not be converted to ''tpe'' on the ''path''
      */
    final def apply(tpe: String, value: String, path: String): DecodingFailure =
      if (path.trim.isEmpty) DecodingFailure(s"Could not convert '$value' to '$tpe'")
      else DecodingFailure(s"Could not convert '$value' to '$tpe' from the path '$path'")

  }

  /**
    * A failure decoding a field when using derivation
    * @param message the error message
    */
  final case class DecodingDerivationFailure(message: String) extends JsonLdDecoderError(message, None)
}
