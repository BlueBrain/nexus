package ch.epfl.bluebrain.nexus.delta.rdf

import ch.epfl.bluebrain.nexus.delta.rdf.instances.{IriInstances, UriInstances}
import ch.epfl.bluebrain.nexus.delta.rdf.syntax.{IriSyntax, JsonSyntax, UriSyntax}

package object implicits extends IriInstances with UriInstances with JsonSyntax with IriSyntax with UriSyntax
