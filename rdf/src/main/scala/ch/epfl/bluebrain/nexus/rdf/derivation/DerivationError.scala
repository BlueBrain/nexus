package ch.epfl.bluebrain.nexus.rdf.derivation

@SuppressWarnings(Array("IncorrectlyNamedExceptions"))
final case class DerivationError(message: String) extends Exception(message) {
  override def fillInStackTrace(): Throwable = this
}
