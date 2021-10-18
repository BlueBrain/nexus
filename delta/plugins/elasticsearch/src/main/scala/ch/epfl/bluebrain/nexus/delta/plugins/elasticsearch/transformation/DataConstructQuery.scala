package ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.transformation
import ch.epfl.bluebrain.nexus.delta.rdf.query.SparqlQuery.SparqlConstructQuery
import ch.epfl.bluebrain.nexus.delta.sdk.views.model.IndexingData
import monix.bio.Task
import pureconfig.ConfigReader
import pureconfig.generic.semiauto._

object DataConstructQuery {

  final private case class Context(query: SparqlConstructQuery)

  implicit private val contextReader: ConfigReader[Context] = deriveReader[Context]

  val value: Transformation =
    Transformation.withContext(
      "dataConstructQuery",
      (context: Context, data: IndexingData) =>
        Task
          .fromEither(
            data.graph.transform(context.query)
          )
          .map { newGraph =>
            Some(
              data.copy(
                types = newGraph.rootTypes,
                graph = newGraph
              )
            )
          }
    )

}
