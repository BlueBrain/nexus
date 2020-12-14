package ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model

import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.decoder.JsonLdDecoder
import io.circe.{Decoder, Encoder}

import java.security.MessageDigest
import scala.util.Try

/**
  * A digest algorithm
  */
final case class DigestAlgorithm private (value: String, digest: MessageDigest) {

  override def toString: String = value

  override def hashCode(): Int = value.hashCode

  override def equals(obj: Any): Boolean =
    obj match {
      case DigestAlgorithm(`value`, _) => true
      case _                           => false
    }
}

object DigestAlgorithm {

  /**
    * The default algorithm, SHA-256
    */
  final val default: DigestAlgorithm =
    new DigestAlgorithm("SHA-256", MessageDigest.getInstance("SHA-256"))

  /**
    * Safely construct an [[DigestAlgorithm]]
    */
  final def apply(algorithm: String): Option[DigestAlgorithm] =
    Try(MessageDigest.getInstance(algorithm)).toOption.map(new DigestAlgorithm(algorithm, _))

  implicit final val digestAlgorithmEncoder: Encoder[DigestAlgorithm] = Encoder.encodeString.contramap(_.value)
  implicit final val digestAlgorithmDecoder: Decoder[DigestAlgorithm] =
    Decoder.decodeString.emap(str => apply(str).toRight(s"Invalid digest algorithm '$str'"))

  implicit final val digestAlgorithmJsonLdDecoder: JsonLdDecoder[DigestAlgorithm] = _.getValue(apply)
}
