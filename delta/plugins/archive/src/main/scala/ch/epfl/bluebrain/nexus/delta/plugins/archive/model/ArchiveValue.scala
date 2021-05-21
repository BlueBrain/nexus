package ch.epfl.bluebrain.nexus.delta.plugins.archive.model

import cats.implicits._
import ch.epfl.bluebrain.nexus.delta.plugins.archive.model.ArchiveRejection.InvalidResourceCollection
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.AbsolutePath
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
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
    * A safe constructor for ArchiveValue that checks for path duplication and validate that paths respect tar implementation in alpakka
    * https://github.com/akka/alpakka/blob/f2971ca8a4a71b541cddbd5bf35af3a2a56efe71/file/src/main/scala/akka/stream/alpakka/file/model.scala#L115
    *
    * @param resources the collection of referenced resources
    */
  final def apply(resources: NonEmptySet[ArchiveReference]): Either[InvalidResourceCollection, ArchiveValue] = {

    def validateDefaultFileName(reference: ArchiveReference) = reference match {
      case r: ArchiveReference.ResourceReference if r.path.isEmpty =>
        r.defaultFileName.length < 100
      case _                                                       => true
    }

    def validatePath(path: AbsolutePath) =
      path.value.getFileName.toString.length < 100 && path.value.getParent.toString.length < 155

    val (_, duplicates, invalids, longIds) = resources.value.foldLeft(
      (Set.empty[AbsolutePath], Set.empty[AbsolutePath], Set.empty[AbsolutePath], Set.empty[Iri])
    ) { case ((visitedPaths, duplicates, invalids, longIds), reference) =>
      (
        visitedPaths ++ reference.path,
        duplicates ++ reference.path.filter(visitedPaths.contains),
        invalids ++ reference.path.filterNot(validatePath),
        longIds ++ Option.unless(validateDefaultFileName(reference))(reference.ref.original)
      )
    }

    if (duplicates.nonEmpty || invalids.nonEmpty || longIds.nonEmpty)
      Left(InvalidResourceCollection(duplicates, invalids, longIds))
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
