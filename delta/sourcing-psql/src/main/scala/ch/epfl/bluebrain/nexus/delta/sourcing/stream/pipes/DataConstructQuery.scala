package ch.epfl.bluebrain.nexus.delta.sourcing.stream.pipes

import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.nxv
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.ExpandedJsonLd
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.decoder.JsonLdDecoder
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.decoder.semiauto.deriveJsonLdDecoder
import ch.epfl.bluebrain.nexus.delta.rdf.query.SparqlQuery.SparqlConstructQuery
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Label
import ch.epfl.bluebrain.nexus.delta.sourcing.state.GraphResource
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.Elem.SuccessElem
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.Operation.Pipe
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.pipes.DataConstructQuery.DataConstructQueryConfig
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.{Elem, PipeDef}
import io.circe.{Json, JsonObject}
import monix.bio.Task
import shapeless.Typeable

/**
  * Pipe implementation for UniformScopedState that transforms the resource graph with a provided query.
  */
class DataConstructQuery(config: DataConstructQueryConfig) extends Pipe {
  override type In  = GraphResource
  override type Out = GraphResource
  override def label: Label                          = DataConstructQuery.label
  override def inType: Typeable[GraphResource]  = Typeable[GraphResource]
  override def outType: Typeable[GraphResource] = Typeable[GraphResource]

  override def apply(element: SuccessElem[GraphResource]): Task[Elem[GraphResource]] =
    element.value.graph.transform(config.query) match {
      case Left(err)       => Task.pure(element.failed(err))
      case Right(newGraph) => Task.pure(element.copy(value = element.value.copy(graph = newGraph)))
    }

}

/**
  * Pipe implementation for UniformScopedState that transforms the resource graph with a provided query.
  */
object DataConstructQuery extends PipeDef {
  override type PipeType = DataConstructQuery
  override type Config   = DataConstructQueryConfig
  override def configType: Typeable[Config]                                     = Typeable[DataConstructQueryConfig]
  override def configDecoder: JsonLdDecoder[Config]                             = JsonLdDecoder[DataConstructQueryConfig]
  override def label: Label                                                     = Label.unsafe("dataConstructQuery")
  override def withConfig(config: DataConstructQueryConfig): DataConstructQuery = new DataConstructQuery(config)

  final case class DataConstructQueryConfig(query: SparqlConstructQuery) {
    def toJsonLd: ExpandedJsonLd = ExpandedJsonLd(
      Seq(
        ExpandedJsonLd.unsafe(
          nxv + label.value,
          JsonObject(
            (nxv + "query").toString -> Json.arr(Json.obj("@value" -> Json.fromString(query.value)))
          )
        )
      )
    )
  }
  object DataConstructQueryConfig                                        {
    implicit val dataConstructQueryConfigJsonLdDecoder: JsonLdDecoder[DataConstructQueryConfig] = deriveJsonLdDecoder
  }
}
