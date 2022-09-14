package ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.indexing

import cats.implicits._
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.client.{ElasticSearchBulk, ElasticSearchClient, IndexLabel}
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.BNode
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.nxv
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.ExpandedJsonLd
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.decoder.JsonLdDecoder
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.decoder.semiauto.deriveJsonLdDecoder
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Label
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.Elem.SuccessElem
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.{Elem, Pipe, PipeDef}
import fs2.Chunk
import io.circe.{Json, JsonObject}
import monix.bio.Task
import shapeless.Typeable

class IndexToElasticSearch(client: ElasticSearchClient, batchSize: Int, index: IndexLabel) extends Pipe {
  override type In  = JsonObject
  override type Out = Unit
  override def label: Label                 = IndexToElasticSearch.label
  override def inType: Typeable[JsonObject] = Typeable[JsonObject]
  override def outType: Typeable[Unit]      = Typeable[Unit]

  override val chunkSize: Int = batchSize

  override def apply(element: SuccessElem[JsonObject]): Task[Elem[Unit]] =
    if (element.value.isEmpty) client.delete(index, element.id.toString).as(element.void)
    else client.replace(index, element.id.toString, element.value).as(element.void)

  override def applyChunk(elements: Chunk[SuccessElem[JsonObject]]): Task[Chunk[Elem[Unit]]] = {
    val (ops, results) = elements.map { elem =>
      if (elem.value.isEmpty) (ElasticSearchBulk.Delete(index, elem.id.toString), elem.dropped)
      else (ElasticSearchBulk.Index(index, elem.id.toString, Json.fromJsonObject(elem.value)), elem.void)
    }.unzip

    // TODO: add retries with delay
    client.bulk(ops.toVector).as(results)
  }

}

object IndexToElasticSearch {

  val label: Label = Label.unsafe("index-to-elasticsearch")

  def apply(client: ElasticSearchClient, batchSize: Int): IndexToElasticSearchDef =
    new IndexToElasticSearchDef(client, batchSize)

  class IndexToElasticSearchDef(client: ElasticSearchClient, batchSize: Int) extends PipeDef {
    override type PipeType = IndexToElasticSearch
    override type Config   = IndexToElasticSearchConfig
    override def configType: Typeable[IndexToElasticSearchConfig]         = Typeable[IndexToElasticSearchConfig]
    override def configDecoder: JsonLdDecoder[IndexToElasticSearchConfig] = JsonLdDecoder[IndexToElasticSearchConfig]
    override def label: Label                                             = IndexToElasticSearch.label

    override def withConfig(config: IndexToElasticSearchConfig): IndexToElasticSearch =
      new IndexToElasticSearch(client, batchSize, config.index)
  }

  final case class IndexToElasticSearchConfig(index: IndexLabel) {
    def toJsonLd: ExpandedJsonLd = ExpandedJsonLd(
      Seq(
        ExpandedJsonLd.unsafe(
          BNode.random,
          JsonObject(
            (nxv + "index").toString -> Json.arr(Json.obj("@value" -> Json.fromString(index.value)))
          )
        )
      )
    )

  }
  object IndexToElasticSearchConfig {
    implicit val indexToElasticSearchConfigJsonLdDecoder: JsonLdDecoder[IndexToElasticSearchConfig] =
      deriveJsonLdDecoder
  }

}
