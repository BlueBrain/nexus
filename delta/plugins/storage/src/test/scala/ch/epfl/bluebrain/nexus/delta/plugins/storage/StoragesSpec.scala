package ch.epfl.bluebrain.nexus.delta.plugins.storage

import ch.epfl.bluebrain.nexus.delta.plugins.storage.Storages.{evaluate, next}
import ch.epfl.bluebrain.nexus.delta.plugins.storage.model.StorageCommand._
import ch.epfl.bluebrain.nexus.delta.plugins.storage.model.StorageEvent._
import ch.epfl.bluebrain.nexus.delta.plugins.storage.model.StorageRejection._
import ch.epfl.bluebrain.nexus.delta.plugins.storage.model.StorageState.Initial
import ch.epfl.bluebrain.nexus.delta.plugins.storage.model.StorageValue._
import ch.epfl.bluebrain.nexus.delta.plugins.storage.model.{DigestAlgorithm, S3Settings}
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.nxv
import ch.epfl.bluebrain.nexus.delta.sdk.model.Label
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Identity.User
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.ProjectRef
import ch.epfl.bluebrain.nexus.testkit.{CirceLiteral, IOFixedClock, IOValues}
import io.circe.Json
import monix.bio.IO
import monix.execution.Scheduler
import org.scalatest.Inspectors
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import java.nio.file.Files
import java.time.Instant

class StoragesSpec extends AnyWordSpec with Matchers with IOValues with IOFixedClock with Inspectors with CirceLiteral {

  implicit private val sc: Scheduler = Scheduler.global

  private val epoch = Instant.EPOCH
  private val time2 = Instant.ofEpochMilli(10L)
  private val realm = Label.unsafe("myrealm")
  private val bob   = User("Bob", realm)
  private val alice = User("Alice", realm)

  private val project = ProjectRef.unsafe("org", "proj")

  private val dId  = nxv + "disk-storage"
  private val s3Id = nxv + "s3-storage"
  private val rdId = nxv + "remote-disk-storage"

  // format: off
  private val diskVal       = DiskStorageValue(default = true, DigestAlgorithm.default, Files.createTempDirectory("disk"), permissions.read, permissions.write, 30L)
  private val s3Val         = S3StorageValue(default = true, DigestAlgorithm.default, "mybucket", S3Settings(None, None, None), permissions.read, permissions.write, 30L)
  private val remoteVal = RemoteDiskStorageValue(default = true, DigestAlgorithm.default, "localhost", None, Label.unsafe("myfolder"), permissions.read, permissions.write, 30L)
  // format: on

  private val access: Storages.StorageAccess = {
    case disk: DiskStorageValue         =>
      if (disk.volume != diskVal.volume) IO.raiseError(StorageNotAccessible(dId, "wrong volume")) else IO.unit
    case s3: S3StorageValue             =>
      if (s3.bucket != s3Val.bucket) IO.raiseError(StorageNotAccessible(dId, "wrong bucket")) else IO.unit
    case remote: RemoteDiskStorageValue =>
      if (remote.endpoint != remoteVal.endpoint) IO.raiseError(StorageNotAccessible(dId, "wrong endpoint"))
      else IO.unit
  }

  private val eval = evaluate(access, 50)(_, _)

