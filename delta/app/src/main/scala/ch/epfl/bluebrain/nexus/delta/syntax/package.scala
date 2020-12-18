package ch.epfl.bluebrain.nexus.delta

import ch.epfl.bluebrain.nexus.delta.rdf.syntax.{IriSyntax, IterableSyntax, JsonLdEncoderSyntax, JsonSyntax, UriSyntax}
import ch.epfl.bluebrain.nexus.delta.sdk.syntax.IOFunctorSyntax

/**
  * Aggregate syntax from rdf plus sdk to avoid importing multiple syntax
  */
package object syntax
    extends JsonSyntax
    with IriSyntax
    with JsonLdEncoderSyntax
    with UriSyntax
    with IterableSyntax
    with HttpResponseFieldsSyntax
    with IOFunctorSyntax
