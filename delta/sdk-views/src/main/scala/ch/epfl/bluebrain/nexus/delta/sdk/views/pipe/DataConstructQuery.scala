package ch.epfl.bluebrain.nexus.delta.sdk.views.pipe
import ch.epfl.bluebrain.nexus.delta.rdf.query.SparqlQuery.SparqlConstructQuery
import ch.epfl.bluebrain.nexus.delta.sdk.views.model.IndexingData
import io.circe.Decoder
import io.circe.generic.semiauto.deriveDecoder
import monix.bio.Task

/**
  * Transforms a resource according to a construct query
  */
object DataConstructQuery {

  final private case class Context(query: SparqlConstructQuery)

  val value: Pipe = {
    implicit val contextReader: Decoder[Context] = deriveDecoder[Context]
    Pipe.withContext(
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

}
