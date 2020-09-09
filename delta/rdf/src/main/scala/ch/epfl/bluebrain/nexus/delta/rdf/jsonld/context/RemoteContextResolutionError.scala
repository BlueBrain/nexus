package ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context

import org.apache.jena.iri.IRI

sealed abstract class RemoteContextResolutionError(reason: String) extends Exception {
  override def fillInStackTrace(): RemoteContextResolutionError = this
  override def getMessage: String                               = s"Reason: '$reason'"
}

object RemoteContextResolutionError {

  /**
    * The remote context with the passed ''iri'' is not found.
    */
  final case class RemoteContextNotFound(iri: IRI)
      extends RemoteContextResolutionError(s"Remote context '$iri' not found")

  /**
    * The remote context with the passed ''iri'' cannot be accessed.
    */
  final case class RemoteContextNotAccessible(iri: IRI, reason: String)
      extends RemoteContextResolutionError(s"Remote context '$iri' not accessible. Details: '$reason'")

  /**
    * The remote context with passed ''iri'' payload response is not a Json Document as expected.
    */
  final case class RemoteContextWrongPayload(iri: IRI)
      extends RemoteContextResolutionError(s"Remote context '$iri' payload response cannot be transformed to Json")

  /**
    * Circular dependency on remote context resolution
    */
  final case class RemoteContextCircularDependency(iri: IRI)
      extends RemoteContextResolutionError(
        s"Remote context '$iri' has already been resolved once. Circular dependency detected"
      )
}
