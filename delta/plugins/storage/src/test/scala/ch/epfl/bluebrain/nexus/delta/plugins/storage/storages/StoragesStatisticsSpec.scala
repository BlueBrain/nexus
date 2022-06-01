package ch.epfl.bluebrain.nexus.delta.plugins.storage.storages

import akka.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import akka.cluster.typed.{Cluster, Join}
import akka.http.scaladsl.model.Uri
import akka.persistence.query.{Offset, Sequence}
import ch.epfl.bluebrain.nexus.delta.kernel.utils.UUIDF
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model.Digest.{ComputedDigest, NotComputedDigest}
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model.FileAttributes.FileAttributesOrigin.Storage
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model.FileEvent.{FileAttributesUpdated, FileCreated, FileDeprecated, FileTagAdded, FileUpdated}
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model.{FileAttributes, FileEvent}
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.StorageRejection.InvalidStorageId
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.StorageStatsCollection.StorageStatEntry
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.{DigestAlgorithm, StorageStatsCollection, StorageType}
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.nxv
import ch.epfl.bluebrain.nexus.delta.sdk.cache.KeyValueStoreConfig
import ch.epfl.bluebrain.nexus.delta.sdk.model.IdSegment.IriSegment
import ch.epfl.bluebrain.nexus.delta.sdk.model._
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Identity.User
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.ProjectRef
import ch.epfl.bluebrain.nexus.delta.sdk.testkit.ConfigFixtures
import ch.epfl.bluebrain.nexus.delta.sourcing.config.SaveProgressConfig
import ch.epfl.bluebrain.nexus.delta.sourcing.model.ResourceRef
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Tag.UserTag
import ch.epfl.bluebrain.nexus.delta.sourcing.projections.{Projection, ProjectionProgress}
import ch.epfl.bluebrain.nexus.testkit.{IOFixedClock, IOValues}
import com.typesafe.config.ConfigFactory
import fs2.Stream
import monix.bio.{IO, Task, UIO}
import monix.execution.Scheduler
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

import java.time.Instant
import java.util.UUID

class StoragesStatisticsSpec
    extends ScalaTestWithActorTestKit(
      ConfigFactory.parseResources("akka-cluster-test.conf").withFallback(ConfigFactory.load()).resolve()
    )
    with AnyWordSpecLike
    with Matchers
    with ConfigFixtures
    with IOFixedClock
    with IOValues {

  private val cluster = Cluster(system)
  cluster.manager ! Join(cluster.selfMember.address)

  private val subject = User("bob", Label.unsafe("realm"))

  private val project1 = ProjectRef.unsafe("org", "proj1")
  private val project2 = ProjectRef.unsafe("org2", "proj2")

  private val storage1 = nxv + "storage1"
  private val storage2 = nxv + "storage2"

  private val file1 = nxv + "file1"
  private val file2 = nxv + "file2"
  private val file3 = nxv + "file3"

  private def fileAttributes(bytes: Long, withDigest: Boolean) =
    FileAttributes(
      UUID.randomUUID(),
      s"file:///something",
      Uri.Path("org/something"),
      "something.txt",
      None,
      bytes,
      if (withDigest) ComputedDigest(DigestAlgorithm.default, "value") else NotComputedDigest,
      Storage
    )

  private def fetchFileStorage = (fileId: Iri, _: ProjectRef) =>
    UIO.pure(
      fileId match {
        case `file2` => storage2
        case _       => storage1
      }
    )

  private def fetchStorageId = (idSegment: IdSegment, _: ProjectRef) =>
    idSegment match {
      case IriSegment(iri) => UIO.pure(iri)
      case segment         => IO.raiseError(InvalidStorageId(segment.toString))
    }

  private val stream: Stream[Task, Envelope[FileEvent]] = Stream
    .emits(
      List(
        FileCreated(
          file1,
          project1,
          ResourceRef.Revision(storage1, 1),
          StorageType.DiskStorage,
          fileAttributes(10L, withDigest = true),
          1L,
          Instant.ofEpochMilli(1000L),
          subject
        ),
        FileCreated(
          file2,
          project2,
          ResourceRef.Revision(storage2, 1),
          StorageType.DiskStorage,
          fileAttributes(20L, withDigest = true),
          1L,
          Instant.ofEpochMilli(2000L),
          subject
        ),
        FileUpdated(
          file1,
          project1,
          ResourceRef.Revision(storage1, 2),
          StorageType.DiskStorage,
          fileAttributes(50L, withDigest = true),
          2L,
          Instant.ofEpochMilli(3000L),
          subject
        ),
        FileCreated(
          file3,
          project1,
          ResourceRef.Revision(storage1, 2),
          StorageType.DiskStorage,
          fileAttributes(1000L, withDigest = false),
          1L,
          Instant.ofEpochMilli(4000L),
          subject
        ),
        FileAttributesUpdated(file3, project1, None, 25L, NotComputedDigest, 2L, Instant.ofEpochMilli(5000L), subject),
        FileTagAdded(file3, project1, 1L, UserTag.unsafe("my-tag"), 3L, Instant.ofEpochMilli(6000L), subject),
        FileDeprecated(file3, project1, 4L, Instant.ofEpochMilli(7000L), subject)
      ).zipWithIndex.map { case (event, index) =>
        Envelope(
          event,
          event.instant,
          eventType = "file",
          offset = Sequence(index + 1L),
          persistenceId = event.id.toString,
          sequenceNr = event.rev
        )
      }
    )
    .covary[Task]

  implicit private val sc: Scheduler                       = Scheduler.global
  implicit private val uuidF: UUIDF                        = UUIDF.random
  implicit private val persistProgress: SaveProgressConfig = persist
  implicit private val kvStoreConfig: KeyValueStoreConfig  = keyValueStore

  "Stream statistics" should {
    val projection = Projection.inMemory(StorageStatsCollection.empty).accepted
    val stats      = StoragesStatistics(
      fetchFileStorage,
      fetchStorageId,
      projection,
      (_: Offset) => stream
    ).accepted

    val storage1Stats = StorageStatEntry(3L, 85L, Some(Instant.ofEpochMilli(7000L)))
    val storage2Stats = StorageStatEntry(1L, 20L, Some(Instant.ofEpochMilli(2000L)))

    "be computed" in eventually {
      stats.get(storage1, project1).accepted shouldEqual storage1Stats
      stats.get(storage2, project2).accepted shouldEqual storage2Stats

      // Existing storage without any file
      stats.get(nxv + "storage3", project2).accepted shouldEqual StorageStatEntry.empty
    }

    "retrieve its offset" in eventually {
      val progress = projection.progress(StoragesStatistics.projectionId).accepted
      progress shouldEqual ProjectionProgress(
        Sequence(7L),
        Instant.ofEpochMilli(7000L),
        7L,
        0L,
        0L,
        0L,
        StorageStatsCollection(
          Map(
            project1 -> Map(storage1 -> storage1Stats),
            project2 -> Map(storage2 -> storage2Stats)
          )
        )
      )
    }

    "delete properly the project" in {
      stats.remove(project1).accepted

      eventually {
        stats.get(storage1, project1).accepted shouldEqual StorageStatEntry.empty

        val progress = projection.progress(StoragesStatistics.projectionId).accepted

        progress shouldEqual ProjectionProgress(
          Sequence(7L),
          Instant.ofEpochMilli(7000L),
          8L,
          0L,
          0L,
          0L,
          StorageStatsCollection(
            Map(
              project2 -> Map(storage2 -> storage2Stats)
            )
          )
        )
      }
    }
  }
}
