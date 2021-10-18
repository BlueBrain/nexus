package ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.transformation

import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.sdk.views.model.IndexingData
import monix.bio.Task
import pureconfig.ConfigReader
import pureconfig.generic.semiauto.deriveReader

object FilterByType {

  final private case class Context(types: Set[Iri])

  implicit private val contextReader: ConfigReader[Context] = deriveReader[Context]

  val value: Transformation =
    Transformation.withContext(
      "filterByType",
      (context: Context, data: IndexingData) =>
        Task.pure(
          Option.when(context.types.isEmpty || context.types.exists(data.types.contains))(data)
        )
    )
}
