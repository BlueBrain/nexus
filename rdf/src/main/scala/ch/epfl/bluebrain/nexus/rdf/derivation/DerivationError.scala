package ch.epfl.bluebrain.nexus.rdf.derivation

final case class DerivationError(message: String) extends Exception(message) {
  override def fillInStackTrace(): Throwable = this
}
