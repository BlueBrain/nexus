package ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.transformation

import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.sdk.views.model.IndexingData
import monix.bio.Task
import pureconfig.ConfigReader
import pureconfig.generic.semiauto.deriveReader

object FilterBySchema {

  final private case class Context(types: Set[Iri])

  implicit private val configReader: ConfigReader[Context] = deriveReader[Context]

  val value: Transformation =
    Transformation.withContext(
      "filterBySchema",
      (context: Context, data: IndexingData) =>
        Task.pure(
          Option.when(context.types.isEmpty || context.types.contains(data.schema.iri))(data)
        )
    )
}
