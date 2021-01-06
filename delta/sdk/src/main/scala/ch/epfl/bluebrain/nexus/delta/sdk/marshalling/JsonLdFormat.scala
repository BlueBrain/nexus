package ch.epfl.bluebrain.nexus.delta.sdk.marshalling

/**
  * Enumeration of allowed Json-LD output formats on the service
  */
sealed trait JsonLdFormat extends Product with Serializable

object JsonLdFormat {

  /**
    * Expanded JSON-LD output format as defined in https://www.w3.org/TR/json-ld-api/#expansion-algorithms
    */
  final case object Expanded extends JsonLdFormat

  /**
    * Compacted JSON-LD output format as defined in https://www.w3.org/TR/json-ld-api/#compaction-algorithms
    */
  final case object Compacted extends JsonLdFormat
}
