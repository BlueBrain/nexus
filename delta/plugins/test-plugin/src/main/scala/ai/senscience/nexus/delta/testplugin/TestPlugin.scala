package ai.senscience.nexus.delta.testplugin

import cats.effect.IO
import ch.epfl.bluebrain.nexus.delta.sdk.model.BaseUri
import ch.epfl.bluebrain.nexus.delta.sdk.plugin.Plugin

import scala.annotation.nowarn

@nowarn("cat=unused")
@SuppressWarnings(Array("UnusedMethodParameter"))
class TestPlugin(baseUri: BaseUri) extends Plugin {
  override def stop(): IO[Unit] = IO.println(s"Stopping plugin")
}
