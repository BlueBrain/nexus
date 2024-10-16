package ch.epfl.bluebrain.nexus.delta.plugins.storage.files

import cats.effect.IO
import ch.epfl.bluebrain.nexus.delta.kernel.Logger
import ch.epfl.bluebrain.nexus.delta.kernel.cache.LocalCache
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model.FileRejection._
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model.FileState
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.Storages
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.StoragesConfig.RemoteDiskStorageConfig
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.{Storage, StorageType}
import ch.epfl.bluebrain.nexus.delta.sdk.model.IdSegmentRef
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{ProjectRef, ResourceRef, SuccessElemStream}
import ch.epfl.bluebrain.nexus.delta.sourcing.offset.Offset
import ch.epfl.bluebrain.nexus.delta.sourcing.stream._
import fs2.Stream
import retry.RetryPolicies.constantDelay
import retry.implicits.retrySyntaxError

import scala.concurrent.duration.FiniteDuration

/**
  * Stream that attempts to update file attributes asynchronously for linked files in remote storages
  */
sealed trait FileAttributesUpdateStream

object FileAttributesUpdateStream {
  private val logger = Logger[FileAttributesUpdateStream]

  private val metadata: ProjectionMetadata = ProjectionMetadata("system", "file-attributes-update", None, None)

  final object Disabled extends FileAttributesUpdateStream

  final class Impl(
      streamFiles: Offset => SuccessElemStream[FileState],
      fetchStorage: (ProjectRef, ResourceRef.Revision) => IO[Storage],
      updateAttributes: (FileState, Storage) => IO[Unit],
      retryDelay: FiniteDuration
  ) extends FileAttributesUpdateStream {

    def run(offset: Offset): Stream[IO, Elem[Unit]] =
      streamFiles(offset).evalMap {
        _.evalMapFilter {
          processFile
        }
      }

    private[files] def processFile(file: FileState) = {
      if (file.storageType == StorageType.RemoteDiskStorage && !file.attributes.digest.computed && !file.deprecated) {
        for {
          _       <- logger.debug(s"Attempt to update attributes for file ${file.id} in ${file.project}")
          storage <- fetchStorage(file.project, file.storage)
          _       <- updateAttributes(file, storage)
          _       <- logger.info(s"Attributes for file ${file.id} in ${file.project} have been updated.")
        } yield Some(())
      } else IO.none
    }.retryingOnAllErrors(
      constantDelay(retryDelay),
      {
        case (_: DigestNotComputed, details) =>
          IO.whenA(details.retriesSoFar % 10 == 0)(
            logger.info(
              s"Digest for file '${file.id}' in '${file.project}' is not yet completed after '${details.cumulativeDelay}'."
            )
          )
        case (error, details)                =>
          logger.error(error)(
            s"Digest for file '${file.id}' in '${file.project}' ended in an error at attempt '${details.retriesSoFar}' and after '${details.cumulativeDelay}'."
          )
      }
    )
  }

  def apply(
      files: Files,
      storages: Storages,
      configOpt: Option[RemoteDiskStorageConfig]
  ): IO[FileAttributesUpdateStream] =
    configOpt match {
      case Some(config) =>
        LocalCache[(ProjectRef, ResourceRef.Revision), Storage]().map { storageCache =>
          def fetchStorage(project: ProjectRef, id: ResourceRef.Revision) =
            storageCache.getOrElseUpdate(
              (project, id),
              storages
                .fetch(IdSegmentRef(id), project)
                .map(_.value)
            )

          new Impl(
            files.states,
            fetchStorage,
            files.updateAttributes,
            config.digestComputationRetryDelay
          )
        }
      case None         => IO.pure(Disabled)
    }

  def start(
      files: Files,
      storages: Storages,
      configOpt: Option[RemoteDiskStorageConfig],
      supervisor: Supervisor
  ): IO[FileAttributesUpdateStream] =
    apply(files, storages, configOpt).flatTap {
      case enabled: Impl    =>
        supervisor
          .run(
            CompiledProjection.fromStream(
              metadata,
              ExecutionStrategy.PersistentSingleNode,
              enabled.run
            )
          )
      case _: Disabled.type =>
        logger.debug("Remote storage is disabled, the update attributes task is disabled too.")
    }
}
