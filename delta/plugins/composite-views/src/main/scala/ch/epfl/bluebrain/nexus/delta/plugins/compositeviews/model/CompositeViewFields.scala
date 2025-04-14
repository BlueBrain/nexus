package ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.model

import cats.data.NonEmptyList
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.model.CompositeView.RebuildStrategy
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.JsonLdContext.keywords
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.decoder.{Configuration, JsonLdDecoder}
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.decoder.JsonLdDecoderError.ParsingFailure
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.decoder.configuration.semiauto.deriveConfigJsonLdDecoder
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.decoder.semiauto.deriveDefaultJsonLdDecoder
import ch.epfl.bluebrain.nexus.delta.rdf.syntax.iriStringContextSyntax
import ch.epfl.bluebrain.nexus.delta.sdk.model.BaseUri
import io.circe.syntax.EncoderOps
import io.circe.{Encoder, Json}

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
    name: Option[String],
    description: Option[String],
    sources: NonEmptyList[CompositeViewSourceFields],
    projections: NonEmptyList[CompositeViewProjectionFields],
    rebuildStrategy: Option[RebuildStrategy]
) {
  def toJson(iri: Iri)(implicit base: BaseUri): Json =
    this.asJsonObject.add(keywords.id, iri.asJson).asJson.deepDropNullValues
}

object CompositeViewFields {

  /**
    * Construct a [[CompositeViewFields]] without name and description
    */
  def apply(
      sources: NonEmptyList[CompositeViewSourceFields],
      projections: NonEmptyList[CompositeViewProjectionFields],
      rebuildStrategy: Option[RebuildStrategy]
  ): CompositeViewFields =
    CompositeViewFields(None, None, sources, projections, rebuildStrategy)

  implicit final def compositeViewFieldsEncoder(implicit base: BaseUri): Encoder.AsObject[CompositeViewFields] = {
    import io.circe.generic.extras.Configuration
    import io.circe.generic.extras.semiauto.*
    implicit val config: Configuration = Configuration.default
    deriveConfiguredEncoder[CompositeViewFields]
  }

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
      deriveDefaultJsonLdDecoder[RebuildStrategy]
    }

    val ctx             = Configuration.default.context
      .addAliasIdType("description", iri"http://schema.org/description")
      .addAliasIdType("name", iri"http://schema.org/name")
    implicit val config = Configuration.default.copy(context = ctx)

    deriveConfigJsonLdDecoder[CompositeViewFields]
  }
}