  "The Storages state machine" when {

    "evaluating an incoming command" should {

      "create a new event from a CreateStorage command" in {
        val disk   = CreateStorage(dId, project, diskVal.copy(maxFileSize = 1), json"""{"disk": "created"}""", bob)
        val s3     = CreateStorage(s3Id, project, s3Val.copy(maxFileSize = 2), json"""{"s3": "created"}""", bob)
        val remote = CreateStorage(rdId, project, remoteVal.copy(maxFileSize = 3), json"""{"remote": "created"}""", bob)

        forAll(List(disk, s3, remote)) { createCmd =>
          eval(Initial, createCmd).accepted shouldEqual
            StorageCreated(createCmd.id, createCmd.project, createCmd.value, createCmd.source, 1, epoch, bob)
        }
      }

      "create a new event from a UpdateStorage command" in {
        val disk          = UpdateStorage(dId, project, diskVal, json"""{"disk": "updated"}""", 1, alice)
        val diskCurrent   = StorageGen.currentState(dId, project, diskVal.copy(maxFileSize = 1))
        val s3            = UpdateStorage(s3Id, project, s3Val, json"""{"s3": "updated"}""", 1, alice)
        val s3Current     = StorageGen.currentState(s3Id, project, s3Val.copy(maxFileSize = 2))
        val remote        = UpdateStorage(rdId, project, remoteVal, json"""{"remote": "updated"}""", 1, alice)
        val remoteCurrent = StorageGen.currentState(rdId, project, remoteVal.copy(maxFileSize = 3))

        forAll(List(diskCurrent -> disk, s3Current -> s3, remoteCurrent -> remote)) { case (current, updateCmd) =>
          eval(current, updateCmd).accepted shouldEqual
            StorageUpdated(updateCmd.id, updateCmd.project, updateCmd.value, updateCmd.source, 2, epoch, alice)
        }
      }

      "create a new event from a TagStorage command" in {
        val current = StorageGen.currentState(dId, project, diskVal, rev = 3)
        eval(current, TagStorage(dId, project, 2, Label.unsafe("myTag"), 3, alice)).accepted shouldEqual
          StorageTagAdded(dId, project, 2, Label.unsafe("myTag"), 4, epoch, alice)
      }

      "create a new event from a DeprecateStorage command" in {
        val current = StorageGen.currentState(dId, project, diskVal, rev = 3)
        eval(current, DeprecateStorage(dId, project, 3, alice)).accepted shouldEqual
          StorageDeprecated(dId, project, 4, epoch, alice)
      }

      "reject with IncorrectRev" in {
        val current  = StorageGen.currentState(dId, project, diskVal)
        val commands = List(
          UpdateStorage(dId, project, diskVal, Json.obj(), 2, alice),
          TagStorage(dId, project, 1L, Label.unsafe("tag"), 2, alice),
          DeprecateStorage(dId, project, 2, alice)
        )
        forAll(commands) { cmd =>
          eval(current, cmd).rejected shouldEqual IncorrectRev(provided = 2, expected = 1)
        }
      }

      "reject with StorageNotAccessible" in {
        val inaccessibleDiskVal   = diskVal.copy(volume = Files.createTempDirectory("other"))
        val inaccessibleS3Val     = s3Val.copy(bucket = "other")
        val inaccessibleRemoteVal = remoteVal.copy(endpoint = "other.com")
        val diskCurrent           = StorageGen.currentState(dId, project, diskVal)
        val s3Current             = StorageGen.currentState(s3Id, project, s3Val)
        val remoteCurrent         = StorageGen.currentState(rdId, project, remoteVal)

        forAll(List(dId -> inaccessibleDiskVal, s3Id -> inaccessibleS3Val, rdId -> inaccessibleRemoteVal)) {
          case (id, value) =>
            val createCmd = CreateStorage(id, project, value, Json.obj(), bob)
            eval(Initial, createCmd).rejected shouldBe a[StorageNotAccessible]
        }

        forAll(
          List(
            diskCurrent   -> inaccessibleDiskVal,
            s3Current     -> inaccessibleS3Val,
            remoteCurrent -> inaccessibleRemoteVal
          )
        ) { case (current, value) =>
          val updateCmd = UpdateStorage(current.id, project, value, Json.obj(), 1L, alice)
          eval(current, updateCmd).rejected shouldBe a[StorageNotAccessible]
        }
      }

      "reject with InvalidMaxFileSize" in {
        val exceededSizeDiskVal   = diskVal.copy(maxFileSize = 100)
        val exceededSizeS3Val     = s3Val.copy(maxFileSize = 100)
        val exceededSizeRemoteVal = remoteVal.copy(maxFileSize = 100)
        val diskCurrent           = StorageGen.currentState(dId, project, diskVal)
        val s3Current             = StorageGen.currentState(s3Id, project, s3Val)
        val remoteCurrent         = StorageGen.currentState(rdId, project, remoteVal)

        forAll(List(dId -> exceededSizeDiskVal, s3Id -> exceededSizeS3Val, rdId -> exceededSizeRemoteVal)) {
          case (id, value) =>
            val createCmd = CreateStorage(id, project, value, Json.obj(), bob)
            eval(Initial, createCmd).rejected shouldEqual InvalidMaxFileSize(id, 100, 50)
        }

        forAll(
          List(
            diskCurrent   -> exceededSizeDiskVal,
            s3Current     -> exceededSizeS3Val,
            remoteCurrent -> exceededSizeRemoteVal
          )
        ) { case (current, value) =>
          val updateCmd = UpdateStorage(current.id, project, value, Json.obj(), 1L, alice)
          eval(current, updateCmd).rejected shouldEqual InvalidMaxFileSize(current.id, 100, 50)
        }
      }

      "reject with StorageAlreadyExists" in {
        val current = StorageGen.currentState(dId, project, diskVal)
        eval(current, CreateStorage(dId, project, diskVal, Json.obj(), bob)).rejectedWith[StorageAlreadyExists]
      }

      "reject with StorageNotFound" in {
        val commands = List(
          UpdateStorage(dId, project, diskVal, Json.obj(), 2, alice),
          TagStorage(dId, project, 1L, Label.unsafe("tag"), 2, alice),
          DeprecateStorage(dId, project, 2, alice)
        )
        forAll(commands) { cmd =>
          eval(Initial, cmd).rejectedWith[StorageNotFound]
        }
      }

      "reject with StorageIsDeprecated" in {
        val current  = StorageGen.currentState(dId, project, diskVal, rev = 2, deprecated = true)
        val commands = List(
          UpdateStorage(dId, project, diskVal, Json.obj(), 2, alice),
          TagStorage(dId, project, 1L, Label.unsafe("tag"), 2, alice),
          DeprecateStorage(dId, project, 2, alice)
        )
        forAll(commands) { cmd =>
          eval(current, cmd).rejectedWith[StorageIsDeprecated]
        }
      }

      "reject with RevisionNotFound" in {
        val current = StorageGen.currentState(dId, project, diskVal)
        eval(current, TagStorage(dId, project, 3L, Label.unsafe("myTag"), 1L, alice)).rejected shouldEqual
          RevisionNotFound(provided = 3L, current = 1L)
      }

      "reject with DifferentStorageType" in {
        val diskCurrent   = StorageGen.currentState(dId, project, diskVal)
        val s3Current     = StorageGen.currentState(s3Id, project, s3Val)
        val remoteCurrent = StorageGen.currentState(rdId, project, remoteVal)
        val list          = List(
          diskCurrent   -> UpdateStorage(dId, project, s3Val, Json.obj(), 1, alice),
          diskCurrent   -> UpdateStorage(dId, project, remoteVal, Json.obj(), 1, alice),
          s3Current     -> UpdateStorage(s3Id, project, diskVal, Json.obj(), 1, alice),
          s3Current     -> UpdateStorage(s3Id, project, remoteVal, Json.obj(), 1, alice),
          remoteCurrent -> UpdateStorage(rdId, project, diskVal, Json.obj(), 1, alice),
          remoteCurrent -> UpdateStorage(rdId, project, s3Val, Json.obj(), 1, alice)
        )
        forAll(list) { case (current, cmd) =>
          eval(current, cmd).rejectedWith[DifferentStorageType]
        }
      }

    }

    "producing next state" should {

      "from a new StorageCreated event" in {
        val event     = StorageCreated(dId, project, diskVal, json"""{"disk": "created"}""", 1, epoch, bob)
        val nextState = StorageGen.currentState(dId, project, diskVal, event.source, createdBy = bob, updatedBy = bob)

        next(Initial, event) shouldEqual nextState
        next(nextState, event) shouldEqual nextState
      }

      "from a new StorageUpdated event" in {
        val event = StorageUpdated(dId, project, diskVal, json"""{"disk": "created"}""", 2, time2, alice)
        next(Initial, event) shouldEqual Initial

        val currentDisk = diskVal.copy(maxFileSize = 2)
        val current     = StorageGen.currentState(dId, project, currentDisk, Json.obj(), createdBy = bob, updatedBy = bob)

        next(current, event) shouldEqual
          current.copy(rev = 2L, value = event.value, source = event.source, updatedAt = time2, updatedBy = alice)
      }

      "from a new StorageTagAdded event" in {
        val tag1    = Label.unsafe("tag1")
        val tag2    = Label.unsafe("tag2")
        val event   = StorageTagAdded(dId, project, 1, tag2, 3, time2, alice)
        val current = StorageGen.currentState(dId, project, diskVal, Json.obj(), tags = Map(tag1 -> 2), rev = 2)

        next(Initial, event) shouldEqual Initial

        next(current, event) shouldEqual
          current.copy(rev = 3, updatedAt = time2, updatedBy = alice, tags = Map(tag1 -> 2, tag2 -> 1))
      }

      "from a new StorageDeprecated event" in {
        val event   = StorageDeprecated(dId, project, 2, time2, alice)
        val current = StorageGen.currentState(dId, project, diskVal, Json.obj())

        next(Initial, event) shouldEqual Initial

        next(current, event) shouldEqual current.copy(rev = 2, deprecated = true, updatedAt = time2, updatedBy = alice)
      }
    }
  }

}
