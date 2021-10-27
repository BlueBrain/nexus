package ch.epfl.bluebrain.nexus.delta.sdk.views.pipe

import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.decoder.JsonLdDecoder
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.decoder.semiauto.deriveJsonLdDecoder
import ch.epfl.bluebrain.nexus.delta.sdk.views.model.IndexingData
import monix.bio.Task

/**
  * Filters a resource according to its types
  */
object FilterByType {

  final private case class Context(types: Set[Iri])

  val value: Pipe = {
    implicit val contextReader: JsonLdDecoder[Context] = deriveJsonLdDecoder[Context]
    Pipe.withContext(
      "filterByType",
      (context: Context, data: IndexingData) =>
        Task.pure(
          Option.when(context.types.isEmpty || context.types.exists(data.types.contains))(data)
        )
    )
  }
}
