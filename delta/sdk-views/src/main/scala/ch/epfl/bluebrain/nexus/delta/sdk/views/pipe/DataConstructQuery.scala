package ch.epfl.bluebrain.nexus.delta.sdk.views.pipe
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.nxv
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.ExpandedJsonLd
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.decoder.JsonLdDecoder
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.decoder.semiauto.deriveJsonLdDecoder
import ch.epfl.bluebrain.nexus.delta.rdf.query.SparqlQuery.SparqlConstructQuery
import ch.epfl.bluebrain.nexus.delta.sdk.views.model.ViewData.IndexingData
import monix.bio.Task

/**
  * Transforms a resource according to a construct query
  */
object DataConstructQuery {

  final private case class Config(query: SparqlConstructQuery)

  val name: String = "dataConstructQuery"

  val pipe: Pipe = {
    implicit val configDecoder: JsonLdDecoder[Config] = deriveJsonLdDecoder[Config]
    Pipe.withConfig(
      name,
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

  private val queryKey = nxv + "query"

  def apply(query: SparqlConstructQuery): PipeDef =
    PipeDef.withConfig(
      name,
      ExpandedJsonLd.empty.copy(rootId = nxv + name).add(queryKey, query.value)
    )

}
