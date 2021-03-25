package ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.serialization

import ch.epfl.bluebrain.nexus.delta.kernel.utils.UUIDF
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.model.{contexts, CompositeViewFields, CompositeViewRejection}
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.RemoteContextResolution
import ch.epfl.bluebrain.nexus.delta.rdf.syntax.jsonOpsSyntax
import ch.epfl.bluebrain.nexus.delta.sdk.jsonld.JsonLdSourceProcessor.JsonLdSourceDecoder
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.Project
import io.circe.Json
import io.circe.syntax._
import monix.bio.IO

/**
  * Decoder for [[CompositeViewFields]] which maps some fields to string, before decoding to get around lack of support
  * for @json in json ld library.
  */
final class CompositeViewFieldsJsonLdSourceDecoder private (
    decoder: JsonLdSourceDecoder[CompositeViewRejection, CompositeViewFields]
) {
  def apply(project: Project, source: Json)(implicit
      rcr: RemoteContextResolution
  ): IO[CompositeViewRejection, (Iri, CompositeViewFields)] = {
    decoder(project, mapJsonToString(source))
  }

  def apply(project: Project, iri: Iri, source: Json)(implicit
      rcr: RemoteContextResolution
  ): IO[CompositeViewRejection, CompositeViewFields] = {

    decoder(
      project,
      iri,
      mapJsonToString(source)
    )
  }

  private def mapJsonToString(json: Json): Json = json
    .mapAllKeys("mapping", _.noSpaces.asJson)
    .mapAllKeys("settings", _.noSpaces.asJson)
    .mapAllKeys("context", _.noSpaces.asJson)
}

object CompositeViewFieldsJsonLdSourceDecoder {

  def apply(uuidF: UUIDF): CompositeViewFieldsJsonLdSourceDecoder =
    new CompositeViewFieldsJsonLdSourceDecoder(
      new JsonLdSourceDecoder[CompositeViewRejection, CompositeViewFields](contexts.compositeView, uuidF)
    )

}
