package ch.epfl.bluebrain.nexus.rdf

import ch.epfl.bluebrain.nexus.rdf.akka.syntax.{FromAkkaSyntax, ToAkkaSyntax}
import ch.epfl.bluebrain.nexus.rdf.jena.syntax.{FromJenaSyntax, ToJenaSyntax}
import ch.epfl.bluebrain.nexus.rdf.jsonld.instances.RdfCirceInstances
import ch.epfl.bluebrain.nexus.rdf.jsonld.syntax.JsonLdSyntax
import ch.epfl.bluebrain.nexus.rdf.syntax.{IriSyntax, NodeSyntax}

object implicits
    extends IriSyntax
    with NodeSyntax
    with FromJenaSyntax
    with ToJenaSyntax
    with FromAkkaSyntax
    with ToAkkaSyntax
    with JsonLdSyntax
    with RdfCirceInstances
