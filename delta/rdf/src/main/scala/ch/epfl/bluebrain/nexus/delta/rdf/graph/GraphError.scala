package ch.epfl.bluebrain.nexus.delta.rdf.graph

import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.JsonLdError

@SuppressWarnings(Array("IncorrectlyNamedExceptions"))
sealed abstract class GraphError(reason: String, details: Option[String] = None) extends Exception {
  override def fillInStackTrace(): GraphError = this
  override def getMessage: String             = details.fold(reason)(d => s"$reason\nDetails: $d")
}

@SuppressWarnings(Array("IncorrectlyNamedExceptions"))
object GraphError {

  /**
    * An error when attempting to convert the [[Graph]] to the passed ''format''
    */
  final case class ConversionError(format: String, details: String)
      extends GraphError(s"Error converting the Graph to format '$format'.", Some(details))

  /**
    * An error when dealing with the JsonLdApi
    */
  final case class JsonLdErrorWrapper(error: JsonLdError) extends GraphError(error.getReason, error.getDetails)

}
