package ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context

import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import io.circe.Json

sealed abstract class RemoteContextResolutionError(reason: String) extends Exception {
  override def fillInStackTrace(): RemoteContextResolutionError = this
  override def getMessage: String                               = s"Reason: '$reason'"
}

object RemoteContextResolutionError {

  /**
    * The remote context with the passed ''iri'' is not found.
    */
  final case class RemoteContextNotFound(iri: Iri)
      extends RemoteContextResolutionError(s"Remote context '$iri' not found")

  /**
    * The remote context with the passed ''iri'' cannot be accessed.
    */
  final case class RemoteContextNotAccessible(iri: Iri, reason: String, details: Option[Json] = None)
      extends RemoteContextResolutionError(s"Remote context '$iri' not accessible. Details: '$reason'")

  /**
    * The remote context with passed ''iri'' payload response is not a Json Document as expected.
    */
  final case class RemoteContextWrongPayload(iri: Iri)
      extends RemoteContextResolutionError(s"Remote context '$iri' payload response cannot be transformed to Json")
}
