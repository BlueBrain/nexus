package ch.epfl.bluebrain.nexus.delta.plugins.storage.storages

import akka.actor.typed.ActorSystem
import akka.persistence.query.Offset
import cats.implicits._
import ch.epfl.bluebrain.nexus.delta.kernel.RetryStrategy
import ch.epfl.bluebrain.nexus.delta.kernel.utils.UUIDF
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.Files
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model.FileEvent
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model.FileEvent.{FileAttributesUpdated, FileCreated, FileUpdated}
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.StorageRejection.StorageFetchRejection
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.StorageStatsCollection
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.StorageStatsCollection.StorageStatEntry
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.sdk.cache.{CompositeKeyValueStore, KeyValueStoreConfig}
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.ProjectRef
import ch.epfl.bluebrain.nexus.delta.sdk.model.{Envelope, IdSegment}
import ch.epfl.bluebrain.nexus.delta.sdk.syntax._
import ch.epfl.bluebrain.nexus.delta.sourcing.config.SaveProgressConfig
import ch.epfl.bluebrain.nexus.delta.sourcing.projections.ProjectionId.CacheProjectionId
import ch.epfl.bluebrain.nexus.delta.sourcing.projections.stream.DaemonStreamCoordinator
import ch.epfl.bluebrain.nexus.delta.sourcing.projections.tracing.ProgressTracingConfig
import ch.epfl.bluebrain.nexus.delta.sourcing.projections.{Projection, SuccessMessage}
import com.typesafe.scalalogging.Logger
import fs2.Stream
import monix.bio.{IO, Task, UIO}
import monix.execution.Scheduler

trait StoragesStatistics {

  /**
    * Retrieve the current statistics for all storages
    */
  def get(): UIO[StorageStatsCollection]

  /**
    * Retrieve the current statistics for storages in the given project
    */
  def get(project: ProjectRef): UIO[Map[Iri, StorageStatEntry]]

  /**
    * Retrieve the current statistics for the given project
    */
  def get(idSegment: IdSegment, project: ProjectRef): IO[StorageFetchRejection, StorageStatEntry]

}

object StoragesStatistics {

  private val logger: Logger = Logger[StoragesStatistics]

  private val id: String = "StorageStatistics"
  type StreamFromOffset = Offset => Stream[Task, Envelope[FileEvent]]
  val projectionId: CacheProjectionId               = CacheProjectionId(id)
  implicit val tracingConfig: ProgressTracingConfig = ProgressTracingConfig(projectionId.value, Map.empty)

  /**
    * Construct a [[StoragesStatistics]] from a passed ''projection'' and ''stream'' function. The underlying stream
    * will store its progress and compute the stats for each storage.
    */
  def apply(
      files: Files,
      storages: Storages,
      projection: Projection[StorageStatsCollection],
      stream: StreamFromOffset,
      config: StoragesConfig
  )(implicit uuidF: UUIDF, as: ActorSystem[Nothing], sc: Scheduler): Task[StoragesStatistics] = {
    implicit val keyValueStoreConfig: KeyValueStoreConfig  = config.keyValueStore
    implicit val persistProgressConfig: SaveProgressConfig = config.persistProgressConfig
    apply(
      files
        .fetch(_, _)
        .bimap(
          e => new IllegalStateException(e.reason),
          _.value.storage.iri
        ),
      storages.fetch(_, _).map(_.id),
      projection,
      stream
    )
  }

  /**
    * Construct a [[StoragesStatistics]] from a passed ''projection'' and ''stream'' function. The underlying stream
    * will store its progress and compute the stats for each storage.
    */
  private[storages] def apply(
      fetchFileStorage: (Iri, ProjectRef) => Task[Iri],
      fetchStorageId: (IdSegment, ProjectRef) => IO[StorageFetchRejection, Iri],
      projection: Projection[StorageStatsCollection],
      stream: StreamFromOffset
  )(implicit
      uuidF: UUIDF,
      as: ActorSystem[Nothing],
      sc: Scheduler,
      keyValueStoreConfig: KeyValueStoreConfig,
      persistProgressConfig: SaveProgressConfig
  ): Task[StoragesStatistics] = {

    val cache =
      CompositeKeyValueStore[ProjectRef, Iri, StorageStatEntry](
        id,
        (_, stats) => stats.lastProcessedEventDateTime.fold(0L)(_.toEpochMilli)
      )

    // Build the storage stat entry from the file event
    def fileEventToStatEntry(f: FileEvent): Task[((ProjectRef, Iri), StorageStatEntry)] = f match {
      case c: FileCreated if !c.attributes.digest.computed =>
        UIO.pure((c.project, c.storage.iri) -> StorageStatEntry(1L, 0L, Some(c.instant)))
      case c: FileCreated                                  =>
        UIO.pure((c.project, c.storage.iri) -> StorageStatEntry(1L, c.attributes.bytes, Some(c.instant)))
      case u: FileUpdated                                  =>
        UIO.pure((u.project, u.storage.iri) -> StorageStatEntry(1L, u.attributes.bytes, Some(u.instant)))
      case fau: FileAttributesUpdated                      =>
        fetchFileStorage(fau.id, fau.project).map { storageIri =>
          (fau.project, storageIri) -> StorageStatEntry(0L, fau.bytes, Some(fau.instant))
        }
      case other                                           =>
        fetchFileStorage(other.id, other.project).map { storageIri =>
          (other.project, storageIri) -> StorageStatEntry(0L, 0L, Some(other.instant))
        }
    }

    def buildStream: Stream[Task, Unit] =
      Stream
        .eval(projection.progress(projectionId))
        .evalTap { progress =>
          cache.putAll(progress.value.value)
        }
        .flatMap { progress =>
          val initial = SuccessMessage(progress.offset, progress.timestamp, "", 1, progress.value, Vector.empty)
          stream(progress.offset)
            .evalMapAccumulate(initial) { case (acc, envelope) =>
              for {
                ((project, storageId), statEntry) <- fileEventToStatEntry(envelope.event)
                updatedEntries                     = acc.value.value |+| Map(project -> Map(storageId -> statEntry))
                updatedEntry                       = updatedEntries.get(project).flatMap(_.get(storageId))
                _                                 <- updatedEntry.fold(
                                                       UIO.delay(logger.warn(s"We should have an entry for storage $storageId in project $project"))
                                                     )(cache.put(project, storageId, _))
              } yield envelope.toMessage.copy(
                value = StorageStatsCollection(updatedEntries)
              ) -> ()
            }
            .map(_._1)
            .persistProgress(progress, projectionId, projection, persistProgressConfig)
            .enableTracing
            .void
        }

    DaemonStreamCoordinator
      .run(id, buildStream, RetryStrategy.retryOnNonFatal(keyValueStoreConfig.retry, logger, "storage statistics"))
      .as(
        new StoragesStatistics {

          override def get(): UIO[StorageStatsCollection] = cache.entries.map(StorageStatsCollection(_))

          override def get(project: ProjectRef): UIO[Map[Iri, StorageStatEntry]] =
            cache.get(project)

          override def get(idSegment: IdSegment, project: ProjectRef): IO[StorageFetchRejection, StorageStatEntry] =
            for {
              iri <- fetchStorageId(idSegment, project)
              res <- cache.get(project, iri).map(_.getOrElse(StorageStatEntry.empty))
            } yield res
        }
      )
  }

}
