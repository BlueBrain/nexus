package ch.epfl.bluebrain.nexus.delta.rdf.jsonld.decoder

sealed abstract class JsonLdDecoderError(val reason: String, details: Option[String] = None) extends Exception {
  override def fillInStackTrace(): JsonLdDecoderError = this
  override def getMessage: String                     = details.fold(reason)(d => s"$reason\nDetails: $d")
}

object JsonLdDecoderError {

  final case class DecodingFailure(message: String, details: Option[String])
      extends JsonLdDecoderError(message, details)

  final case class DecodingDerivationFailure(message: String) extends JsonLdDecoderError(message, None)

  object DecodingFailure {

    final def apply(tpe: String, path: String, details: Option[String]): DecodingFailure =
      if (path.trim.isEmpty) DecodingFailure(s"Could not extract a '$tpe'", details)
      else DecodingFailure(s"Could not extract a '$tpe' from the path '$path'", details)

    final def apply(tpe: String, value: String, path: String): DecodingFailure =
      if (path.trim.isEmpty) DecodingFailure(s"Could not convert '$value' to '$tpe'", None)
      else DecodingFailure(s"Could not convert '$value' to '$tpe' from the path '$path'", None)

  }
}
