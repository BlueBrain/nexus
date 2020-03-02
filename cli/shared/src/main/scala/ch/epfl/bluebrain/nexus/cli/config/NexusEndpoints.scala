package ch.epfl.bluebrain.nexus.cli.config

import java.util.UUID

import ch.epfl.bluebrain.nexus.cli.types.Label
import org.http4s.Uri

/**
  * A class that generated the Nexus Uri endpoints for the different clients.
  *
  * @param base the Nexus base Uri
  */
class NexusEndpoints(base: Uri) {

  def eventsUri: Uri =
    base / "events"

  def eventsUri(organization: Label): Uri =
    base / "resources" / organization.value / "events"

  def eventsUri(organization: Label, project: Label): Uri =
    base / "resources" / organization.value / project.value / "events"

  def projectUri(organization: UUID, project: UUID): Uri =
    base / "projects" / organization.toString / project.toString

  def sparqlQueryUri(organization: Label, project: Label, viewId: Uri): Uri =
    base / "views" / organization.value / project.value / viewId.toString() / "sparql"

}

object NexusEndpoints {

  /**
    * Creates a [[NexusEndpoints]] from the passed Nexus base Uri
    */
  final def apply(base: Uri): NexusEndpoints = new NexusEndpoints(base)

  /**
    * Creates a [[NexusEndpoints]] from the passed Nexus configuration
    */
  final def apply(config: NexusConfig): NexusEndpoints = apply(config.endpoint)
}
