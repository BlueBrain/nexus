package ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.model

import cats.syntax.all._
import ch.epfl.bluebrain.nexus.delta.kernel.Secret
import ch.epfl.bluebrain.nexus.delta.kernel.utils.UUIDF
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.model.CompositeView.RebuildStrategy
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.model.CompositeViewSource.AccessToken
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.sdk.crypto.Crypto
import ch.epfl.bluebrain.nexus.delta.sdk.implicits._
import ch.epfl.bluebrain.nexus.delta.sdk.model.NonEmptySet
import ch.epfl.bluebrain.nexus.delta.sdk.projects.model.ProjectBase
import io.circe.generic.extras.Configuration
import io.circe.{Codec, Decoder, Encoder}
import io.circe.generic.extras.semiauto.{deriveConfiguredCodec, deriveConfiguredDecoder, deriveConfiguredEncoder}
import monix.bio.UIO

import java.util.UUID
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
    sources: NonEmptySet[CompositeViewSource],
    projections: NonEmptySet[CompositeViewProjection],
    rebuildStrategy: Option[RebuildStrategy]
)

object CompositeViewValue {

  /**
    * Create a [[CompositeViewValue]] from [[CompositeViewFields]] and previous Ids/UUIDs.
    */
  def apply(
      fields: CompositeViewFields,
      currentSources: Map[Iri, UUID],
      currentProjections: Map[Iri, UUID],
      projectBase: ProjectBase
  )(implicit uuidF: UUIDF): UIO[CompositeViewValue] = {
    val sources                                         = UIO.traverse(fields.sources.value) { source =>
      val currentUuid = source.id.flatMap(currentSources.get)
      for {
        uuid       <- currentUuid.fold(uuidF())(UIO.delay(_))
        generatedId = projectBase.iri / uuid.toString
      } yield source.toSource(uuid, generatedId)
    }
    val projections: UIO[List[CompositeViewProjection]] = UIO.traverse(fields.projections.value) { projection =>
      val currentUuid = projection.id.flatMap(currentProjections.get)
      for {
        uuid       <- currentUuid.fold(uuidF())(UIO.delay(_))
        generatedId = projectBase.iri / uuid.toString
      } yield projection.toProjection(uuid, generatedId)
    }
    for {
      s <- sources
      p <- projections
    } yield CompositeViewValue(NonEmptySet(s.toSet), NonEmptySet(p.toSet), fields.rebuildStrategy)
  }

  @SuppressWarnings(Array("TryGet"))
  @nowarn("cat=unused")
  def databaseCodec(crypto: Crypto)(implicit configuration: Configuration): Codec[CompositeViewValue] = {
    import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.Database._
    implicit val stringSecretEncryptEncoder: Encoder[Secret[String]] = Encoder.encodeString.contramap {
      case Secret(value) => crypto.encrypt(value).get
    }
    implicit val stringSecretDecryptDecoder: Decoder[Secret[String]] =
      Decoder.decodeString.emap(str => crypto.decrypt(str).map(Secret(_)).toEither.leftMap(_.getMessage))

    implicit val accessTokenCodec: Codec.AsObject[AccessToken] = deriveConfiguredCodec[AccessToken]

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

    Codec.from(
      deriveConfiguredDecoder[CompositeViewValue],
      deriveConfiguredEncoder[CompositeViewValue].mapJson(_.deepDropNullValues)
    )
  }

}
