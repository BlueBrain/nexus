package ch.epfl.bluebrain.nexus.delta.sdk.marshalling

import cats.syntax.all._
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.contexts
import ch.epfl.bluebrain.nexus.delta.sdk.model.{BaseUri, ResourceF}
import ch.epfl.bluebrain.nexus.delta.sdk.syntax._
import io.circe.Json
import io.circe.syntax.EncoderOps

object AnnotatedSource {

  /**
    * Merges the source with the metadata of [[ResourceF]]
    */
  def apply(resourceF: ResourceF[_], source: Json)(implicit baseUri: BaseUri): Json = {
    val sourceWithoutMetadata = source.removeMetadataKeys
    val metadataJson          = resourceF.void.asJson
    metadataJson.deepMerge(sourceWithoutMetadata).addContext(contexts.metadata)
  }

}
