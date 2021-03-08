package ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch

import ch.epfl.bluebrain.nexus.delta.sdk.plugin.Plugin
import monix.bio.Task

object ElasticSearchPlugin extends Plugin {

  override def stop(): Task[Unit] = Task.unit
}
