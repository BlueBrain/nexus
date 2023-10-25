package ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch

import cats.effect.IO
import ch.epfl.bluebrain.nexus.delta.sdk.plugin.Plugin

object ElasticSearchPlugin extends Plugin {

  override def stop(): IO[Unit] = IO.unit
}
