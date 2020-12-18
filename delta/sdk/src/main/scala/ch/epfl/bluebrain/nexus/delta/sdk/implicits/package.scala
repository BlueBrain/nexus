package ch.epfl.bluebrain.nexus.delta.sdk

import ch.epfl.bluebrain.nexus.delta.kernel.syntax.KamonSyntax
import ch.epfl.bluebrain.nexus.delta.rdf.instances.{TripleInstances, UriInstances}
import ch.epfl.bluebrain.nexus.delta.rdf.syntax.{IriSyntax, IterableSyntax, JsonLdEncoderSyntax, JsonSyntax, UriSyntax}
import ch.epfl.bluebrain.nexus.delta.sdk.syntax.HttpRequestSyntax

/**
  * Aggregate instances and syntax from rdf plus the current sdk instances and syntax to avoid importing multiple instances and syntax
  */
package object implicits
    extends TripleInstances
    with JsonSyntax
    with IriSyntax
    with JsonLdEncoderSyntax
    with UriInstances
    with UriSyntax
    with IterableSyntax
    with HttpRequestSyntax
    with KamonSyntax
