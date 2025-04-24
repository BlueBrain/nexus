package ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model

import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.DigestAlgorithm.builtIn
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.decoder.JsonLdDecoder
import fs2.hashing.HashAlgorithm
import io.circe.{Decoder, Encoder}

/**
  * A digest algorithm
  */
final case class DigestAlgorithm private (value: String) {

  override def toString: String = value

  def asFs2: HashAlgorithm =
    builtIn.getOrElse(value, throw new IllegalStateException(s"$value is not a valid algorithm"))
}

object DigestAlgorithm {

  final val SHA256: DigestAlgorithm = new DigestAlgorithm("SHA-256")

  /**
    * The default algorithm, SHA-256
    */
  final val default: DigestAlgorithm = SHA256

  final val builtIn = Map(
    "MD5"         -> HashAlgorithm.MD5,
    "SHA-1"       -> HashAlgorithm.SHA1,
    "SHA-224"     -> HashAlgorithm.SHA224,
    "SHA-256"     -> HashAlgorithm.SHA256,
    "SHA-384"     -> HashAlgorithm.SHA384,
    "SHA-512"     -> HashAlgorithm.SHA512,
    "SHA-512/224" -> HashAlgorithm.SHA512_224,
    "SHA-512/256" -> HashAlgorithm.SHA512_256,
    "SHA3-224"    -> HashAlgorithm.SHA3_224,
    "SHA3-256"    -> HashAlgorithm.SHA3_256,
    "SHA3-384"    -> HashAlgorithm.SHA3_384,
    "SHA3-512"    -> HashAlgorithm.SHA3_512
  )

  final def apply(algorithm: String): Option[DigestAlgorithm] =
    Option.when(builtIn.contains(algorithm))(new DigestAlgorithm(algorithm))

  implicit final val digestAlgorithmEncoder: Encoder[DigestAlgorithm] = Encoder.encodeString.contramap(_.value)
  implicit final val digestAlgorithmDecoder: Decoder[DigestAlgorithm] =
    Decoder.decodeString.emap(str => apply(str).toRight(s"Invalid digest algorithm '$str'"))

  implicit final val digestAlgorithmJsonLdDecoder: JsonLdDecoder[DigestAlgorithm] = _.getValue(apply)
}
