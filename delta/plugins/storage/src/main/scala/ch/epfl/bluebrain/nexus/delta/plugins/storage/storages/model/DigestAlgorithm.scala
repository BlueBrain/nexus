package ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model

import cats.syntax.all._
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.decoder.JsonLdDecoder
import io.circe.{Decoder, Encoder}

import java.security.MessageDigest
import scala.util.Try

/**
  * A digest algorithm
  */
final case class DigestAlgorithm private (value: String) {

  /**
    * @return the [[MessageDigest]] from the current algorithm
    */
  def digest: MessageDigest = MessageDigest.getInstance(value)

  override def toString: String = value
}

object DigestAlgorithm {

  /**
    * The default algorithm, SHA-256
    */
  final val default: DigestAlgorithm =
    new DigestAlgorithm("SHA-256")

  /**
    * Safely construct an [[DigestAlgorithm]]
    */
  final def apply(algorithm: String): Option[DigestAlgorithm] =
    Try(MessageDigest.getInstance(algorithm)).toOption.as(new DigestAlgorithm(algorithm))

  implicit final val digestAlgorithmEncoder: Encoder[DigestAlgorithm] = Encoder.encodeString.contramap(_.value)
  implicit final val digestAlgorithmDecoder: Decoder[DigestAlgorithm] =
    Decoder.decodeString.emap(str => apply(str).toRight(s"Invalid digest algorithm '$str'"))

  implicit final val digestAlgorithmJsonLdDecoder: JsonLdDecoder[DigestAlgorithm] = _.getValue(apply)
}
