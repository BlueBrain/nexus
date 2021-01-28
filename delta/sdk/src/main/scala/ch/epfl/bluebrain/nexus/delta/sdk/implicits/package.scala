package ch.epfl.bluebrain.nexus.delta.sdk

import ch.epfl.bluebrain.nexus.delta.kernel.syntax.KamonSyntax
import ch.epfl.bluebrain.nexus.delta.rdf.instances.{TripleInstances, UriInstances}
import ch.epfl.bluebrain.nexus.delta.rdf.syntax.{IriSyntax, IterableSyntax, JsonLdEncoderSyntax, JsonSyntax, PathSyntax, UriSyntax}
import ch.epfl.bluebrain.nexus.delta.sdk.syntax.{HttpRequestSyntax, HttpResponseFieldsSyntax, IOFunctorSyntax}

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
    with PathSyntax
    with IterableSyntax
    with KamonSyntax
    with IOFunctorSyntax
    with HttpRequestSyntax
    with HttpResponseFieldsSyntax
