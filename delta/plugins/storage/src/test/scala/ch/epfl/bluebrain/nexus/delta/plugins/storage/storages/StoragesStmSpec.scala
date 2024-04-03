package ch.epfl.bluebrain.nexus.delta.plugins.storage.storages

import cats.effect.IO
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.StorageGen.storageState
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.Storages.{evaluate, next}
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.StoragesConfig.{DiskStorageConfig, StorageTypeConfig}
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.StorageCommand.{CreateStorage, DeprecateStorage, UndeprecateStorage, UpdateStorage}
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.StorageEvent._
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.StorageRejection.{DifferentStorageType, IncorrectRev, InvalidMaxFileSize, InvalidStorageType, PermissionsAreNotDefined, ResourceAlreadyExists, StorageIsDeprecated, StorageIsNotDeprecated, StorageNotAccessible, StorageNotFound}
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.StorageType.{DiskStorage => DiskStorageType}
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.StorageValue.{DiskStorageValue, RemoteDiskStorageValue, S3StorageValue}
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.{AbsolutePath, DigestAlgorithm}
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.StorageAccess
import ch.epfl.bluebrain.nexus.delta.sdk.permissions.model.Permission
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.User
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Tag.UserTag
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{Label, ProjectRef}
import ch.epfl.bluebrain.nexus.testkit.scalatest.ce.CatsEffectSpec
import io.circe.Json

import java.nio.file.Files
import java.time.Instant

class StoragesStmSpec extends CatsEffectSpec with StorageFixtures {

  private val epoch = Instant.EPOCH
  private val time2 = Instant.ofEpochMilli(10L)
  private val realm = Label.unsafe("myrealm")
  private val bob   = User("Bob", realm)
  private val alice = User("Alice", realm)

  private val project        = ProjectRef.unsafe("org", "proj")
  private val tmp2           = AbsolutePath("/tmp2").rightValue
  private val accessibleDisk = Set(diskFields.volume.value, tmp2)

  private val access: StorageAccess = {
    case disk: DiskStorageValue         =>
      IO.whenA(!accessibleDisk.contains(disk.volume))(IO.raiseError(StorageNotAccessible("wrong volume")))
    case s3: S3StorageValue             =>
      IO.whenA(s3.bucket != s3Fields.bucket)(IO.raiseError(StorageNotAccessible("wrong bucket")))
    case remote: RemoteDiskStorageValue =>
      IO.whenA(remote.folder != remoteFields.folder)(
        IO.raiseError(StorageNotAccessible("Folder does not exist"))
      )
  }

  private val perms = IO.pure(allowedPerms.toSet)

  private val eval = evaluate(access, perms, config, clock)(_, _)

