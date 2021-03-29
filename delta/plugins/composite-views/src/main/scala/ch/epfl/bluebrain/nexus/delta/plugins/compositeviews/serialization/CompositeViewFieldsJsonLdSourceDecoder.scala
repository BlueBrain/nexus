package ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.serialization

import ch.epfl.bluebrain.nexus.delta.kernel.utils.UUIDF
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.model.{contexts, CompositeViewFields, CompositeViewRejection}
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.syntax.jsonOpsSyntax
import ch.epfl.bluebrain.nexus.delta.sdk.jsonld.JsonLdSourceProcessor.JsonLdSourceResolvingDecoder
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Caller
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.Project
import ch.epfl.bluebrain.nexus.delta.sdk.model.resolvers.ResolverContextResolution
import io.circe.Json
import io.circe.syntax._
import monix.bio.IO

/**
  * Decoder for [[CompositeViewFields]] which maps some fields to string, before decoding to get around lack of support
  * for @json in json ld library.
  */
//TODO remove when support for @json is added in json-ld library
final class CompositeViewFieldsJsonLdSourceDecoder private (
    decoder: JsonLdSourceResolvingDecoder[CompositeViewRejection, CompositeViewFields]
) {
  def apply(project: Project, source: Json)(implicit
      caller: Caller
  ): IO[CompositeViewRejection, (Iri, CompositeViewFields)] = {
    decoder(project, mapJsonToString(source))
  }

  def apply(project: Project, iri: Iri, source: Json)(implicit
      caller: Caller
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

  def apply(uuidF: UUIDF, contextResolution: ResolverContextResolution): CompositeViewFieldsJsonLdSourceDecoder =
    new CompositeViewFieldsJsonLdSourceDecoder(
      new JsonLdSourceResolvingDecoder[CompositeViewRejection, CompositeViewFields](
        contexts.compositeViews,
        contextResolution,
        uuidF
      )
    )

}
