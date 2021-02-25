package ch.epfl.bluebrain.nexus.delta.plugins.archive.model

import cats.implicits.toBifunctorOps
import ch.epfl.bluebrain.nexus.delta.plugins.archive.model.ArchiveRejection.DuplicateResourcePath
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.decoder.JsonLdDecoderError.ParsingFailure
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.decoder.configuration.semiauto.deriveConfigJsonLdDecoder
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.decoder.{Configuration, JsonLdDecoder}
import ch.epfl.bluebrain.nexus.delta.sdk.model.NonEmptySet

import scala.annotation.nowarn

/**
  * An archive value.
  *
  * @param resources the collection of referenced resources
  */
final case class ArchiveValue private (resources: NonEmptySet[ArchiveReference])

object ArchiveValue {

  /**
    * A safe constructor for ArchiveValue that checks for path duplication.
    *
    * @param resources the collection of referenced resources
    */
  final def apply(resources: NonEmptySet[ArchiveReference]): Either[DuplicateResourcePath, ArchiveValue] = {
    val duplicates = resources.value
      .groupBy(_.path)
      .collect {
        case (Some(path), refs) if refs.size > 1 => path
      }
      .toSet
    if (duplicates.nonEmpty) Left(DuplicateResourcePath(duplicates))
    else Right(unsafe(resources))
  }

  /**
    * An unsafe constructor for ArchiveValue that doesn't check for path duplication.
    *
    * @param resources the collection of referenced resources
    */
  final def unsafe(resources: NonEmptySet[ArchiveReference]): ArchiveValue =
    new ArchiveValue(resources)

  final private case class ArchiveValueInput(resources: NonEmptySet[ArchiveReference])

  @nowarn("cat=unused")
  implicit final val archiveValueJsonLdDecoder: JsonLdDecoder[ArchiveValue] = {
    implicit val cfg: Configuration = Configuration.default
    deriveConfigJsonLdDecoder[ArchiveValueInput].flatMap { input =>
      apply(input.resources).leftMap(err => ParsingFailure(err.reason))
    }
  }
}
