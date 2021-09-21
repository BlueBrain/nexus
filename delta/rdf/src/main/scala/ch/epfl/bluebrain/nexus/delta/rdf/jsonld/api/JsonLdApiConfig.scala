package ch.epfl.bluebrain.nexus.delta.rdf.jsonld.api

import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.api.JsonLdApiConfig.ErrorHandling
import pureconfig.ConfigReader
import pureconfig.generic.semiauto.{deriveEnumerationReader, deriveReader}

/**
  * Configuration for the json
  * @param strict
  *   set the Jena parser to strict mode
  * @param extraChecks
  *   set the Jena parser to perform additional checks
  * @param errorHandling
  *   set the error handler fpr the jena parser
  */
final case class JsonLdApiConfig(strict: Boolean, extraChecks: Boolean, errorHandling: ErrorHandling)

object JsonLdApiConfig {

  sealed trait ErrorHandling

  object ErrorHandling {

    /**
      * Keep the default error handler, errors will throw exception but only log warnings
      */
    case object Default extends ErrorHandling

    /**
      * Error handler that will throw exceptions for both errors and warnings
      */
    case object Strict extends ErrorHandling

    /**
      * Error handler that will throw exceptions for errors but do nothing about warnings
      */
    case object NoWarning extends ErrorHandling

    implicit final val flavourReader: ConfigReader[ErrorHandling] =
      deriveEnumerationReader

  }

  implicit final val jsonLdApiConfigReader: ConfigReader[JsonLdApiConfig] =
    deriveReader[JsonLdApiConfig]

}
