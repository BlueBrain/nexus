package ch.epfl.bluebrain.nexus.ship

import ch.epfl.bluebrain.nexus.delta.sourcing.offset.Offset
import fs2.io.file.Path

sealed trait ShipCommand extends Product with Serializable

object ShipCommand {

  final case class RunCommand(path: Path, config: Option[Path], offset: Offset, mode: RunMode) extends ShipCommand

  final case class ShowConfigCommand(config: Option[Path]) extends ShipCommand

}
