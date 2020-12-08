package ch.epfl.bluebrain.nexus.delta.plugins.storage.model

import cats.syntax.all._

import java.security.MessageDigest
import scala.util.Try

/**
  * A digest algorithm
  */
final case class DigestAlgorithm private (value: String) extends AnyVal

object DigestAlgorithm {

  /**
    * The default algorithm, SHA-256
    */
  final val default: DigestAlgorithm = new DigestAlgorithm("SHA-256")

  /**
    * Safely construct an [[DigestAlgorithm]]
    */
  final def apply(algorithm: String): Option[DigestAlgorithm] =
    Try(MessageDigest.getInstance(algorithm)).toOption >> DigestAlgorithm(algorithm)
}
