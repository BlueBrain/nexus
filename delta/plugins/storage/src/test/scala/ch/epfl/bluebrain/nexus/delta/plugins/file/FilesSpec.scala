package ch.epfl.bluebrain.nexus.delta.plugins.file

import akka.http.scaladsl.model.{ContentTypes, Uri}
import ch.epfl.bluebrain.nexus.delta.plugins.file.Files.{evaluate, next}
import ch.epfl.bluebrain.nexus.delta.plugins.file.model.Digest.{ComputedDigest, NotComputedDigest}
import ch.epfl.bluebrain.nexus.delta.plugins.file.model.FileAttributes
import ch.epfl.bluebrain.nexus.delta.plugins.file.model.FileCommand.{CreateFile, _}
import ch.epfl.bluebrain.nexus.delta.plugins.file.model.FileEvent._
import ch.epfl.bluebrain.nexus.delta.plugins.file.model.FileRejection._
import ch.epfl.bluebrain.nexus.delta.plugins.file.model.FileState._
import ch.epfl.bluebrain.nexus.delta.plugins.storage.model.DigestAlgorithm
import ch.epfl.bluebrain.nexus.delta.plugins.storage.model.StorageReference.{DiskStorageReference, RemoteDiskStorageReference}
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.nxv
import ch.epfl.bluebrain.nexus.delta.sdk.model.Label
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Identity.User
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.ProjectRef
import ch.epfl.bluebrain.nexus.testkit.{CirceLiteral, IOFixedClock, IOValues}
import monix.execution.Scheduler
import org.scalatest.Inspectors
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import java.time.Instant
import java.util.UUID

class FilesSpec extends AnyWordSpec with Matchers with IOValues with IOFixedClock with Inspectors with CirceLiteral {

  implicit private val sc: Scheduler = Scheduler.global

  private val epoch = Instant.EPOCH
  private val time2 = Instant.ofEpochMilli(10L)
  private val realm = Label.unsafe("myrealm")
  private val bob   = User("Bob", realm)
  private val alice = User("Alice", realm)

  private val project = ProjectRef.unsafe("org", "proj")

  private val id               = nxv + "file"
  private val myTag            = Label.unsafe("myTag")
  private val uuid             = UUID.randomUUID()
  private val mediaType        = ContentTypes.`text/plain(UTF-8)`
  private val digest           = ComputedDigest(DigestAlgorithm.default, "something")
  private val storageRef       = DiskStorageReference(nxv + "disk-reference", 1L)
  private val remoteStorageRef = RemoteDiskStorageReference(nxv + "remote-disk-reference", 1L)
  private val attributes       = FileAttributes(
    uuid,
    location = "http://localhost/my/file.txt",
    path = Uri.Path("my/file.txt"),
    filename = "myfile.txt",
    mediaType = mediaType,
    bytes = 10,
    digest
  )

