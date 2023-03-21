package ch.epfl.bluebrain.nexus.delta.plugins.storage.storages

import ch.epfl.bluebrain.nexus.delta.kernel.Secret
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.StorageType.{DiskStorage => DiskStorageType}
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.StorageGen.storageState
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.Storages.{evaluate, next}
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.StoragesConfig.{DiskStorageConfig, StorageTypeConfig}
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.{AbsolutePath, DigestAlgorithm}
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.StorageCommand.{CreateStorage, DeprecateStorage, TagStorage, UpdateStorage}
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.StorageEvent.{StorageCreated, StorageDeprecated, StorageTagAdded, StorageUpdated}
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.StorageRejection.{DifferentStorageType, IncorrectRev, InvalidMaxFileSize, InvalidStorageType, PermissionsAreNotDefined, ResourceAlreadyExists, RevisionNotFound, StorageIsDeprecated, StorageNotAccessible, StorageNotFound}
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.StorageValue.{DiskStorageValue, RemoteDiskStorageValue, S3StorageValue}
import ch.epfl.bluebrain.nexus.delta.sdk.model.{BaseUri, Tags}
import ch.epfl.bluebrain.nexus.delta.sdk.permissions.model.Permission
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.User
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{Label, ProjectRef}
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Tag.UserTag
import ch.epfl.bluebrain.nexus.testkit.{EitherValuable, IOFixedClock, IOValues}
import io.circe.Json
import monix.bio.IO
import org.scalatest.{Inspectors, OptionValues}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

import java.nio.file.Files
import java.time.Instant

