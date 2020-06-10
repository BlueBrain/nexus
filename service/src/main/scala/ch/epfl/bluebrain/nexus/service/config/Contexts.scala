package ch.epfl.bluebrain.nexus.service.config

import ch.epfl.bluebrain.nexus.rdf.implicits._
import ch.epfl.bluebrain.nexus.rdf.Iri.AbsoluteIri

object Contexts {
  val base = url"https://bluebrain.github.io/nexus/contexts/"

  val resourceCtxUri: AbsoluteIri = base + "resource.json"
  val iamCtxUri: AbsoluteIri      = base + "iam.json"
  val adminCtxUri: AbsoluteIri    = base + "admin.json"
  val searchCtxUri: AbsoluteIri   = base + "search.json"
  val errorCtxUri: AbsoluteIri    = base + "error.json"
}
