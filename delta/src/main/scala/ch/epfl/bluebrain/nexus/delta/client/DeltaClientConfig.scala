package ch.epfl.bluebrain.nexus.delta.client

import ch.epfl.bluebrain.nexus.rdf.Iri.AbsoluteIri

import scala.concurrent.duration._

/**
  * Configuration for [[DeltaClient]]
  *
  * @param publicIri     base URL for Delta service
  * @param sseRetryDelay delay for retrying after completion on SSE. 1 second by default
  */
final case class DeltaClientConfig(publicIri: AbsoluteIri, sseRetryDelay: FiniteDuration = 1.second) {
  lazy val resourcesIri = publicIri + "resources"
  lazy val projectsIri  = publicIri + "projects"
}
