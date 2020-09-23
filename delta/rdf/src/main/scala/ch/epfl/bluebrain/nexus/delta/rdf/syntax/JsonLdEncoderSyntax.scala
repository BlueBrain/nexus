package ch.epfl.bluebrain.nexus.delta.rdf.syntax

import ch.epfl.bluebrain.nexus.delta.rdf._
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.{JsonLd, JsonLdEncoder}
import monix.bio.IO

trait JsonLdEncoderSyntax {
  implicit final def jsonLdEncoderSyntax[A](a: A): JsonLdEncoderOpts[A] = new JsonLdEncoderOpts(a)
}

final class JsonLdEncoderOpts[A](private val a: A) extends AnyVal {

  /**
    * Converts the value ''a'' to [[JsonLd]].
    */
  def toJsonLd(implicit encoder: JsonLdEncoder[A]): IO[RdfError, JsonLd] = encoder(a)
}
