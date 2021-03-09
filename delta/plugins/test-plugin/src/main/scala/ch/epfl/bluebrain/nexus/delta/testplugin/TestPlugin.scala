package ch.epfl.bluebrain.nexus.delta.testplugin

import ch.epfl.bluebrain.nexus.delta.sdk.Permissions
import ch.epfl.bluebrain.nexus.delta.sdk.plugin.Plugin
import monix.bio.Task

import scala.annotation.unused

class TestPlugin(@unused permissions: Permissions) extends Plugin {
  override def stop(): Task[Unit] = Task.pure(println(s"Stopping plugin"))
}
