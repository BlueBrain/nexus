package ch.epfl.bluebrain.nexus.delta.plugins.statistics

import ch.epfl.bluebrain.nexus.delta.sdk.plugin.Plugin
import monix.bio.Task

object StatisticsPlugin extends Plugin {

  override def stop(): Task[Unit] = Task.unit
}
