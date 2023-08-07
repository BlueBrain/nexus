package ch.epfl.bluebrain.nexus.storage.config

import akka.http.scaladsl.model.Uri

object Contexts {

  private val base = "https://bluebrain.github.io/nexus/contexts/"

  val errorCtxIri: Uri    = s"${base}error.json"
  val resourceCtxIri: Uri = s"${base}resource.json"

}
