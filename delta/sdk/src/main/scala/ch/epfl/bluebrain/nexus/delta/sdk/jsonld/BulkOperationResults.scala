package ch.epfl.bluebrain.nexus.delta.sdk.jsonld

import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.{contexts, nxv}
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.ContextValue
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.encoder.JsonLdEncoder
import io.circe.syntax._
import io.circe.{Encoder, Json, JsonObject}

final case class BulkOperationResults[A](results: Seq[A])

object BulkOperationResults {

  private val context = ContextValue(contexts.bulkOperation, contexts.metadata)

  implicit def encoder[A: Encoder.AsObject]: Encoder.AsObject[BulkOperationResults[A]] =
    Encoder.AsObject.instance { r =>
      JsonObject(nxv.results.prefix -> Json.fromValues(r.results.map(_.asJson)))
    }

  def searchResultsJsonLdEncoder[A: Encoder.AsObject](
      additionalContext: ContextValue
  ): JsonLdEncoder[BulkOperationResults[A]] =
    JsonLdEncoder.computeFromCirce(context.merge(additionalContext))
}
