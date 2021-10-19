package ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.pipe

import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.sdk.views.model.IndexingData
import io.circe.Decoder
import io.circe.generic.semiauto.deriveDecoder
import monix.bio.Task

object FilterByType {

  final private case class Context(types: Set[Iri])

  implicit private val contextReader: Decoder[Context] = deriveDecoder[Context]

  val value: Pipe =
    Pipe.withContext(
      "filterByType",
      (context: Context, data: IndexingData) =>
        Task.pure(
          Option.when(context.types.isEmpty || context.types.exists(data.types.contains))(data)
        )
    )
}
