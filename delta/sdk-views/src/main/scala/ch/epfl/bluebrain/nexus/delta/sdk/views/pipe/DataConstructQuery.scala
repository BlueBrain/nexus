package ch.epfl.bluebrain.nexus.delta.sdk.views.pipe
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.decoder.JsonLdDecoder
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.decoder.semiauto.deriveJsonLdDecoder
import ch.epfl.bluebrain.nexus.delta.rdf.query.SparqlQuery.SparqlConstructQuery
import ch.epfl.bluebrain.nexus.delta.sdk.views.model.IndexingData
import monix.bio.Task

/**
  * Transforms a resource according to a construct query
  */
object DataConstructQuery {

  final private case class Config(query: SparqlConstructQuery)

  val value: Pipe = {
    implicit val configDecoder: JsonLdDecoder[Config] = deriveJsonLdDecoder[Config]
    Pipe.withConfig(
      "dataConstructQuery",
      (config: Config, data: IndexingData) =>
        Task
          .fromEither(
            data.graph.transform(config.query)
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
