package ch.epfl.bluebrain.nexus.delta.plugins.jira

import ch.epfl.bluebrain.nexus.delta.sdk.plugin.Plugin
import monix.bio.Task

object JiraPlugin extends Plugin {

  override def stop(): Task[Unit] = Task.unit
}
