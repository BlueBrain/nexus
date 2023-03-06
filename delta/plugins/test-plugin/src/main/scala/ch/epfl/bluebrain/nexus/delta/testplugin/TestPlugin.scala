package ch.epfl.bluebrain.nexus.delta.testplugin

import ch.epfl.bluebrain.nexus.delta.sdk.model.BaseUri
import ch.epfl.bluebrain.nexus.delta.sdk.plugin.Plugin
import monix.bio.Task

import scala.annotation.nowarn

@nowarn("cat=unused")
@SuppressWarnings(Array("UnusedMethodParameter"))
class TestPlugin(baseUri: BaseUri) extends Plugin {
  override def stop(): Task[Unit] = Task.pure(println(s"Stopping plugin"))
}
