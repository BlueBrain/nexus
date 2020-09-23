package ch.epfl.bluebrain.nexus.delta.rdf

import ch.epfl.bluebrain.nexus.delta.rdf.instances.{IriInstances, TripleInstances, UriInstances}
import ch.epfl.bluebrain.nexus.delta.rdf.syntax.{IriSyntax, JsonLdEncoderSyntax, JsonSyntax, UriSyntax}

package object implicits
    extends IriInstances
    with UriInstances
    with TripleInstances
    with JsonSyntax
    with IriSyntax
    with UriSyntax
    with JsonLdEncoderSyntax
