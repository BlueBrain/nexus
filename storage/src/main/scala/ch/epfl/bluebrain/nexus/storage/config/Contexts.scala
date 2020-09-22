package ch.epfl.bluebrain.nexus.storage.config

import ch.epfl.bluebrain.nexus.delta.rdf.implicits._
import org.apache.jena.iri.IRI

object Contexts {

  private val base = "https://bluebrain.github.io/nexus/contexts/"

  val errorCtxIri: IRI    = iri"${base}error.json"
  val resourceCtxIri: IRI = iri"${base}resource.json"

}
