package ch.epfl.bluebrain.nexus.delta.sdk.syntax

import akka.http.scaladsl.model.Uri
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.sdk.syntax.UriSyntax.uri

trait IriUriSyntax {
  implicit final def iriUriSyntax(iri: Iri): IriUriOpts = new IriUriOpts(iri)
}

final class IriUriOpts(private val iri: Iri) extends AnyVal {

  /**
    * Constructs a [[Uri]] from an [[Iri]]
    */
  def toUri: Either[String, Uri] =
    uri(iri.toString)
}
