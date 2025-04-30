package ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.client

import cats.syntax.all.*
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.client.BulkResponse.MixedOutcomes.Outcome
import io.circe.{Decoder, DecodingFailure, JsonObject}

/**
  * Elasticsearch response after a bulk request
  */
sealed trait BulkResponse extends Product with Serializable

object BulkResponse {

  /**
    * No indexing error were returned by elasticsearch
    */
  final case object Success extends BulkResponse

  /**
    * At least one indexing error has been returned by elasticsearch
    * @param items
    *   the different outcomes indexed by document id (So we only keep the last outcome if a document id is repeated in
    *   a query but this is ok in the Delta context)
    */
  final case class MixedOutcomes(items: Map[String, Outcome]) extends BulkResponse

  object MixedOutcomes {

    /**
      * Outcome returned by Elasticsearch for a single document
      */
    sealed trait Outcome extends Product with Serializable {
      def id: String
    }

    object Outcome {

      /**
        * The document has been properly indexed
        */
      final case class Success(id: String) extends Outcome

      /**
        * The document could not be indexed
        * @param json
        *   the reason returned by the Elasticsearch API
        */
      final case class Error(id: String, json: JsonObject) extends Outcome

      implicit private[client] val outcomeDecoder: Decoder[Outcome] = {
        val operations = List("index", "delete", "update", "create")
        Decoder.instance { hc =>
          for {
            sub   <-
              operations
                .collectFirstSome(hc.downField(_).success)
                .toRight(DecodingFailure(s"Operation type was not one of ${operations.mkString(", ")}.", hc.history))
            id    <- sub.get[String]("_id")
            error <- sub.get[Option[JsonObject]]("error")
          } yield {
            error.map { e => Error(id, e) }.getOrElse(Success(id))
          }
        }
      }
    }
  }

  implicit private[client] val bulkResponseDecoder: Decoder[BulkResponse] =
    Decoder.instance { hc =>
      hc.get[Boolean]("errors").flatMap { hasErrors =>
        if (hasErrors)
          hc.get[Vector[Outcome]]("items").map { outcomes =>
            MixedOutcomes(outcomes.map { o => o.id -> o }.toMap)
          }
        else
          Right(Success)
      }
    }
}
