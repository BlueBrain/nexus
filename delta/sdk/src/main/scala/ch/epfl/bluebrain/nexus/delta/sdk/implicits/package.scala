package ch.epfl.bluebrain.nexus.delta.sdk

import ch.epfl.bluebrain.nexus.delta.rdf.instances.TripleInstances
import ch.epfl.bluebrain.nexus.delta.rdf.syntax.{IriSyntax, JsonLdEncoderSyntax, JsonSyntax}
import ch.epfl.bluebrain.nexus.delta.sdk.instances.UriInstances
import ch.epfl.bluebrain.nexus.delta.sdk.syntax.{IriUriSyntax, UriSyntax}

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
    with IriUriSyntax
