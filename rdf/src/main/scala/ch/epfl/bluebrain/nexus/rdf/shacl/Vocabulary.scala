package ch.epfl.bluebrain.nexus.rdf.shacl

import ch.epfl.bluebrain.nexus.rdf.syntax.all._

object Vocabulary {

  /**
    * Shacl vocabulary.
    */
  object sh {
    val base             = "http://www.w3.org/ns/shacl#"
    val ValidationReport = url"${base}ValidationReport"
    val conforms         = url"${base}conforms"
  }

  /**
    * Nexus shacl vocabulary.
    */
  object nxsh {
    val base          = "https://bluebrain.github.io/nexus/vocabulary/shacl/"
    val targetedNodes = url"${base}targetedNodes"
  }
}
