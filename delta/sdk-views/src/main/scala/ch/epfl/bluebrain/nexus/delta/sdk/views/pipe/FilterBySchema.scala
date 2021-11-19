package ch.epfl.bluebrain.nexus.delta.sdk.views.pipe

import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.decoder.JsonLdDecoder
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.decoder.semiauto.deriveJsonLdDecoder
import ch.epfl.bluebrain.nexus.delta.sdk.views.model.IndexingData.IndexingResource
import monix.bio.Task

/**
  * Filters a resource according to its schema
  */
object FilterBySchema {

  final private case class Config(types: Set[Iri])

  val value: Pipe = {
    implicit val configDecoder: JsonLdDecoder[Config] = deriveJsonLdDecoder[Config]
    Pipe.withConfig(
      "filterBySchema",
      (config: Config, data: IndexingResource) =>
        Task.pure(
          Option.when(config.types.isEmpty || config.types.contains(data.schema.iri))(data)
        )
    )
  }
}
