package ch.epfl.bluebrain.nexus

import java.util.UUID

import cats.effect.Sync
import ch.epfl.bluebrain.nexus.cli.error.ClientError
import ch.epfl.bluebrain.nexus.cli.types.Label
import io.chrisdavenport.log4cats.Logger
import io.chrisdavenport.log4cats.slf4j.Slf4jLogger

package object cli {

  type ClientErrOr[A]  = Either[ClientError, A]
  type ProjectLabelRef = (Label, Label)
  type ProjectUuidRef  = (UUID, UUID)

  implicit def unsafeLogger[F[_]: Sync]: Logger[F] = Slf4jLogger.getLogger[F]

}