  "The Storages state machine" when {

    "evaluating an incoming command" should {

      "create a new event from a CreateStorage command" in {
        val disk   = CreateStorage(dId, project, diskFields.copy(maxFileSize = Some(1)), diskFieldsJson, bob)
        val s3     = CreateStorage(s3Id, project, s3Fields.copy(maxFileSize = Some(2)), s3FieldsJson, bob)
        val remote = CreateStorage(rdId, project, remoteFields.copy(maxFileSize = Some(3)), remoteFieldsJson, bob)

        forAll(List(disk, s3, remote)) { cmd =>
          eval(None, cmd).accepted shouldEqual
            StorageCreated(cmd.id, cmd.project, cmd.fields.toValue(config).value, cmd.source, 1, epoch, bob)
        }
      }

      "create a new event from a UpdateStorage command" in {
        val disk          = UpdateStorage(dId, project, diskFields, diskFieldsJson, 1, alice)
        val diskCurrent   = storageState(dId, project, diskVal.copy(maxFileSize = 1))
        val s3            = UpdateStorage(s3Id, project, s3Fields, s3FieldsJson, 1, alice)
        val s3Current     = storageState(s3Id, project, s3Val.copy(maxFileSize = 2))
        val remote        = UpdateStorage(rdId, project, remoteFields, remoteFieldsJson, 1, alice)
        val remoteCurrent = storageState(rdId, project, remoteVal.copy(maxFileSize = 3))
        val list          = List((diskCurrent, disk), (s3Current, s3), (remoteCurrent, remote))

        forAll(list) { case (state, cmd) =>
          eval(Some(state), cmd).accepted shouldEqual
            StorageUpdated(cmd.id, cmd.project, cmd.fields.toValue(config).value, cmd.source, 2, epoch, alice)
        }
      }

      "create a new event from a DeprecateStorage command" in {
        val state = storageState(dId, project, diskVal, rev = 3)
        eval(Some(state), DeprecateStorage(dId, project, 3, alice)).accepted shouldEqual
          StorageDeprecated(dId, project, DiskStorageType, 4, epoch, alice)
      }

      "create a new event from an UndeprecateStorage command" in {
        val state = storageState(dId, project, diskVal, rev = 3, deprecated = true)
        eval(Some(state), UndeprecateStorage(dId, project, 3, alice)).accepted shouldEqual
          StorageUndeprecated(dId, project, DiskStorageType, 4, epoch, alice)
      }

      "reject with IncorrectRev" in {
        val state    = storageState(dId, project, diskVal)
        val commands = List(
          UpdateStorage(dId, project, diskFields, Json.obj(), 2, alice),
          DeprecateStorage(dId, project, 2, alice),
          UndeprecateStorage(dId, project, 2, alice)
        )
        forAll(commands) { cmd =>
          eval(Some(state), cmd).rejected shouldEqual IncorrectRev(provided = 2, expected = 1)
        }
      }

      "reject with StorageNotAccessible" in {
        val notAllowedDiskVal     = diskFields.copy(volume = Some(tmp2))
        val inaccessibleDiskVal   =
          diskFields.copy(volume = Some(AbsolutePath(Files.createTempDirectory("other")).rightValue))
        val inaccessibleS3Val     = s3Fields.copy(bucket = "other")
        val inaccessibleRemoteVal = remoteFields.copy(folder = Label.unsafe("xxx"))
        val diskCurrent           = storageState(dId, project, diskVal)
        val s3Current             = storageState(s3Id, project, s3Val)
        val remoteCurrent         = storageState(rdId, project, remoteVal)

        forAll(
          List(
            dId  -> notAllowedDiskVal,
            dId  -> inaccessibleDiskVal,
            s3Id -> inaccessibleS3Val,
            rdId -> inaccessibleRemoteVal
          )
        ) { case (id, value) =>
          val createCmd = CreateStorage(id, project, value, Json.obj(), bob)
          eval(None, createCmd).rejected shouldBe a[StorageNotAccessible]
        }

        forAll(
          List(
            diskCurrent   -> inaccessibleDiskVal,
            s3Current     -> inaccessibleS3Val,
            remoteCurrent -> inaccessibleRemoteVal
          )
        ) { case (state, value) =>
          val updateCmd = UpdateStorage(state.id, project, value, Json.obj(), 1, alice)
          eval(Some(state), updateCmd).rejected shouldBe a[StorageNotAccessible]
        }
      }

      "reject with InvalidMaxFileSize" in {
        val exceededSizeDiskVal   = diskFields.copy(maxFileSize = Some(100))
        val exceededSizeS3Val     = s3Fields.copy(maxFileSize = Some(100))
        val exceededSizeRemoteVal = remoteFields.copy(maxFileSize = Some(100))
        val diskCurrent           = storageState(dId, project, diskVal)
        val s3Current             = storageState(s3Id, project, s3Val)
        val remoteCurrent         = storageState(rdId, project, remoteVal)

        forAll(
          List(
            (dId, exceededSizeDiskVal, config.disk.defaultMaxFileSize),
            (s3Id, exceededSizeS3Val, config.amazon.value.defaultMaxFileSize),
            (rdId, exceededSizeRemoteVal, config.remoteDisk.value.defaultMaxFileSize)
          )
        ) { case (id, value, maxFileSize) =>
          val createCmd = CreateStorage(id, project, value, Json.obj(), bob)
          eval(None, createCmd).rejected shouldEqual InvalidMaxFileSize(id, 100, maxFileSize)
        }

        forAll(
          List(
            (diskCurrent, exceededSizeDiskVal, config.disk.defaultMaxFileSize),
            (s3Current, exceededSizeS3Val, config.amazon.get.defaultMaxFileSize),
            (remoteCurrent, exceededSizeRemoteVal, config.remoteDisk.value.defaultMaxFileSize)
          )
        ) { case (state, value, maxFileSize) =>
          val updateCmd = UpdateStorage(state.id, project, value, Json.obj(), 1, alice)
          eval(Some(state), updateCmd).rejected shouldEqual InvalidMaxFileSize(state.id, 100, maxFileSize)
        }
      }

      "reject with ResourceAlreadyExists when storage already exists" in {
        val state = storageState(dId, project, diskVal)
        eval(Some(state), CreateStorage(dId, project, diskFields, Json.obj(), bob))
          .rejectedWith[ResourceAlreadyExists]
      }

      "reject with StorageNotFound" in {
        val commands = List(
          UpdateStorage(dId, project, diskFields, Json.obj(), 2, alice),
          DeprecateStorage(dId, project, 2, alice)
        )
        forAll(commands) { cmd =>
          eval(None, cmd).rejectedWith[StorageNotFound]
        }
      }

      "reject with StorageIsDeprecated" in {
        val state    = storageState(dId, project, diskVal, rev = 2, deprecated = true)
        val commands = List(
          UpdateStorage(dId, project, diskFields, Json.obj(), 2, alice),
          DeprecateStorage(dId, project, 2, alice)
        )
        forAll(commands) { cmd =>
          eval(Some(state), cmd).rejectedWith[StorageIsDeprecated]
        }
      }

      "reject with StorageIsNotDeprecated" in {
        val state    = storageState(dId, project, diskVal, rev = 2, deprecated = false)
        val commands = List(
          UndeprecateStorage(dId, project, 2, alice)
        )
        forAll(commands) { cmd =>
          eval(Some(state), cmd).rejectedWith[StorageIsNotDeprecated]
        }
      }

      "reject with DifferentStorageType" in {
        val diskCurrent   = storageState(dId, project, diskVal)
        val s3Current     = storageState(s3Id, project, s3Val)
        val remoteCurrent = storageState(rdId, project, remoteVal)
        val list          = List(
          diskCurrent   -> UpdateStorage(dId, project, s3Fields, Json.obj(), 1, alice),
          diskCurrent   -> UpdateStorage(dId, project, remoteFields, Json.obj(), 1, alice),
          s3Current     -> UpdateStorage(s3Id, project, diskFields, Json.obj(), 1, alice),
          s3Current     -> UpdateStorage(s3Id, project, remoteFields, Json.obj(), 1, alice),
          remoteCurrent -> UpdateStorage(rdId, project, diskFields, Json.obj(), 1, alice),
          remoteCurrent -> UpdateStorage(rdId, project, s3Fields, Json.obj(), 1, alice)
        )
        forAll(list) { case (state, cmd) =>
          eval(Some(state), cmd).rejectedWith[DifferentStorageType]
        }
      }
    }

    "reject with PermissionsAreNotDefined" in {
      val read         = Permission.unsafe("wrong/read")
      val write        = Permission.unsafe("wrong/write")
      val storageValue = s3Fields.copy(readPermission = Some(read), writePermission = Some(write))
      val current      = storageState(s3Id, project, s3Val)
      val list         = List(
        None          -> CreateStorage(s3Id, project, storageValue, Json.obj(), bob),
        Some(current) -> UpdateStorage(s3Id, project, storageValue, Json.obj(), 1, alice)
      )
      forAll(list) { case (current, cmd) =>
        eval(current, cmd).rejected shouldEqual PermissionsAreNotDefined(Set(read, write))
      }
    }

    "reject with InvalidStorageType" in {
      val s3Current                 = storageState(s3Id, project, s3Val)
      val remoteCurrent             = storageState(rdId, project, remoteVal)
      val list                      = List(
        None                -> CreateStorage(s3Id, project, s3Fields, Json.obj(), bob),
        None                -> CreateStorage(s3Id, project, remoteFields, Json.obj(), bob),
        Some(s3Current)     -> UpdateStorage(s3Id, project, s3Fields, Json.obj(), 1, alice),
        Some(remoteCurrent) -> UpdateStorage(rdId, project, remoteFields, Json.obj(), 1, alice)
      )
      val diskVolume                = AbsolutePath(Files.createTempDirectory("disk")).rightValue
      // format: off
      val config: StorageTypeConfig = StorageTypeConfig(
        disk        = DiskStorageConfig(diskVolume, Set(diskVolume), DigestAlgorithm.default, permissions.read, permissions.write, showLocation = false, Some(1000), 150),
        amazon      = None,
        remoteDisk  = None
      )
      // format: on
      val eval                      = evaluate(access, perms, config, clock)(_, _)
      forAll(list) { case (current, cmd) =>
        eval(current, cmd).rejectedWith[InvalidStorageType]
      }
    }

    "producing next state" should {

      "from a new StorageCreated event" in {
        val event     = StorageCreated(dId, project, diskVal, diskFieldsJson, 1, epoch, bob)
        val nextState = storageState(dId, project, diskVal, diskFieldsJson, createdBy = bob, updatedBy = bob)

        next(None, event).value shouldEqual nextState
        next(Some(nextState), event) shouldEqual None
      }

      "from a new StorageUpdated event" in {
        val event = StorageUpdated(dId, project, diskVal, diskFieldsJson, 2, time2, alice)
        next(None, event) shouldEqual None

        val currentDisk = diskVal.copy(maxFileSize = 2)
        val current     = storageState(dId, project, currentDisk, createdBy = bob, updatedBy = bob)

        next(Some(current), event).value shouldEqual
          current.copy(rev = 2, value = diskVal, source = diskFieldsJson, updatedAt = time2, updatedBy = alice)
      }

      "from a new StorageTagAdded event, only the revision is updated" in {
        val event   = StorageTagAdded(dId, project, DiskStorageType, 1, UserTag.unsafe("unused"), 3, time2, alice)
        val current = storageState(dId, project, diskVal, rev = 2)

        next(None, event) shouldEqual None

        next(Some(current), event).value shouldEqual
          current.copy(rev = 3, updatedAt = time2, updatedBy = alice)
      }

      "from a new StorageDeprecated event" in {
        val event   = StorageDeprecated(dId, project, DiskStorageType, 2, time2, alice)
        val current = storageState(dId, project, diskVal)

        next(None, event) shouldEqual None

        next(Some(current), event).value shouldEqual current.copy(
          rev = 2,
          deprecated = true,
          updatedAt = time2,
          updatedBy = alice
        )
      }

      "from a new StorageUndeprecated event" in {
        val event   = StorageUndeprecated(dId, project, DiskStorageType, 2, time2, alice)
        val current = storageState(dId, project, diskVal, deprecated = true)

        next(None, event) shouldEqual None

        next(Some(current), event).value shouldEqual current.copy(
          rev = 2,
          deprecated = false,
          updatedAt = time2,
          updatedBy = alice
        )
      }
    }
  }

}
