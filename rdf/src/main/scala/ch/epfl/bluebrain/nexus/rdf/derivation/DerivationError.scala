package ch.epfl.bluebrain.nexus.rdf.derivation

@SuppressWarnings(Array("IncorrectlyNamedExceptions"))
sealed abstract class DerivationError(message: String) extends Exception(message) {
  override def fillInStackTrace(): Throwable = this

}

@SuppressWarnings(Array("IncorrectlyNamedExceptions"))
object DerivationError {

  final case object DuplicatedParameters
      extends DerivationError("Duplicate key detected after applying transformation function for case class parameters")

  final case class InvalidUriTransformation(param: String, uriString: String)
      extends DerivationError(s"The parameter '$param' was converted to the invalid Uri '$uriString'")
}
