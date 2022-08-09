package ch.epfl.bluebrain.nexus.delta.plugins.storage.files

import akka.http.scaladsl.model.{ContentTypes, Uri}
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.Files.{evaluate, next}
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model.Digest.{ComputedDigest, NotComputedDigest}
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model.FileAttributes
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model.FileAttributes.FileAttributesOrigin.Client
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model.FileCommand.{CreateFile, DeleteFileTag, DeprecateFile, TagFile, UpdateFile, UpdateFileAttributes}
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model.FileEvent.{FileAttributesUpdated, FileCreated, FileDeprecated, FileTagAdded, FileTagDeleted, FileUpdated}
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.StorageType.{DiskStorage => DiskStorageType, RemoteDiskStorage => RemoteStorageType}
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model.FileRejection.{DigestNotComputed, FileIsDeprecated, FileNotFound, IncorrectRev, ResourceAlreadyExists, RevisionNotFound}
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.StorageFixtures
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.DigestAlgorithm
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.nxv
import ch.epfl.bluebrain.nexus.delta.sdk.model.Tags
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.User
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{Label, ResourceRef}
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Tag.UserTag
import ch.epfl.bluebrain.nexus.testkit.{EitherValuable, IOFixedClock, IOValues}
import org.scalatest.{Inspectors, OptionValues}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

import java.time.Instant

