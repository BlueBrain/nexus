package ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context

import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.JsonLdContext.keywords
import io.circe.{Encoder, Json}
import io.circe.generic.extras.Configuration
import io.circe.generic.extras.semiauto.deriveConfiguredEncoder

import scala.annotation.nowarn

sealed abstract class RemoteContextResolutionError(reason: String, details: Option[String] = None) extends Exception {
  override def fillInStackTrace(): RemoteContextResolutionError = this
  override def getMessage: String                               = reason

  def getDetails: Option[String] = details
}

@nowarn("cat=unused")
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
      extends RemoteContextResolutionError(
        s"Remote context '$iri' not accessible because of: '$reason'",
        details = details.map(_.noSpaces)
      )

  /**
    * The remote context with passed ''iri'' payload response is not a Json Document as expected.
    */
  final case class RemoteContextWrongPayload(iri: Iri)
      extends RemoteContextResolutionError(s"Remote context '$iri' payload response cannot be transformed to Json")

  implicit private val config: Configuration = Configuration.default.withDiscriminator(keywords.tpe)

  implicit val remoteContextResolutionErrorEncoder: Encoder.AsObject[RemoteContextResolutionError] =
    deriveConfiguredEncoder[RemoteContextResolutionError]
}
