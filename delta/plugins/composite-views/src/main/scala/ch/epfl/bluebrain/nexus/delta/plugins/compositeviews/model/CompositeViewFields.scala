package ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.model

import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.model.CompositeView.RebuildStrategy
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.JsonLdContext.keywords
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.decoder.JsonLdDecoder
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.decoder.JsonLdDecoderError.ParsingFailure
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.decoder.semiauto.deriveJsonLdDecoder
import ch.epfl.bluebrain.nexus.delta.sdk.model.{BaseUri, NonEmptySet}
import io.circe.syntax.EncoderOps
import io.circe.{Encoder, Json}

import scala.annotation.nowarn
import scala.concurrent.duration.FiniteDuration

/**
  * Necessary values to create/update a composite view.
  *
  * @param sources
  *   list of sources
  * @param projections
  *   list of projections
  * @param rebuildStrategy
  *   retry strategy
  */
final case class CompositeViewFields(
    sources: NonEmptySet[CompositeViewSourceFields],
    projections: NonEmptySet[CompositeViewProjectionFields],
    rebuildStrategy: Option[RebuildStrategy]
) {
  def toJson(iri: Iri)(implicit base: BaseUri): Json =
    this.asJsonObject.add(keywords.id, iri.asJson).asJson.deepDropNullValues
}

object CompositeViewFields {

  @nowarn("cat=unused")
  implicit final def compositeViewFieldsEncoder(implicit base: BaseUri): Encoder.AsObject[CompositeViewFields] = {
    import io.circe.generic.extras.Configuration
    import io.circe.generic.extras.semiauto._
    implicit val config: Configuration = Configuration.default
    deriveConfiguredEncoder[CompositeViewFields]
  }

  @nowarn("cat=unused")
  final def jsonLdDecoder(minIntervalRebuild: FiniteDuration): JsonLdDecoder[CompositeViewFields] = {
    implicit val rebuildStrategyDecoder: JsonLdDecoder[RebuildStrategy] = {
      implicit val scopedFiniteDurationDecoder: JsonLdDecoder[FiniteDuration] =
        JsonLdDecoder.finiteDurationJsonLdDecoder.andThen { case (cursor, duration) =>
          Option
            .when(duration.gteq(minIntervalRebuild))(duration)
            .toRight(
              ParsingFailure(
                "Duration",
                duration.toString,
                cursor.history,
                s"duration must be greater than $minIntervalRebuild"
              )
            )
        }
      deriveJsonLdDecoder[RebuildStrategy]
    }
    deriveJsonLdDecoder[CompositeViewFields]
  }
}
