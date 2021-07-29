package ch.epfl.bluebrain.nexus.delta.plugins.search.model

import ch.epfl.bluebrain.nexus.delta.sdk.error.SDKError

abstract class SearchConfigError(val reason: String) extends SDKError

object SearchConfigError {

  final case class LoadingFileError(path: String, throwable: Throwable)
      extends SearchConfigError(s"File at path '$path' could not be loaded because of '${throwable.getMessage}'.")

  final case class InvalidJsonError(path: String, details: String)
      extends SearchConfigError(s"File at path '$path' does not contain a the expect json: '$details'.")

  final case class InvalidSparqlConstructQuery(path: String, details: String)
      extends SearchConfigError(s"File at path '$path' does not contain a valid SPARQL construct query: '$details'.")

}
