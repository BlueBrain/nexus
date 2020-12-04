package ch.epfl.bluebrain.nexus.delta.plugins.storage.model

import cats.syntax.all._

import java.security.MessageDigest
import scala.util.Try

/**
  * A digest algorithm
  */
final case class Algorithm private (value: String) extends AnyVal

object Algorithm {

  /**
    * The default algorithm, SHA-256
    */
  final val default: Algorithm = new Algorithm("SHA-256")

  /**
    * Safely construct an [[Algorithm]]
    */
  final def apply(algorithm: String): Option[Algorithm] =
    Try(MessageDigest.getInstance(algorithm)).toOption >> Algorithm(algorithm)
}
