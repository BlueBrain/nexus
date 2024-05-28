package ch.epfl.bluebrain.nexus.ship.resources

import cats.effect.{Clock, IO}
import ch.epfl.bluebrain.nexus.delta.plugins.storage.FileSelf
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.Files.{definition, FilesLog}
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model.FileRejection.{FileNotFound, RevisionNotFound, TagNotFound}
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model.{File, FileId, FileState}
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.sdk.model.BaseUri
import ch.epfl.bluebrain.nexus.delta.sdk.model.IdSegmentRef.{Latest, Revision, Tag}
import ch.epfl.bluebrain.nexus.delta.sdk.projects.FetchContext
import ch.epfl.bluebrain.nexus.delta.sourcing.{ScopedEventLog, Transactors}
import ch.epfl.bluebrain.nexus.ship.ProjectMapper
import ch.epfl.bluebrain.nexus.ship.config.InputConfig
import ch.epfl.bluebrain.nexus.ship.resources.SourcePatcher.removeEmptyIds
import io.circe.Json
import io.circe.optics.JsonPath.root

final class SourcePatcher(distributionPatcher: DistributionPatcher) {

  def apply(json: Json): IO[Json] = distributionPatcher.singleOrArray(json).map(removeEmptyIds)

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
      targetBase: BaseUri,
      fetchContext: FetchContext,
      clock: Clock[IO],
      xas: Transactors,
      config: InputConfig
  ): SourcePatcher = {
    val log: FilesLog = ScopedEventLog(definition(clock), config.eventLog, xas)

    def fetchState(id: FileId, iri: Iri): IO[FileState] = {
      val notFound = FileNotFound(iri, id.project)
      id.id match {
        case Latest(_)        => log.stateOr(id.project, iri, notFound)
        case Revision(_, rev) => log.stateOr(id.project, iri, rev, notFound, RevisionNotFound)
        case Tag(_, tag)      => log.stateOr(id.project, iri, tag, notFound, TagNotFound(tag))
      }
    }

    val fetchFileResource: FileId => IO[File] = id => {
      for {
        (iri, _) <- id.expandIri(fetchContext.onRead)
        state    <- fetchState(id, iri)
      } yield state.toResource.value
    }

    new SourcePatcher(new DistributionPatcher(fileSelfParser, projectMapper, targetBase, fetchFileResource))
  }
}