  "The Files state machine" when {

    "evaluating an incoming command" should {

      "create a new event from a CreateFile command" in {
        val createCmd = CreateFile(id, project, storageRef, attributes, bob)

        evaluate(Initial, createCmd).accepted shouldEqual
          FileCreated(id, project, storageRef, attributes, 1, epoch, bob)
      }

      "create a new event from a UpdateFile command" in {
        val updateCmd = UpdateFile(id, project, storageRef, attributes, 1, alice)
        val current   = FileGen.currentState(id, project, remoteStorageRef, attributes.copy(bytes = 1))

        evaluate(current, updateCmd).accepted shouldEqual
          FileUpdated(id, project, storageRef, attributes, 2, epoch, alice)
      }

      "create a new event from a UpdateFileComputedAttributes command" in {
        val updateAttrCmd = UpdateFileComputedAttributes(id, project, remoteStorageRef, mediaType, 10, digest, 1, alice)
        val current       = FileGen.currentState(id, project, remoteStorageRef, attributes.copy(bytes = 1))

        evaluate(current, updateAttrCmd).accepted shouldEqual
          FileComputedAttributesUpdated(id, project, remoteStorageRef, mediaType, 10, digest, 2, epoch, alice)
      }

      "create a new event from a TagFile command" in {
        val current = FileGen.currentState(id, project, storageRef, attributes, rev = 2)
        evaluate(current, TagFile(id, project, targetRev = 2, myTag, 2, alice)).accepted shouldEqual
          FileTagAdded(id, project, targetRev = 2, myTag, 3, epoch, alice)
      }

      "create a new event from a DeprecateFile command" in {
        val current = FileGen.currentState(id, project, storageRef, attributes, rev = 2)
        evaluate(current, DeprecateFile(id, project, 2, alice)).accepted shouldEqual
          FileDeprecated(id, project, 3, epoch, alice)
      }

      "reject with IncorrectRev" in {
        val current  = FileGen.currentState(id, project, storageRef, attributes)
        val commands = List(
          UpdateFile(id, project, storageRef, attributes, 2, alice),
          UpdateFileComputedAttributes(id, project, remoteStorageRef, mediaType, 10, digest, 2, alice),
          TagFile(id, project, targetRev = 1, myTag, 2, alice),
          DeprecateFile(id, project, 2, alice)
        )
        forAll(commands) { cmd =>
          evaluate(current, cmd).rejected shouldEqual IncorrectRev(provided = 2, expected = 1)
        }
      }

      "reject with FileAlreadyExists" in {
        val current = FileGen.currentState(id, project, storageRef, attributes)
        evaluate(current, CreateFile(id, project, storageRef, attributes, bob)).rejectedWith[FileAlreadyExists]
      }

      "reject with FileNotFound" in {
        val commands = List(
          UpdateFile(id, project, storageRef, attributes, 2, alice),
          UpdateFileComputedAttributes(id, project, remoteStorageRef, mediaType, 10, digest, 2, alice),
          TagFile(id, project, targetRev = 1, myTag, 2, alice),
          DeprecateFile(id, project, 2, alice)
        )
        forAll(commands) { cmd =>
          evaluate(Initial, cmd).rejectedWith[FileNotFound]
        }
      }

      "reject with FileIsDeprecated" in {
        val current  = FileGen.currentState(id, project, storageRef, attributes, rev = 2, deprecated = true)
        val commands = List(
          UpdateFile(id, project, storageRef, attributes, 2, alice),
          UpdateFileComputedAttributes(id, project, remoteStorageRef, mediaType, 10, digest, 2, alice),
          TagFile(id, project, targetRev = 1, myTag, 2, alice),
          DeprecateFile(id, project, 2, alice)
        )
        forAll(commands) { cmd =>
          evaluate(current, cmd).rejectedWith[FileIsDeprecated]
        }
      }

      "reject with RevisionNotFound" in {
        val current = FileGen.currentState(id, project, storageRef, attributes)
        evaluate(current, TagFile(id, project, targetRev = 3, myTag, 1, alice)).rejected shouldEqual
          RevisionNotFound(provided = 3, current = 1)
      }

      "reject with DigestNotComputed" in {
        val current = FileGen.currentState(id, project, storageRef, attributes.copy(digest = NotComputedDigest))
        val cmd     = UpdateFile(id, project, storageRef, attributes, 1, alice)
        evaluate(current, cmd).rejected shouldEqual DigestNotComputed(id)
      }

    }

    "producing next state" should {

      "from a new FileCreated event" in {
        val event     = FileCreated(id, project, storageRef, attributes, 1, epoch, bob)
        val nextState = FileGen.currentState(id, project, storageRef, attributes, createdBy = bob, updatedBy = bob)

        next(Initial, event) shouldEqual nextState
        next(nextState, event) shouldEqual nextState
      }

      "from a new FileUpdated event" in {
        val event = FileUpdated(id, project, storageRef, attributes, 2, time2, alice)
        next(Initial, event) shouldEqual Initial

        val att     = attributes.copy(bytes = 1)
        val current = FileGen.currentState(id, project, remoteStorageRef, att, createdBy = bob, updatedBy = bob)

        next(current, event) shouldEqual
          current.copy(rev = 2L, storage = storageRef, attributes = attributes, updatedAt = time2, updatedBy = alice)
      }

      "from a new FileTagAdded event" in {
        val tag1    = Label.unsafe("tag1")
        val event   = FileTagAdded(id, project, targetRev = 1, tag1, 3, time2, alice)
        val current = FileGen.currentState(id, project, storageRef, attributes, tags = Map(myTag -> 2), rev = 2)

        next(Initial, event) shouldEqual Initial

        next(current, event) shouldEqual
          current.copy(rev = 3, updatedAt = time2, updatedBy = alice, tags = Map(myTag -> 2, tag1 -> 1))
      }

      "from a new FileDeprecated event" in {
        val event   = FileDeprecated(id, project, 2, time2, alice)
        val current = FileGen.currentState(id, project, storageRef, attributes)

        next(Initial, event) shouldEqual Initial

        next(current, event) shouldEqual current.copy(rev = 2, deprecated = true, updatedAt = time2, updatedBy = alice)
      }
    }
  }

}
