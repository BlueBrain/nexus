package ch.epfl.bluebrain.nexus.delta.sdk.syntax

import akka.http.scaladsl.model.Uri
import ch.epfl.bluebrain.nexus.delta.sdk.syntax.UriSyntax.uri
import org.apache.jena.iri.IRI

trait IriUriSyntax {
  implicit final def iriUriSyntax(iri: IRI): IriUriOpts = new IriUriOpts(iri)
}

final class IriUriOpts(private val iri: IRI) extends AnyVal {

  /**
    * Constructs a [[Uri]] from an [[IRI]]
    */
  def toUri: Either[String, Uri] =
    uri(iri.toString)
}
