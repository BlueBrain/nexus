package ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.indexing

import cats.effect.IO
import ch.epfl.bluebrain.nexus.delta.kernel.effect.migration._
import ch.epfl.bluebrain.nexus.delta.kernel.kamon.KamonMetricComponent
import ch.epfl.bluebrain.nexus.delta.kernel.syntax.kamonSyntax
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.ElasticSearchViews
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.client.ElasticSearchClient.BulkResponse.{MixedOutcomes, Success}
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.client.ElasticSearchClient.{BulkResponse, Refresh}
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.client.{ElasticSearchBulk, ElasticSearchClient, IndexLabel}
import ch.epfl.bluebrain.nexus.delta.sdk.implicits._
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.Elem
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.Elem.FailedElem
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.Operation.Sink
import fs2.Chunk
import io.circe.{Json, JsonObject}
import shapeless.Typeable

import scala.concurrent.duration.FiniteDuration
import scala.util.control.NoStackTrace

/**
  * Sink that pushes json documents into an Elasticsearch index
  * @param client
  *   the ES client
  * @param chunkSize
  *   the maximum number of documents to be pushed at once
  * @param maxWindow
  *   the maximum window before a document is pushed
  * @param index
  *   the index to push into
  * @param documentId
  *   a function that maps an elem to a documentId
  * @param refresh
  *   the value for the `refresh` Elasticsearch parameter
  */
final class ElasticSearchSink private (
    client: ElasticSearchClient,
    override val chunkSize: Int,
    override val maxWindow: FiniteDuration,
    index: IndexLabel,
    documentId: Elem[Json] => String,
    refresh: Refresh
) extends Sink {
  override type In = Json

  override def inType: Typeable[Json] = Typeable[Json]

  implicit private val kamonComponent: KamonMetricComponent =
    KamonMetricComponent(ElasticSearchViews.entityType.value)

  override def apply(elements: Chunk[Elem[Json]]): IO[Chunk[Elem[Unit]]] = {
    val bulk = elements.foldLeft(Vector.empty[ElasticSearchBulk]) {
      case (acc, successElem @ Elem.SuccessElem(_, _, _, _, _, json, _)) =>
        if (json.isEmpty()) {
          acc :+ ElasticSearchBulk.Delete(index, documentId(successElem))
        } else
          acc :+ ElasticSearchBulk.Index(index, documentId(successElem), json)
      case (acc, droppedElem: Elem.DroppedElem)                          =>
        acc :+ ElasticSearchBulk.Delete(index, documentId(droppedElem))
      case (acc, _: Elem.FailedElem)                                     => acc
    }

    if (bulk.nonEmpty) {
      client
        .bulk(bulk, refresh)
        .toCatsIO
        .map(ElasticSearchSink.markElems(_, elements, documentId))
    } else {
      IO.pure(elements.map(_.void))
    }
  }.span("elasticSearchSink")
}

object ElasticSearchSink {

  /**
    * @return
    *   a function that maps an elem to a documentId based on the project (if available), the elem id, and the revision
    */
  private val eventDocumentId: Elem[_] => String = elem =>
    elem.project match {
      case Some(project) => s"$project/${elem.id}:${elem.rev}"
      case None          => s"${elem.id}/${elem.rev}"
    }

  /**
    * Mark and update the elements according to the elasticsearch response
    * @param response
    *   the elasticsearch bulk response
    * @param elements
    *   the chunk of elements
    * @param documentId
    *   how to extract the document id from an element
    */
  def markElems[A](response: BulkResponse, elements: Chunk[Elem[A]], documentId: Elem[A] => String): Chunk[Elem[Unit]] =
    response match {
      case Success                           => elements.map(_.void)
      case BulkResponse.MixedOutcomes(items) =>
        elements.map {
          case element: FailedElem => element
          case element             =>
            items.get(documentId(element)) match {
              case None                                    =>
                element.failed(
                  BulkUpdateException(
                    JsonObject(
                      "reason" -> Json.fromString(s"${element.id} was not found in Elasticsearch response")
                    )
                  )
                )
              case Some(MixedOutcomes.Outcome.Success)     => element.void
              case Some(MixedOutcomes.Outcome.Error(json)) => element.failed(BulkUpdateException(json))
            }
        }
    }

  /**
    * @param client
    *   the ES client
    * @param chunkSize
    *   the maximum number of documents to be pushed at once
    * @param maxWindow
    *   the maximum window before a document is pushed
    * @param index
    *   the index to push into
    * @param refresh
    *   the value for the `refresh` Elasticsearch parameter
    * @return
    *   an ElasticSearchSink for events
    */
  def events(
      client: ElasticSearchClient,
      chunkSize: Int,
      maxWindow: FiniteDuration,
      index: IndexLabel,
      refresh: Refresh
  ): ElasticSearchSink =
    new ElasticSearchSink(
      client,
      chunkSize,
      maxWindow,
      index,
      eventDocumentId,
      refresh
    )

  /**
    * @param client
    *   the ES client
    * @param chunkSize
    *   the maximum number of documents to be pushed at once
    * @param maxWindow
    *   the maximum window before a document is pushed
    * @param index
    *   the index to push into
    * @param refresh
    *   the value for the `refresh` Elasticsearch parameter
    * @return
    *   an ElasticSearchSink for states
    */
  def states(
      client: ElasticSearchClient,
      chunkSize: Int,
      maxWindow: FiniteDuration,
      index: IndexLabel,
      refresh: Refresh
  ): ElasticSearchSink =
    new ElasticSearchSink(
      client,
      chunkSize,
      maxWindow,
      index,
      elem => elem.id.toString,
      refresh
    )

  final case class BulkUpdateException(json: JsonObject)
      extends Exception("Error updating elasticsearch: " + Json.fromJsonObject(json).noSpaces)
      with NoStackTrace
}