class StoragesStmSpec
    extends AnyWordSpecLike
    with Matchers
    with IOFixedClock
    with OptionValues
    with EitherValuable
    with Inspectors
    with IOValues
    with StorageFixtures {

  private val epoch = Instant.EPOCH
  private val time2 = Instant.ofEpochMilli(10L)
  private val realm = Label.unsafe("myrealm")
  private val bob   = User("Bob", realm)
  private val alice = User("Alice", realm)

  private val project        = ProjectRef.unsafe("org", "proj")
  private val tmp2           = AbsolutePath("/tmp2").rightValue
  private val accessibleDisk = Set(diskFields.volume.value, tmp2)

  private val access: Storages.StorageAccess = {
    case (id, disk: DiskStorageValue)         =>
      IO.when(!accessibleDisk.contains(disk.volume))(IO.raiseError(StorageNotAccessible(id, "wrong volume")))
    case (id, s3: S3StorageValue)             =>
      IO.when(s3.bucket != s3Fields.bucket)(IO.raiseError(StorageNotAccessible(id, "wrong bucket")))
    case (id, remote: RemoteDiskStorageValue) =>
      IO.when(remote.endpoint != remoteFields.endpoint.value)(IO.raiseError(StorageNotAccessible(id, "wrong endpoint")))
  }

  private val perms = IO.pure(allowedPerms.toSet)

  private val eval = evaluate(access, perms, config, crypto)(_, _)

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

      "create a new event from a TagStorage command" in {
        val state = storageState(dId, project, diskVal, rev = 3)
        eval(Some(state), TagStorage(dId, project, 2, UserTag.unsafe("myTag"), 3, alice)).accepted shouldEqual
          StorageTagAdded(dId, project, DiskStorageType, 2, UserTag.unsafe("myTag"), 4, epoch, alice)
      }

      "create a new event from a TagStorage command when storage is deprecated" in {
        val state = storageState(dId, project, diskVal, rev = 3, deprecated = true)
        eval(Some(state), TagStorage(dId, project, 2, UserTag.unsafe("myTag"), 3, alice)).accepted shouldEqual
          StorageTagAdded(dId, project, DiskStorageType, 2, UserTag.unsafe("myTag"), 4, epoch, alice)
      }

      "create a new event from a DeprecateStorage command" in {
        val state = storageState(dId, project, diskVal, rev = 3)
        eval(Some(state), DeprecateStorage(dId, project, 3, alice)).accepted shouldEqual
          StorageDeprecated(dId, project, DiskStorageType, 4, epoch, alice)
      }

      "reject with IncorrectRev" in {
        val state    = storageState(dId, project, diskVal)
        val commands = List(
          UpdateStorage(dId, project, diskFields, Secret(Json.obj()), 2, alice),
          TagStorage(dId, project, 1, UserTag.unsafe("tag"), 2, alice),
          DeprecateStorage(dId, project, 2, alice)
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
        val inaccessibleRemoteVal = remoteFields.copy(endpoint = Some(BaseUri.withoutPrefix("other.com")))
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
          val createCmd = CreateStorage(id, project, value, Secret(Json.obj()), bob)
          eval(None, createCmd).rejected shouldBe a[StorageNotAccessible]
        }

        forAll(
          List(
            diskCurrent   -> inaccessibleDiskVal,
            s3Current     -> inaccessibleS3Val,
            remoteCurrent -> inaccessibleRemoteVal
          )
        ) { case (state, value) =>
          val updateCmd = UpdateStorage(state.id, project, value, Secret(Json.obj()), 1, alice)
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
          val createCmd = CreateStorage(id, project, value, Secret(Json.obj()), bob)
          eval(None, createCmd).rejected shouldEqual InvalidMaxFileSize(id, 100, maxFileSize)
        }

        forAll(
          List(
            (diskCurrent, exceededSizeDiskVal, config.disk.defaultMaxFileSize),
            (s3Current, exceededSizeS3Val, config.amazon.get.defaultMaxFileSize),
            (remoteCurrent, exceededSizeRemoteVal, config.remoteDisk.value.defaultMaxFileSize)
          )
        ) { case (state, value, maxFileSize) =>
          val updateCmd = UpdateStorage(state.id, project, value, Secret(Json.obj()), 1, alice)
          eval(Some(state), updateCmd).rejected shouldEqual InvalidMaxFileSize(state.id, 100, maxFileSize)
        }
      }

      "reject with ResourceAlreadyExists when storage already exists" in {
        val state = storageState(dId, project, diskVal)
        eval(Some(state), CreateStorage(dId, project, diskFields, Secret(Json.obj()), bob))
          .rejectedWith[ResourceAlreadyExists]
      }

      "reject with StorageNotFound" in {
        val commands = List(
          UpdateStorage(dId, project, diskFields, Secret(Json.obj()), 2, alice),
          TagStorage(dId, project, 1, UserTag.unsafe("tag"), 2, alice),
          DeprecateStorage(dId, project, 2, alice)
        )
        forAll(commands) { cmd =>
          eval(None, cmd).rejectedWith[StorageNotFound]
        }
      }

      "reject with StorageIsDeprecated" in {
        val state    = storageState(dId, project, diskVal, rev = 2, deprecated = true)
        val commands = List(
          UpdateStorage(dId, project, diskFields, Secret(Json.obj()), 2, alice),
          DeprecateStorage(dId, project, 2, alice)
        )
        forAll(commands) { cmd =>
          eval(Some(state), cmd).rejectedWith[StorageIsDeprecated]
        }
      }

      "reject with RevisionNotFound" in {
        val state = storageState(dId, project, diskVal)
        eval(Some(state), TagStorage(dId, project, 3, UserTag.unsafe("myTag"), 1, alice)).rejected shouldEqual
          RevisionNotFound(provided = 3, current = 1)
      }

      "reject with DifferentStorageType" in {
        val diskCurrent   = storageState(dId, project, diskVal)
        val s3Current     = storageState(s3Id, project, s3Val)
        val remoteCurrent = storageState(rdId, project, remoteVal)
        val list          = List(
          diskCurrent   -> UpdateStorage(dId, project, s3Fields, Secret(Json.obj()), 1, alice),
          diskCurrent   -> UpdateStorage(dId, project, remoteFields, Secret(Json.obj()), 1, alice),
          s3Current     -> UpdateStorage(s3Id, project, diskFields, Secret(Json.obj()), 1, alice),
          s3Current     -> UpdateStorage(s3Id, project, remoteFields, Secret(Json.obj()), 1, alice),
          remoteCurrent -> UpdateStorage(rdId, project, diskFields, Secret(Json.obj()), 1, alice),
          remoteCurrent -> UpdateStorage(rdId, project, s3Fields, Secret(Json.obj()), 1, alice)
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
        None          -> CreateStorage(s3Id, project, storageValue, Secret(Json.obj()), bob),
        Some(current) -> UpdateStorage(s3Id, project, storageValue, Secret(Json.obj()), 1, alice)
      )
      forAll(list) { case (current, cmd) =>
        eval(current, cmd).rejected shouldEqual PermissionsAreNotDefined(Set(read, write))
      }
    }

    "reject with InvalidStorageType" in {
      val s3Current                 = storageState(s3Id, project, s3Val)
      val remoteCurrent             = storageState(rdId, project, remoteVal)
      val list                      = List(
        None                -> CreateStorage(s3Id, project, s3Fields, Secret(Json.obj()), bob),
        None                -> CreateStorage(s3Id, project, remoteFields, Secret(Json.obj()), bob),
        Some(s3Current)     -> UpdateStorage(s3Id, project, s3Fields, Secret(Json.obj()), 1, alice),
        Some(remoteCurrent) -> UpdateStorage(rdId, project, remoteFields, Secret(Json.obj()), 1, alice)
      )
      val diskVolume                = AbsolutePath(Files.createTempDirectory("disk")).rightValue
      // format: off
      val config: StorageTypeConfig = StorageTypeConfig(
        disk        = DiskStorageConfig(diskVolume, Set(diskVolume), DigestAlgorithm.default, permissions.read, permissions.write, showLocation = false, Some(1000), 150),
        amazon      = None,
        remoteDisk  = None
      )
      // format: on
      val eval                      = evaluate(access, perms, config, crypto)(_, _)
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

      "from a new StorageTagAdded event" in {
        val tag1    = UserTag.unsafe("tag1")
        val tag2    = UserTag.unsafe("tag2")
        val event   = StorageTagAdded(dId, project, DiskStorageType, 1, tag2, 3, time2, alice)
        val current = storageState(dId, project, diskVal, tags = Tags(tag1 -> 2), rev = 2)

        next(None, event) shouldEqual None

        next(Some(current), event).value shouldEqual
          current.copy(rev = 3, updatedAt = time2, updatedBy = alice, tags = Tags(tag1 -> 2, tag2 -> 1))
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
    }
  }

}
