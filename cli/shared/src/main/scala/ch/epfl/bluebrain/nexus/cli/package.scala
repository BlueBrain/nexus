package ch.epfl.bluebrain.nexus

import cats.effect.Sync
import io.chrisdavenport.log4cats.Logger
import io.chrisdavenport.log4cats.slf4j.Slf4jLogger

package object cli {

  type ClientErrOr[A] = Either[ClientError, A]

  implicit def unsafeLogger[F[_]: Sync]: Logger[F] = Slf4jLogger.getLogger[F]

}
