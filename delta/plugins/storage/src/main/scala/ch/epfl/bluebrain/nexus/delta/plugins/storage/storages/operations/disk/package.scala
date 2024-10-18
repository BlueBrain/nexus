package ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations

import akka.http.scaladsl.model.Uri.Path
import cats.effect.IO

import java.net.URI
import java.nio.file
import java.nio.file.Paths

package object disk {

  def absoluteDiskPath(relative: Path): IO[file.Path] = IO(Paths.get(URI.create(s"file://$relative")))

}
