package ch.epfl.bluebrain.nexus.delta.sdk.marshalling

import cats.syntax.all._
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.contexts
import ch.epfl.bluebrain.nexus.delta.sdk.model.{BaseUri, ResourceF}
import ch.epfl.bluebrain.nexus.delta.sdk.syntax._
import io.circe.Json
import io.circe.syntax.EncoderOps

object AnnotatedSource {

  /**
    * Merge the source with the metadata when annotation is requested or return only the source otherwise
    */
  def when(annotate: Boolean)(resourceF: ResourceF[_], source: Json)(implicit baseUri: BaseUri): Json =
    if (annotate) apply(resourceF, source) else source

  /**
    * Merges the source with the metadata of [[ResourceF]]
    */
  def apply(resourceF: ResourceF[_], source: Json)(implicit baseUri: BaseUri): Json = {
    val sourceWithoutMetadata = source.removeMetadataKeys()
    val metadataJson          = resourceF.void.asJson
    metadataJson.deepMerge(sourceWithoutMetadata).addContext(contexts.metadata)
  }

}
