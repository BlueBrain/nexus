package ch.epfl.bluebrain.nexus.delta.rdf

import ch.epfl.bluebrain.nexus.delta.rdf.instances.{IriInstances, TripleInstances}
import ch.epfl.bluebrain.nexus.delta.rdf.syntax.{IriSyntax, JsonLdEncoderSyntax, JsonSyntax}

package object implicits
    extends IriInstances
    with TripleInstances
    with JsonSyntax
    with IriSyntax
    with JsonLdEncoderSyntax
