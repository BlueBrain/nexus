package ch.epfl.bluebrain.nexus.delta.sdk.views.pipe

import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.sdk.views.model.IndexingData
import io.circe.Decoder
import io.circe.generic.semiauto.deriveDecoder
import monix.bio.Task

/**
  * Filters a resource according to its schema
  */
object FilterBySchema {

  final private case class Context(types: Set[Iri])

  val value: Pipe = {
    implicit val contextReader: Decoder[Context] = deriveDecoder[Context]
    Pipe.withContext(
      "filterBySchema",
      (context: Context, data: IndexingData) =>
        Task.pure(
          Option.when(context.types.isEmpty || context.types.contains(data.schema.iri))(data)
        )
    )
  }
}