class FilesStmSpec
    extends AnyWordSpecLike
    with Matchers
    with IOFixedClock
    with OptionValues
    with EitherValuable
    with Inspectors
    with IOValues
    with FileFixtures
    with StorageFixtures {

  private val epoch = Instant.EPOCH
  private val time2 = Instant.ofEpochMilli(10L)
  private val realm = Label.unsafe("myrealm")
  private val bob   = User("Bob", realm)
  private val alice = User("Alice", realm)

  private val id               = nxv + "files"
  private val myTag            = UserTag.unsafe("myTag")
  private val storageRef       = ResourceRef.Revision(nxv + "disk?rev=1", nxv + "disk", 1L)
  private val remoteStorageRef = ResourceRef.Revision(nxv + "remote?rev=1", nxv + "remote", 1L)
  private val mediaType        = Some(ContentTypes.`text/plain(UTF-8)`)
  private val dig              = ComputedDigest(DigestAlgorithm.default, "something")
  private val attributes       = FileAttributes(
    uuid,
    location = "http://localhost/my/file.txt",
    path = Uri.Path("my/file.txt"),
    filename = "myfile.txt",
    mediaType = mediaType,
    bytes = 10,
    dig,
    Client
  )

  "The Files state machine" when {
    "evaluating an incoming command" should {

      "create a new event from a CreateFile command" in {
        val createCmd = CreateFile(id, projectRef, storageRef, DiskStorageType, attributes, bob)

        evaluate(None, createCmd).accepted shouldEqual
          FileCreated(id, projectRef, storageRef, DiskStorageType, attributes, 1, epoch, bob)
      }

      "create a new event from a UpdateFile command" in {
        val updateCmd = UpdateFile(id, projectRef, storageRef, DiskStorageType, attributes, 1, alice)
        val current   =
          FileGen.state(id, projectRef, remoteStorageRef, attributes.copy(bytes = 1), RemoteStorageType)

        evaluate(Some(current), updateCmd).accepted shouldEqual
          FileUpdated(id, projectRef, storageRef, DiskStorageType, attributes, 2, epoch, alice)
      }

      "create a new event from a UpdateFileAttributes command" in {
        val updateAttrCmd = UpdateFileAttributes(id, projectRef, mediaType, 10, dig, 1, alice)
        val current       = FileGen.state(id, projectRef, remoteStorageRef, attributes.copy(bytes = 1))

        evaluate(Some(current), updateAttrCmd).accepted shouldEqual
          FileAttributesUpdated(id, projectRef, mediaType, 10, dig, 2, epoch, alice)
      }

      "create a new event from a TagFile command" in {
        val current = FileGen.state(id, projectRef, storageRef, attributes, rev = 2)
        evaluate(Some(current), TagFile(id, projectRef, targetRev = 2, myTag, 2, alice)).accepted shouldEqual
          FileTagAdded(id, projectRef, targetRev = 2, myTag, 3, epoch, alice)
      }

      "create a new event from a DeleteFileTag command" in {
        val current =
          FileGen.state(id, projectRef, storageRef, attributes, rev = 2).copy(tags = Tags(myTag -> 2))
        evaluate(Some(current), DeleteFileTag(id, projectRef, myTag, 2, alice)).accepted shouldEqual
          FileTagDeleted(id, projectRef, myTag, 3, epoch, alice)
      }

      "create a new event from a TagFile command when deprecated" in {
        val current = FileGen.state(id, projectRef, storageRef, attributes, rev = 2, deprecated = true)
        evaluate(Some(current), TagFile(id, projectRef, targetRev = 2, myTag, 2, alice)).accepted shouldEqual
          FileTagAdded(id, projectRef, targetRev = 2, myTag, 3, epoch, alice)
      }

      "create a new event from a DeprecateFile command" in {
        val current = FileGen.state(id, projectRef, storageRef, attributes, rev = 2)
        evaluate(Some(current), DeprecateFile(id, projectRef, 2, alice)).accepted shouldEqual
          FileDeprecated(id, projectRef, 3, epoch, alice)
      }

      "reject with IncorrectRev" in {
        val current  = FileGen.state(id, projectRef, storageRef, attributes)
        val commands = List(
          UpdateFile(id, projectRef, storageRef, DiskStorageType, attributes, 2, alice),
          UpdateFileAttributes(id, projectRef, mediaType, 10, dig, 2, alice),
          TagFile(id, projectRef, targetRev = 1, myTag, 2, alice),
          DeleteFileTag(id, projectRef, myTag, 2, alice),
          DeprecateFile(id, projectRef, 2, alice)
        )
        forAll(commands) { cmd =>
          evaluate(Some(current), cmd).rejected shouldEqual IncorrectRev(provided = 2, expected = 1)
        }
      }

      "reject with ResourceAlreadyExists when file already exists" in {
        val current = FileGen.state(id, projectRef, storageRef, attributes)
        evaluate(Some(current), CreateFile(id, projectRef, storageRef, DiskStorageType, attributes, bob))
          .rejectedWith[ResourceAlreadyExists]
      }

      "reject with FileNotFound" in {
        val commands = List(
          UpdateFile(id, projectRef, storageRef, DiskStorageType, attributes, 2, alice),
          UpdateFileAttributes(id, projectRef, mediaType, 10, dig, 2, alice),
          TagFile(id, projectRef, targetRev = 1, myTag, 2, alice),
          DeleteFileTag(id, projectRef, myTag, 2, alice),
          DeprecateFile(id, projectRef, 2, alice)
        )
        forAll(commands) { cmd =>
          evaluate(None, cmd).rejectedWith[FileNotFound]
        }
      }

      "reject with FileIsDeprecated" in {
        val current  = FileGen.state(id, projectRef, storageRef, attributes, rev = 2, deprecated = true)
        val commands = List(
          UpdateFile(id, projectRef, storageRef, DiskStorageType, attributes, 2, alice),
          UpdateFileAttributes(id, projectRef, mediaType, 10, dig, 2, alice),
          DeprecateFile(id, projectRef, 2, alice)
        )
        forAll(commands) { cmd =>
          evaluate(Some(current), cmd).rejectedWith[FileIsDeprecated]
        }
      }

      "reject with RevisionNotFound" in {
        val current = FileGen.state(id, projectRef, storageRef, attributes)
        evaluate(Some(current), TagFile(id, projectRef, targetRev = 3, myTag, 1, alice)).rejected shouldEqual
          RevisionNotFound(provided = 3, current = 1)
      }

      "reject with DigestNotComputed" in {
        val current = FileGen.state(id, projectRef, storageRef, attributes.copy(digest = NotComputedDigest))
        val cmd     = UpdateFile(id, projectRef, storageRef, DiskStorageType, attributes, 1, alice)
        evaluate(Some(current), cmd).rejected shouldEqual DigestNotComputed(id)
      }

    }

    "producing next state" should {

      "from a new FileCreated event" in {
        val event     = FileCreated(id, projectRef, storageRef, DiskStorageType, attributes, 1, epoch, bob)
        val nextState = FileGen.state(id, projectRef, storageRef, attributes, createdBy = bob, updatedBy = bob)

        next(None, event).value shouldEqual nextState
        next(Some(nextState), event) shouldEqual None
      }

      "from a new FileUpdated event" in {
        val event = FileUpdated(id, projectRef, storageRef, DiskStorageType, attributes, 2, time2, alice)
        next(None, event) shouldEqual None

        val att     = attributes.copy(bytes = 1)
        val current = FileGen.state(id, projectRef, remoteStorageRef, att, createdBy = bob, updatedBy = bob)

        next(Some(current), event).value shouldEqual
          current.copy(rev = 2, storage = storageRef, attributes = attributes, updatedAt = time2, updatedBy = alice)
      }

      "from a new FileTagAdded event" in {
        val tag1    = UserTag.unsafe("tag1")
        val event   = FileTagAdded(id, projectRef, targetRev = 1, tag1, 3, time2, alice)
        val current = FileGen.state(id, projectRef, storageRef, attributes, tags = Tags(myTag -> 2), rev = 2)

        next(None, event) shouldEqual None

        next(Some(current), event).value shouldEqual
          current.copy(rev = 3, updatedAt = time2, updatedBy = alice, tags = Tags(myTag -> 2, tag1 -> 1))
      }

      "from a new FileDeprecated event" in {
        val event   = FileDeprecated(id, projectRef, 2, time2, alice)
        val current = FileGen.state(id, projectRef, storageRef, attributes)

        next(None, event) shouldEqual None

        next(Some(current), event).value shouldEqual current.copy(
          rev = 2,
          deprecated = true,
          updatedAt = time2,
          updatedBy = alice
        )
      }
    }
  }

}
