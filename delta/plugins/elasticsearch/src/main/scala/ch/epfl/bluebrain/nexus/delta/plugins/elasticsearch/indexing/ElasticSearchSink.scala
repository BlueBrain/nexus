package ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.indexing

import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.client.ElasticSearchClient.Refresh
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.client.{ElasticSearchBulk, ElasticSearchClient, IndexLabel}
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.indexing.ElasticSearchSink.logger
import ch.epfl.bluebrain.nexus.delta.sdk.implicits._
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.Elem
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.Operation.Sink
import com.typesafe.scalalogging.Logger
import fs2.Chunk
import io.circe.Json
import monix.bio.{Task, UIO}
import shapeless.Typeable

import scala.concurrent.duration.FiniteDuration

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

  override def apply(elements: Chunk[Elem[Json]]): Task[Chunk[Elem[Unit]]] = {
    val bulk = elements.foldLeft(List.empty[ElasticSearchBulk]) {
      case (acc, successElem @ Elem.SuccessElem(_, _, _, _, _, json, _)) =>
        if (json.isEmpty()) {
          ElasticSearchBulk.Delete(index, documentId(successElem)) :: acc
        } else
          ElasticSearchBulk.Index(index, documentId(successElem), json) :: acc
      case (acc, droppedElem: Elem.DroppedElem)                          =>
        ElasticSearchBulk.Delete(index, documentId(droppedElem)) :: acc
      case (acc, _: Elem.FailedElem)                                     => acc
    }

    if (bulk.nonEmpty) {
      client
        .bulk(bulk, refresh)
        .redeemWith(
          err =>
            UIO
              .delay(logger.error(s"Indexing in elasticsearch index ${index.value} failed", err))
              .as(elements.map { _.failed(err) }),
          _ => Task.pure(elements.map(_.void))
        )
    } else {
      Task.pure(elements.map(_.void))
    }
  }
}

object ElasticSearchSink {

  private val logger: Logger = Logger[ElasticSearchSink]

  /**
    * @return
    *   a function that maps an elem to a documentId based on the project (if available), the elem id, and the revision
    */
  private val eventDocumentId: Elem[_] => String = elem =>
    elem.project match {
      case Some(project) => s"$project/${elem.id}:${elem.revision}"
      case None          => s"${elem.id}/${elem.revision}"
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

}
