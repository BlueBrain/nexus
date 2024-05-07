package ch.epfl.bluebrain.nexus.ship

import ch.epfl.bluebrain.nexus.delta.sourcing.offset.Offset
import fs2.io.file.Path
import io.circe.Encoder
import io.circe.generic.semiauto.deriveEncoder

sealed trait ShipCommand extends Product with Serializable

object ShipCommand {

  final case class RunCommand(path: Path, config: Option[Path], offset: Offset, mode: RunMode) extends ShipCommand

  object RunCommand {

    implicit val runCommandEncoder: Encoder[RunCommand] = {
      implicit val pathEncoder: Encoder[Path] = Encoder.encodeString.contramap(_.toString)
      deriveEncoder[RunCommand]
    }
  }

  final case class ShowConfigCommand(config: Option[Path]) extends ShipCommand

}
