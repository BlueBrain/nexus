package ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.model

import cats.data.NonEmptyMap
//import cats.syntax.all._
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.model.CompositeView.RebuildStrategy
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.sdk.implicits._
import ch.epfl.bluebrain.nexus.delta.sdk.views.IndexingRev
import io.circe.generic.extras.Configuration
import io.circe.generic.extras.semiauto.{deriveConfiguredCodec, deriveConfiguredDecoder, deriveConfiguredEncoder}
import io.circe.{Codec, Decoder, Encoder}

import scala.annotation.nowarn
import scala.concurrent.duration.{Duration, FiniteDuration}

/**
  * The configuration for a composite view.
  *
  * @param sources
  *   the collection of sources for the view
  * @param projections
  *   the collection of projections for the view
  * @param rebuildStrategy
  *   the rebuild strategy of the view
  */
final case class CompositeViewValue(
    name: Option[String],
    description: Option[String],
    sourceIndexingRev: IndexingRev,
    sources: NonEmptyMap[Iri, CompositeViewSource],
    projections: NonEmptyMap[Iri, CompositeViewProjection],
    rebuildStrategy: Option[RebuildStrategy]
)

object CompositeViewValue {

  @SuppressWarnings(Array("TryGet"))
  @nowarn("cat=unused")
  def databaseCodec()(implicit configuration: Configuration): Codec[CompositeViewValue] = {
    import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.Database._
    implicit val finiteDurationEncoder: Encoder[FiniteDuration] = Encoder.encodeString.contramap(_.toString())
    implicit val finiteDurationDecoder: Decoder[FiniteDuration] = Decoder.decodeString.emap { s =>
      Duration(s) match {
        case finite: FiniteDuration => Right(finite)
        case _                      => Left(s"$s is not a valid FinalDuration")
      }
    }

    implicit val rebuildStrategyCodec: Codec.AsObject[RebuildStrategy] =
      deriveConfiguredCodec[RebuildStrategy]

    implicit val compositeViewSourceTypeCodec: Codec.AsObject[SourceType] =
      deriveConfiguredCodec[SourceType]

    implicit val compositeViewProjectionTypeCodec: Codec.AsObject[ProjectionType] =
      deriveConfiguredCodec[ProjectionType]

    implicit val compositeViewProjectionCodec: Codec.AsObject[CompositeViewProjection] =
      deriveConfiguredCodec[CompositeViewProjection]

    implicit val compositeViewSourceCodec: Codec.AsObject[CompositeViewSource] =
      deriveConfiguredCodec[CompositeViewSource]

    // No need to repeat the key (as it is included in the value) in the json result so we just encode the value
    import ch.epfl.bluebrain.nexus.delta.sdk.circe.nonEmptyMap._

    // Decoding and extracting the id/key back from the value
    implicit val nonEmptyMapProjectionDecoder: Decoder[NonEmptyMap[Iri, CompositeViewProjection]] =
      dropKeyDecoder(_.id)

    implicit val nonEmptyMapSourceDecoder: Decoder[NonEmptyMap[Iri, CompositeViewSource]] =
      dropKeyDecoder(_.id)

    Codec.from(
      deriveConfiguredDecoder[CompositeViewValue],
      deriveConfiguredEncoder[CompositeViewValue].mapJson(_.deepDropNullValues)
    )
  }

}
