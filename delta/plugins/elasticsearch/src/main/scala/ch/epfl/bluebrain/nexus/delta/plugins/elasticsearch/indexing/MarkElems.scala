package ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.indexing

import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.client.ElasticSearchClient.BulkResponse
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.client.ElasticSearchClient.BulkResponse.{MixedOutcomes, Success}
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.Elem.FailedElem
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.{Elem, FailureReason}
import fs2.Chunk
import io.circe.syntax.KeyOps
import io.circe.{Json, JsonObject}

object MarkElems {

  /**
    * Mark and update the elements according to the elasticsearch response
    * @param response
    *   the elasticsearch bulk response
    * @param elements
    *   the chunk of elements
    * @param documentId
    *   how to extract the document id from an element
    */
  def apply[A](response: BulkResponse, elements: Chunk[Elem[A]], documentId: Elem[A] => String): Chunk[Elem[Unit]] =
    response match {
      case Success                           => elements.map(_.void)
      case BulkResponse.MixedOutcomes(items) =>
        elements.map {
          case element: FailedElem => element
          case element             =>
            items.get(documentId(element)) match {
              case None                                    => element.failed(onMissingInResponse(element.id))
              case Some(MixedOutcomes.Outcome.Success)     => element.void
              case Some(MixedOutcomes.Outcome.Error(json)) => element.failed(onIndexingFailure(json))
            }
        }
    }

  private def onMissingInResponse(id: Iri) = FailureReason(
    "MissingInResponse",
    Json.obj("message" := s"$id was not found in Elasticsearch response")
  )

  private def onIndexingFailure(error: JsonObject) =
    FailureReason("IndexingFailure", error)

}
