package ch.epfl.bluebrain.nexus.admin.config

import ch.epfl.bluebrain.nexus.rdf.Iri.AbsoluteIri
import ch.epfl.bluebrain.nexus.rdf.implicits._

object Contexts {
  val base: AbsoluteIri = url"https://bluebrain.github.io/nexus/contexts/"

  val adminCtxUri: AbsoluteIri    = base + "admin.json"
  val errorCtxUri: AbsoluteIri    = base + "error.json"
  val resourceCtxUri: AbsoluteIri = base + "resource.json"
  val searchCtxUri: AbsoluteIri   = base + "search.json"
}
