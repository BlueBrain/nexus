package ch.epfl.bluebrain.nexus.delta.rdf.jsonld.decoder

import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.nxv
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.JsonLdContext.keywords
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.{ContextValue, JsonLdContext}
import io.circe.Json
import io.circe.syntax._

/**
  * The configuration used in order to build derivation for [[JsonLdDecoder]] using magnolia
  *
  * @param context   the context used to compact keys
  * @param idPredicateName the key name for the @id
  */
final case class Configuration(context: JsonLdContext, idPredicateName: String)

object Configuration {
  private val nxvCtx = JsonLdContext(ContextValue(Json.obj(keywords.vocab -> nxv.base.asJson)), vocab = Some(nxv.base))

  /**
    * The default configuration with ''nxv''' as vocab and the idPredicateName as id''
    */
  val default: Configuration = Configuration(nxvCtx, "id")
}
