package ch.epfl.bluebrain.nexus.delta.utils

sealed trait JsonLdFormat extends Product with Serializable

object JsonLdFormat {

  final case object Compacted extends JsonLdFormat
  final case object Expanded extends JsonLdFormat

}
