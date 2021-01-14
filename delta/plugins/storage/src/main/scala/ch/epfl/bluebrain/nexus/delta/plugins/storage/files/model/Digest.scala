package ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model

import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.DigestAlgorithm
import io.circe.syntax._
import io.circe.{Encoder, JsonObject}

/**
  * A digest value
  */
sealed trait Digest extends Product with Serializable

object Digest {

  /**
    * A computed digest value with the algorithm used to compute it.
    *
    * @param algorithm the algorithm used in order to compute the digest
    * @param value     the actual value of the digest of the file
    */
  final case class ComputedDigest(algorithm: DigestAlgorithm, value: String) extends Digest

  /**
    * A digest that does not yield a value because it is still being computed
    */
  final case object NotComputedDigest extends Digest

  implicit val digestEncoder: Encoder.AsObject[Digest] = Encoder.encodeJsonObject.contramapObject {
    case ComputedDigest(algorithm, value) => JsonObject("_algorithm" -> algorithm.asJson, "_value" -> value.asJson)
    case NotComputedDigest                => JsonObject("_value" -> "".asJson)
  }
}
