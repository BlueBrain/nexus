package ch.epfl.bluebrain.nexus.rdf

@SuppressWarnings(Array("IncorrectlyNamedExceptions"))
final case class DecodingError(message: String, history: List[CursorOp]) extends Exception {
  override def fillInStackTrace(): DecodingError = this
  override def getMessage: String =
    if (history.isEmpty) message else s"$message: ${history.mkString(",")}"
}
