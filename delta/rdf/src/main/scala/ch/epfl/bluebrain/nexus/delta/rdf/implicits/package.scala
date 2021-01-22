package ch.epfl.bluebrain.nexus.delta.rdf

import ch.epfl.bluebrain.nexus.delta.rdf.instances.{TripleInstances, UriInstances}
import ch.epfl.bluebrain.nexus.delta.rdf.syntax.{IriSyntax, IterableSyntax, JsonLdEncoderSyntax, JsonSyntax, PathSyntax, UriSyntax}

package object implicits
    extends TripleInstances
    with UriInstances
    with JsonSyntax
    with IriSyntax
    with JsonLdEncoderSyntax
    with IterableSyntax
    with UriSyntax
    with PathSyntax
