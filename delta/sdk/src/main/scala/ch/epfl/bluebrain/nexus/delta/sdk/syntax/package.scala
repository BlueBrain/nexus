package ch.epfl.bluebrain.nexus.delta.sdk

import ch.epfl.bluebrain.nexus.delta.kernel.syntax.KamonSyntax
import ch.epfl.bluebrain.nexus.delta.rdf.syntax.{IriSyntax, IterableSyntax, JsonLdEncoderSyntax, JsonSyntax, UriSyntax}

/**
  * Aggregate syntax from rdf plus the current sdk syntax to avoid importing multiple syntax
  */
package object syntax
    extends JsonSyntax
    with IriSyntax
    with JsonLdEncoderSyntax
    with UriSyntax
    with IterableSyntax
    with KamonSyntax
    with IOFunctorSyntax
    with HttpRequestSyntax
