package ch.epfl.bluebrain.nexus.admin

import cats.effect.Effect
import com.typesafe.scalalogging.Logger
import retry.RetryDetails

object instances {
  private val log = Logger[this.type]

  implicit def logErrors[F[_]](implicit F: Effect[F]): (Throwable, RetryDetails) => F[Unit] =
    (err, details) => F.pure(log.warn(s"Retrying with details '$details'", err))
}
