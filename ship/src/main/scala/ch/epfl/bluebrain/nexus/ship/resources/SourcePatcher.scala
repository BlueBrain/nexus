package ch.epfl.bluebrain.nexus.ship.resources

import cats.effect.{Clock, IO}
import ch.epfl.bluebrain.nexus.delta.plugins.storage.FileSelf
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.Files.{definition, FilesLog}
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model.FileAttributes
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model.FileRejection.{FileNotFound, RevisionNotFound, TagNotFound}
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.sdk.model.BaseUri
import ch.epfl.bluebrain.nexus.delta.sourcing.model.ResourceRef._
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{ProjectRef, ResourceRef}
import ch.epfl.bluebrain.nexus.delta.sourcing.{ScopedEventLog, Transactors}
import ch.epfl.bluebrain.nexus.ship.config.InputConfig
import ch.epfl.bluebrain.nexus.ship.resources.SourcePatcher.{patchIris, removeEmptyIds}
import ch.epfl.bluebrain.nexus.ship.{IriPatcher, ProjectMapper}
import io.circe.optics.JsonPath.root
import io.circe.syntax.EncoderOps
import io.circe.{Json, JsonObject}

final class SourcePatcher(distributionPatcher: DistributionPatcher, iriPatcher: IriPatcher) {

  def apply(json: Json): IO[Json] =
    distributionPatcher
      .patchAll(json)
      .map(removeEmptyIds)
      .map(patchIris(_, iriPatcher))

}

object SourcePatcher {

  private val emptyString = Json.fromString("")

  // Remove empty ids from sources as they are not accepted anymore in payloads
  // Resources will be keep their ids as we explicitly pass them along the source payload
  val removeEmptyIds: Json => Json = root.obj.modify {
    case obj if obj("@id").contains(emptyString) => obj.remove("@id")
    case obj                                     => obj
  }

  def apply(
      fileSelfParser: FileSelf,
      projectMapper: ProjectMapper,
      iriPatcher: IriPatcher,
      targetBase: BaseUri,
      clock: Clock[IO],
      xas: Transactors,
      config: InputConfig
  ): SourcePatcher = {
    val log: FilesLog = ScopedEventLog(definition(clock), config.eventLog, xas)

    def fetchFileAttributes(project: ProjectRef, resourceRef: ResourceRef): IO[FileAttributes] = {
      val notFound = FileNotFound(resourceRef.iri, project)
      resourceRef match {
        case Latest(iri)           => log.stateOr(project, iri, notFound)
        case Revision(_, iri, rev) => log.stateOr(project, iri, rev, notFound, RevisionNotFound)
        case Tag(_, iri, tag)      => log.stateOr(project, iri, tag, notFound, TagNotFound(tag))
      }
    }.map(_.attributes)

    val distributionPatcher =
      new DistributionPatcher(fileSelfParser, projectMapper, iriPatcher, targetBase, fetchFileAttributes)
    new SourcePatcher(distributionPatcher, iriPatcher)
  }

  def patchIris(original: Json, iriPatcher: IriPatcher): Json = if (iriPatcher.enabled) {

    def literal(value: Json): Json = value.as[Iri].map(iriPatcher.apply(_).asJson).getOrElse(value)

    def innerArray(jsonArray: Vector[Json]): Json = {
      val patched = jsonArray.map { entry =>
        entry.arrayOrObject(
          literal(entry),
          innerArray,
          innerObject
        )
      }
      Json.fromValues(patched)
    }

    def innerObject(jsonObject: JsonObject): Json = {
      val result = jsonObject.toVector.map { case (key, value) =>
        val patched = value.arrayOrObject(
          literal(value),
          innerArray,
          innerObject
        )
        key -> patched
      }
      Json.fromFields(result)
    }

    original.arrayOrObject(
      literal(original),
      innerArray,
      innerObject
    )

  } else original
}
