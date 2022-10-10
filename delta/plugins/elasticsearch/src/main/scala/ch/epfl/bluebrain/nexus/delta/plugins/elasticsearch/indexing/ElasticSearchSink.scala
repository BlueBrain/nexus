package ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.indexing

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

final class ElasticSearchSink(
    client: ElasticSearchClient,
    override val chunkSize: Int,
    override val maxWindow: FiniteDuration,
    index: IndexLabel
) extends Sink {
  override type In = Json

  override def inType: Typeable[Json] = Typeable[Json]

  override def apply(elements: Chunk[Elem[Json]]): Task[Chunk[Elem[Unit]]] = {
    val bulk = elements.foldLeft(List.empty[ElasticSearchBulk]) {
      case (acc, Elem.SuccessElem(_, id, _, _, json)) =>
        if (json.isEmpty()) {
          ElasticSearchBulk.Delete(index, id) :: acc
        } else
          ElasticSearchBulk.Index(index, id, json) :: acc
      case (acc, Elem.DroppedElem(_, id, _, _))       =>
        ElasticSearchBulk.Delete(index, id) :: acc
      case (acc, _: Elem.FailedElem)                  => acc
    }

    if (bulk.nonEmpty) {
      client
        .bulk(bulk)
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

}
