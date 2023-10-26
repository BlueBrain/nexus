package ch.epfl.bluebrain.nexus.delta.plugins.jira

import cats.effect.IO
import ch.epfl.bluebrain.nexus.delta.sdk.plugin.Plugin

object JiraPlugin extends Plugin {

  override def stop(): IO[Unit] = IO.unit
}
