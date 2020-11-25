package ch.epfl.bluebrain.nexus.delta.rdf.jsonld.decoder

import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.JsonLdContext

/**
  * The configuration used in order to build derivation for [[JsonLdDecoder]] using magnolia
  *
  * @param context   the context used to compact keys
  * @param idPredicateName the key name for the @id
  */
final case class Configuration(context: JsonLdContext, idPredicateName: String)

object Configuration {
  val default: Configuration = Configuration(JsonLdContext.empty, "id")
}
