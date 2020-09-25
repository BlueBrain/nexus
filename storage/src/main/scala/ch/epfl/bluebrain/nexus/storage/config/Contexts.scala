package ch.epfl.bluebrain.nexus.storage.config

import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.implicits._

object Contexts {

  private val base = "https://bluebrain.github.io/nexus/contexts/"

  val errorCtxIri: Iri    = iri"${base}error.json"
  val resourceCtxIri: Iri = iri"${base}resource.json"

}
