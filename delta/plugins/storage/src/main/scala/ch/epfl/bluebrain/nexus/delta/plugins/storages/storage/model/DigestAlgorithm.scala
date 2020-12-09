package ch.epfl.bluebrain.nexus.delta.plugins.storages.storage.model

import cats.syntax.all._
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.decoder.JsonLdDecoder
import io.circe.Encoder

import java.security.MessageDigest
import scala.util.Try

/**
  * A digest algorithm
  */
final case class DigestAlgorithm private (value: String) extends AnyVal {
  override def toString: String = value
}

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

  implicit final val digestAlgorithmEncoder: Encoder[DigestAlgorithm] = Encoder.encodeString.contramap(_.value)

  implicit final val digestAlgorithmJsonLdDecoder: JsonLdDecoder[DigestAlgorithm] = _.getValue(apply)
}
