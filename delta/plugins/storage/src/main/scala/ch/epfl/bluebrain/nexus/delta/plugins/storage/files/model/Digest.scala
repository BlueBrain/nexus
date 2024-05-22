package ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model

import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.DigestAlgorithm
import io.circe.syntax._
import io.circe.{Encoder, JsonObject}

/**
  * A digest value
  */
sealed trait Digest extends Product with Serializable {
  def computed: Boolean = this != Digest.NotComputedDigest
}

object Digest {

  /**
    * A computed digest value with the algorithm used to compute it.
    *
    * @param algorithm
    *   the algorithm used in order to compute the digest
    * @param value
    *   the actual value of the digest of the file
    */
  final case class ComputedDigest(algorithm: DigestAlgorithm, value: String) extends Digest

  /**
    * A digest as provided by S3 when the file is uploaded in parts.
    *
    * @param algorithm
    *   the algorithm used in order to compute the digest
    * @param value
    *   the actual value of the digest of the file
    * @param numberOfParts
    *   the number of parts the digest was computed from
    */
  final case class MultiPartDigest(algorithm: DigestAlgorithm, value: String, numberOfParts: Int) extends Digest

  /**
    * A digest that does not yield a value because it is still being computed
    */
  final case object NoDigest extends Digest

  val none: Digest = NoDigest

  /**
    * A digest that does not yield a value because it is still being computed
    */
  final case object NotComputedDigest extends Digest

  implicit val digestEncoder: Encoder.AsObject[Digest] = Encoder.encodeJsonObject.contramapObject {
    case ComputedDigest(algorithm, value)                 => JsonObject("_algorithm" -> algorithm.asJson, "_value" -> value.asJson)
    case MultiPartDigest(algorithm, value, numberOfParts) =>
      JsonObject("_algorithm" -> algorithm.asJson, "_value" -> value.asJson, "_numberOfParts" -> numberOfParts.asJson)
    case NotComputedDigest                                => JsonObject("_value" -> "".asJson)
    case NoDigest                                         => JsonObject("_value" -> "".asJson)
  }
}
