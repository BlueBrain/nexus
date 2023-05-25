package ch.epfl.bluebrain.nexus.delta.plugins.search.model

import ch.epfl.bluebrain.nexus.delta.sdk.error.SDKError
import pureconfig.error.ConfigReaderFailures

import scala.concurrent.duration.FiniteDuration

abstract class SearchConfigError(val reason: String) extends SDKError

object SearchConfigError {

  final case class LoadingFileError(path: String, throwable: Throwable)
      extends SearchConfigError(s"File at path '$path' could not be loaded because of '${throwable.getMessage}'.")

  final case class InvalidJsonError(path: String, details: String)
      extends SearchConfigError(s"File at path '$path' does not contain a the expect json: '$details'.")

  final case class InvalidSparqlConstructQuery(path: String, details: String)
      extends SearchConfigError(s"File at path '$path' does not contain a valid SPARQL construct query: '$details'.")

  final case class InvalidSuites(failures: ConfigReaderFailures)
      extends SearchConfigError(s"Configuration for search suites is invalid:\n${failures.prettyPrint()}")

  final case class InvalidRebuildStrategy(rebuild: FiniteDuration, minInterval: FiniteDuration)
      extends SearchConfigError(
        s"The specified rebuild strategy '$rebuild' was lower than the minimum '$minInterval' interval."
      )

}
