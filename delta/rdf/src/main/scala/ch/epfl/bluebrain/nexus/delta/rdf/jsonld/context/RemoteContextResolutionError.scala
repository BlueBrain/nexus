package ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context

import akka.http.scaladsl.model.Uri

@SuppressWarnings(Array("IncorrectlyNamedExceptions"))
sealed abstract class RemoteContextResolutionError(reason: String) extends Exception {
  override def fillInStackTrace(): RemoteContextResolutionError = this
  override def getMessage: String                               = s"Reason: '$reason'"
}

@SuppressWarnings(Array("IncorrectlyNamedExceptions"))
object RemoteContextResolutionError {

  /**
    * The remote context with the passed ''uri'' is not found.
    */
  final case class RemoteContextNotFound(uri: Uri)
      extends RemoteContextResolutionError(s"Remote context '$uri' not found")

  /**
    * The remote context with the passed ''uri'' cannot be accessed.
    */
  final case class RemoteContextNotAccessible(uri: Uri, reason: String)
      extends RemoteContextResolutionError(s"Remote context '$uri' not accessible. Details: '$reason'")

  /**
    * The remote context with passed ''uri'' payload response is not a Json Document as expected.
    */
  final case class RemoteContextWrongPayload(uri: Uri)
      extends RemoteContextResolutionError(s"Remote context '$uri' payload response cannot be transformed to Json")

  /**
    * Circular dependency on remote context resolution
    */
  final case class RemoteContextCircularDependency(uri: Uri)
      extends RemoteContextResolutionError(
        s"Remote context '$uri' has already been resolved once. Circular dependency detected"
      )
}
