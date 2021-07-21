package ch.epfl.bluebrain.nexus.delta.plugins.storage.files

import akka.http.scaladsl.model.ContentTypes.`text/plain(UTF-8)`
import akka.http.scaladsl.model.{ContentTypes, Uri}
import akka.persistence.query.{NoOffset, Sequence}
import ch.epfl.bluebrain.nexus.delta.kernel.Secret
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.Files.{evaluate, next}
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model.Digest.{ComputedDigest, NotComputedDigest}
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model.FileAttributes
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model.FileAttributes.FileAttributesOrigin.{Client, Storage}
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model.FileCommand._
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model.FileEvent._
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model.FileRejection._
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model.FileState._
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.StorageFixtures
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.DigestAlgorithm
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.StorageRejection.StorageNotFound
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.StorageType.{DiskStorage => DiskStorageType, RemoteDiskStorage => RemoteStorageType}
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.AkkaSourceHelpers
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.remote.RemoteStorageDocker.{BucketName, RemoteStorageEndpoint}
import ch.epfl.bluebrain.nexus.delta.plugins.storage.{ConfigFixtures, RemoteContextResolutionFixture}
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.nxv
import ch.epfl.bluebrain.nexus.delta.sdk.Indexing.{Async, Sync}
import ch.epfl.bluebrain.nexus.delta.sdk.Permissions
import ch.epfl.bluebrain.nexus.delta.sdk.model._
import ch.epfl.bluebrain.nexus.delta.sdk.model.acls.{Acl, AclAddress}
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Caller
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Identity.{Anonymous, Authenticated, Group, User}
import ch.epfl.bluebrain.nexus.delta.sdk.model.organizations.OrganizationRejection.{OrganizationIsDeprecated, OrganizationNotFound}
import ch.epfl.bluebrain.nexus.delta.sdk.model.permissions.Permission
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.ProjectRef
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.ProjectRejection.{ProjectIsDeprecated, ProjectNotFound}
import ch.epfl.bluebrain.nexus.delta.sdk.syntax._
import ch.epfl.bluebrain.nexus.delta.sdk.testkit._
import ch.epfl.bluebrain.nexus.testkit.{CirceLiteral, IOFixedClock, IOValues}
import monix.bio.IO
import monix.execution.Scheduler
import org.scalatest.concurrent.Eventually
import org.scalatest.matchers.should.Matchers
import org.scalatest.{DoNotDiscover, Inspectors}

import java.time.Instant

@DoNotDiscover
class FilesSpec
    extends AbstractDBSpec
    with Matchers
    with IOValues
    with IOFixedClock
    with Inspectors
    with CirceLiteral
    with ConfigFixtures
    with StorageFixtures
    with AkkaSourceHelpers
    with RemoteContextResolutionFixture
    with FileFixtures
    with Eventually {
  implicit private val sc: Scheduler = Scheduler.global

  private val epoch = Instant.EPOCH
  private val time2 = Instant.ofEpochMilli(10L)
  private val realm = Label.unsafe("myrealm")
  private val bob   = User("Bob", realm)
  private val alice = User("Alice", realm)

  private val id               = nxv + "file"
  private val myTag            = TagLabel.unsafe("myTag")
  private val mediaType        = Some(ContentTypes.`text/plain(UTF-8)`)
  private val dig              = ComputedDigest(DigestAlgorithm.default, "something")
  private val storageRef       = ResourceRef.Revision(nxv + "disk?rev=1", nxv + "disk", 1L)
  private val remoteStorageRef = ResourceRef.Revision(nxv + "remote?rev=1", nxv + "remote", 1L)
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
    val eval = evaluate((_, _) => IO.unit)(_, _)
    "evaluating an incoming command" should {

      "create a new event from a CreateFile command" in {
        val createCmd = CreateFile(id, projectRef, storageRef, DiskStorageType, attributes, bob)

        eval(Initial, createCmd).accepted shouldEqual
          FileCreated(id, projectRef, storageRef, DiskStorageType, attributes, 1, epoch, bob)
      }

      "create a new event from a UpdateFile command" in {
        val updateCmd = UpdateFile(id, projectRef, storageRef, DiskStorageType, attributes, 1, alice)
        val current   =
          FileGen.currentState(id, projectRef, remoteStorageRef, attributes.copy(bytes = 1), RemoteStorageType)

        eval(current, updateCmd).accepted shouldEqual
          FileUpdated(id, projectRef, storageRef, DiskStorageType, attributes, 2, epoch, alice)
      }

      "create a new event from a UpdateFileAttributes command" in {
        val updateAttrCmd = UpdateFileAttributes(id, projectRef, mediaType, 10, dig, 1, alice)
        val current       = FileGen.currentState(id, projectRef, remoteStorageRef, attributes.copy(bytes = 1))

        eval(current, updateAttrCmd).accepted shouldEqual
          FileAttributesUpdated(id, projectRef, mediaType, 10, dig, 2, epoch, alice)
      }

      "create a new event from a TagFile command" in {
        val current = FileGen.currentState(id, projectRef, storageRef, attributes, rev = 2)
        eval(current, TagFile(id, projectRef, targetRev = 2, myTag, 2, alice)).accepted shouldEqual
          FileTagAdded(id, projectRef, targetRev = 2, myTag, 3, epoch, alice)
      }

      "create a new event from a DeprecateFile command" in {
        val current = FileGen.currentState(id, projectRef, storageRef, attributes, rev = 2)
        eval(current, DeprecateFile(id, projectRef, 2, alice)).accepted shouldEqual
          FileDeprecated(id, projectRef, 3, epoch, alice)
      }

      "reject with IncorrectRev" in {
        val current  = FileGen.currentState(id, projectRef, storageRef, attributes)
        val commands = List(
          UpdateFile(id, projectRef, storageRef, DiskStorageType, attributes, 2, alice),
          UpdateFileAttributes(id, projectRef, mediaType, 10, dig, 2, alice),
          TagFile(id, projectRef, targetRev = 1, myTag, 2, alice),
          DeprecateFile(id, projectRef, 2, alice)
        )
        forAll(commands) { cmd =>
          eval(current, cmd).rejected shouldEqual IncorrectRev(provided = 2, expected = 1)
        }
      }

      "reject with ResourceAlreadyExists when file already exists" in {
        val current = FileGen.currentState(id, projectRef, storageRef, attributes)
        eval(current, CreateFile(id, projectRef, storageRef, DiskStorageType, attributes, bob))
          .rejectedWith[ResourceAlreadyExists]
      }

      "reject with ResourceAlreadyExists" in {
        val command = CreateFile(id, projectRef, storageRef, DiskStorageType, attributes, bob)
        val eval    = evaluate((project, id) => IO.raiseError(ResourceAlreadyExists(id, project)))(_, _)
        eval(Initial, command).rejected shouldEqual ResourceAlreadyExists(command.id, command.project)
      }

      "reject with FileNotFound" in {
        val commands = List(
          UpdateFile(id, projectRef, storageRef, DiskStorageType, attributes, 2, alice),
          UpdateFileAttributes(id, projectRef, mediaType, 10, dig, 2, alice),
          TagFile(id, projectRef, targetRev = 1, myTag, 2, alice),
          DeprecateFile(id, projectRef, 2, alice)
        )
        forAll(commands) { cmd =>
          eval(Initial, cmd).rejectedWith[FileNotFound]
        }
      }

      "reject with FileIsDeprecated" in {
        val current  = FileGen.currentState(id, projectRef, storageRef, attributes, rev = 2, deprecated = true)
        val commands = List(
          UpdateFile(id, projectRef, storageRef, DiskStorageType, attributes, 2, alice),
          UpdateFileAttributes(id, projectRef, mediaType, 10, dig, 2, alice),
          TagFile(id, projectRef, targetRev = 1, myTag, 2, alice),
          DeprecateFile(id, projectRef, 2, alice)
        )
        forAll(commands) { cmd =>
          eval(current, cmd).rejectedWith[FileIsDeprecated]
        }
      }

      "reject with RevisionNotFound" in {
        val current = FileGen.currentState(id, projectRef, storageRef, attributes)
        eval(current, TagFile(id, projectRef, targetRev = 3, myTag, 1, alice)).rejected shouldEqual
          RevisionNotFound(provided = 3, current = 1)
      }

      "reject with DigestNotComputed" in {
        val current = FileGen.currentState(id, projectRef, storageRef, attributes.copy(digest = NotComputedDigest))
        val cmd     = UpdateFile(id, projectRef, storageRef, DiskStorageType, attributes, 1, alice)
        eval(current, cmd).rejected shouldEqual DigestNotComputed(id)
      }

    }

    "producing next state" should {

      "from a new FileCreated event" in {
        val event     = FileCreated(id, projectRef, storageRef, DiskStorageType, attributes, 1, epoch, bob)
        val nextState = FileGen.currentState(id, projectRef, storageRef, attributes, createdBy = bob, updatedBy = bob)

        next(Initial, event) shouldEqual nextState
        next(nextState, event) shouldEqual nextState
      }

      "from a new FileUpdated event" in {
        val event = FileUpdated(id, projectRef, storageRef, DiskStorageType, attributes, 2, time2, alice)
        next(Initial, event) shouldEqual Initial

        val att     = attributes.copy(bytes = 1)
        val current = FileGen.currentState(id, projectRef, remoteStorageRef, att, createdBy = bob, updatedBy = bob)

        next(current, event) shouldEqual
          current.copy(rev = 2L, storage = storageRef, attributes = attributes, updatedAt = time2, updatedBy = alice)
      }

      "from a new FileTagAdded event" in {
        val tag1    = TagLabel.unsafe("tag1")
        val event   = FileTagAdded(id, projectRef, targetRev = 1, tag1, 3, time2, alice)
        val current = FileGen.currentState(id, projectRef, storageRef, attributes, tags = Map(myTag -> 2), rev = 2)

        next(Initial, event) shouldEqual Initial

        next(current, event) shouldEqual
          current.copy(rev = 3, updatedAt = time2, updatedBy = alice, tags = Map(myTag -> 2, tag1 -> 1))
      }

      "from a new FileDeprecated event" in {
        val event   = FileDeprecated(id, projectRef, 2, time2, alice)
        val current = FileGen.currentState(id, projectRef, storageRef, attributes)

        next(Initial, event) shouldEqual Initial

        next(current, event) shouldEqual current.copy(rev = 2, deprecated = true, updatedAt = time2, updatedBy = alice)
      }
    }
  }

  "The Files operations bundle" when {
    implicit val caller: Caller   = Caller(bob, Set(bob, Group("mygroup", realm), Authenticated(realm)))
    implicit val baseUri: BaseUri = BaseUri("http://localhost", Label.unsafe("v1"))

    val tag        = TagLabel.unsafe("tag")
    val otherRead  = Permission.unsafe("other/read")
    val otherWrite = Permission.unsafe("other/write")

    val allowedPerms = Set(
      diskFields.readPermission.value,
      diskFields.writePermission.value,
      otherRead,
      otherWrite
    )

    val remoteId  = nxv + "remote"
    val remoteRev = ResourceRef.Revision(iri"$remoteId?rev=1", remoteId, 1)

    val (orgs, projs) =
      ProjectSetup
        .init(
          orgsToCreate = org :: orgDeprecated :: Nil,
          projectsToCreate = project :: deprecatedProject :: projectWithDeprecatedOrg :: Nil,
          projectsToDeprecate = deprecatedProject.ref :: Nil,
          organizationsToDeprecate = orgDeprecated :: Nil
        )
        .accepted

    val acls = AclSetup
      .init(
        (Anonymous, AclAddress.Root, Set(Permissions.resources.read)),
        (bob, AclAddress.Project(projectRef), Set(diskFields.readPermission.value, diskFields.writePermission.value)),
        (alice, AclAddress.Project(projectRef), Set(otherRead, otherWrite))
      )
      .accepted

    val cfg             = config.copy(
      disk = config.disk.copy(defaultMaxFileSize = 500, allowedVolumes = config.disk.allowedVolumes + path),
      remoteDisk = Some(config.remoteDisk.value.copy(defaultMaxFileSize = 500))
    )
    val consistentWrite = IndexingActionDummy()

    val (files, storages) = FilesSetup.init(orgs, projs, acls, cfg, consistentWrite, allowedPerms.toSeq: _*)

    "creating a file" should {

      "create storages for files" in {
        val payload = diskFieldsJson.map(_ deepMerge json"""{"maxFileSize": 300, "volume": "$path"}""")
        storages.create(diskId, projectRef, payload, Async).accepted

        val payload2 =
          json"""{"@type": "RemoteDiskStorage", "endpoint": "$RemoteStorageEndpoint", "folder": "$BucketName", "readPermission": "$otherRead", "writePermission": "$otherWrite", "maxFileSize": 300, "default": false}"""
        storages.create(remoteId, projectRef, Secret(payload2), Async).accepted
      }

      "succeed with the id passed" in {
        files
          .create("file1", Some(diskId), projectRef, entity("myfile.txt"), Sync)
          .accepted shouldEqual
          FileGen.resourceFor(file1, projectRef, diskRev, attributes("myfile.txt"), createdBy = bob, updatedBy = bob)
        consistentWrite.valueFor(projectRef, file1, 1L).accepted.value shouldEqual Sync

      }

      "succeed with randomly generated id" in {
        files.create(None, projectRef, entity("myfile2.txt"), Async).accepted shouldEqual
          FileGen.resourceFor(
            generatedId,
            projectRef,
            diskRev,
            attributes("myfile2.txt"),
            createdBy = bob,
            updatedBy = bob
          )
        consistentWrite.valueFor(projectRef, generatedId, 1L).accepted.value shouldEqual Async

      }

      "reject if no write permissions" in {
        files
          .create("file2", Some(remoteId), projectRef, entity(), Async)
          .rejectedWith[AuthorizationFailed]
      }

      "reject if file id already exists" in {
        files.create("file1", None, projectRef, entity(), Async).rejected shouldEqual
          ResourceAlreadyExists(file1, projectRef)
      }

      "reject if storage does not exist" in {
        val storage = nxv + "other-storage"
        files.create("file2", Some(storage), projectRef, entity(), Async).rejected shouldEqual
          WrappedStorageRejection(StorageNotFound(storage, projectRef))
      }

      "reject if project does not exist" in {
        val projectRef = ProjectRef(org, Label.unsafe("other"))
        files.create(None, projectRef, entity(), Async).rejected shouldEqual
          WrappedProjectRejection(ProjectNotFound(projectRef))
      }

      "reject if project is deprecated" in {
        files.create(Some(diskId), deprecatedProject.ref, entity(), Async).rejected shouldEqual
          WrappedProjectRejection(ProjectIsDeprecated(deprecatedProject.ref))
      }

      "reject if organization is deprecated" in {
        files.create(None, projectWithDeprecatedOrg.ref, entity(), Async).rejected shouldEqual
          WrappedOrganizationRejection(OrganizationIsDeprecated(orgDeprecated))
      }
    }

    "linking a file" should {

      "reject if no write permissions" in {
        files
          .createLink("file2", Some(remoteId), projectRef, None, None, Uri.Path.Empty, Async)
          .rejectedWith[AuthorizationFailed]
      }

      "succeed with the id passed" in {
        acls.append(Acl(AclAddress.Root, bob -> Set(otherWrite)), 1).accepted
        val path     = Uri.Path("my/file-3.txt")
        val tempAttr = attributes("myfile.txt").copy(digest = NotComputedDigest)
        val attr     =
          tempAttr.copy(location = s"file:///app/nexustest/nexus/${tempAttr.path}", origin = Storage, mediaType = None)
        files
          .createLink("file2", Some(remoteId), projectRef, Some("myfile.txt"), None, path, Sync)
          .accepted shouldEqual
          FileGen.resourceFor(file2, projectRef, remoteRev, attr, RemoteStorageType, createdBy = bob, updatedBy = bob)
        consistentWrite.valueFor(projectRef, file2, 1L).accepted.value shouldEqual Sync

      }

      "reject if no filename" in {
        files
          .createLink("file3", Some(remoteId), projectRef, None, None, Uri.Path("a/b/"), Async)
          .rejectedWith[InvalidFileLink]
      }

      "reject if file id already exists" in {
        files
          .createLink("file2", Some(remoteId), projectRef, None, None, Uri.Path.Empty, Async)
          .rejected shouldEqual
          ResourceAlreadyExists(file2, projectRef)
      }

      "reject if storage does not exist" in {
        val storage = nxv + "other-storage"
        files
          .createLink("file3", Some(storage), projectRef, None, None, Uri.Path.Empty, Async)
          .rejected shouldEqual
          WrappedStorageRejection(StorageNotFound(storage, projectRef))
      }

      "reject if project does not exist" in {
        val projectRef = ProjectRef(org, Label.unsafe("other"))
        files.createLink(None, projectRef, None, None, Uri.Path.Empty, Async).rejected shouldEqual
          WrappedProjectRejection(ProjectNotFound(projectRef))
      }

      "reject if project is deprecated" in {
        files
          .createLink(Some(remoteId), deprecatedProject.ref, None, None, Uri.Path.Empty, Async)
          .rejected shouldEqual
          WrappedProjectRejection(ProjectIsDeprecated(deprecatedProject.ref))
      }

      "reject if organization is deprecated" in {
        files
          .createLink(None, projectWithDeprecatedOrg.ref, None, None, Uri.Path.Empty, Async)
          .rejected shouldEqual
          WrappedOrganizationRejection(OrganizationIsDeprecated(orgDeprecated))
      }
    }

    "updating a file" should {

      "succeed" in {
        files.update("file1", None, projectRef, 1, entity(), Sync).accepted shouldEqual
          FileGen.resourceFor(file1, projectRef, diskRev, attributes(), rev = 2, createdBy = bob, updatedBy = bob)
        consistentWrite.valueFor(projectRef, file1, 2L).accepted.value shouldEqual Sync

      }

      "reject if file doesn't exists" in {
        files.update(nxv + "other", None, projectRef, 1, entity(), Async).rejectedWith[FileNotFound]
      }

      "reject if storage does not exist" in {
        val storage = nxv + "other-storage"
        files.update("file1", Some(storage), projectRef, 2, entity(), Async).rejected shouldEqual
          WrappedStorageRejection(StorageNotFound(storage, projectRef))
      }

      "reject if project does not exist" in {
        val projectRef = ProjectRef(org, Label.unsafe("other"))

        files.update(file1, None, projectRef, 2, entity(), Async).rejected shouldEqual
          WrappedProjectRejection(ProjectNotFound(projectRef))
      }

      "reject if project is deprecated" in {
        files.update(file1, None, deprecatedProject.ref, 2, entity(), Async).rejected shouldEqual
          WrappedProjectRejection(ProjectIsDeprecated(deprecatedProject.ref))
      }

      "reject if organization is deprecated" in {
        files.update(file1, None, projectWithDeprecatedOrg.ref, 2, entity(), Async).rejected shouldEqual
          WrappedOrganizationRejection(OrganizationIsDeprecated(orgDeprecated))
      }
    }

    "updating remote disk file attributes" should {

      "reject if digest is already computed" in {
        files.updateAttributes(file1, projectRef, Async).rejectedWith[DigestAlreadyComputed]
      }

      "succeed" in {
        val tempAttr = attributes("myfile.txt")
        val attr     =
          tempAttr.copy(location = s"file:///app/nexustest/nexus/${tempAttr.path}", origin = Storage)
        files.updateAttributes(file2, projectRef, Sync).accepted shouldEqual
          FileGen.resourceFor(
            file2,
            projectRef,
            remoteRev,
            attr,
            RemoteStorageType,
            rev = 2,
            createdBy = bob,
            updatedBy = bob
          )
        consistentWrite.valueFor(projectRef, file2, 2L).accepted.value shouldEqual Sync

      }
    }

    "updating a file linking" should {

      "succeed" in {
        val path     = Uri.Path("my/file-4.txt")
        val tempAttr = attributes("file-4.txt").copy(digest = NotComputedDigest)
        val attr     =
          tempAttr.copy(location = s"file:///app/nexustest/nexus/${tempAttr.path}", origin = Storage)
        files
          .updateLink("file2", Some(remoteId), projectRef, None, Some(`text/plain(UTF-8)`), path, 2, Sync)
          .accepted shouldEqual
          FileGen.resourceFor(
            file2,
            projectRef,
            remoteRev,
            attr,
            RemoteStorageType,
            rev = 3,
            createdBy = bob,
            updatedBy = bob
          )
        consistentWrite.valueFor(projectRef, file2, 3L).accepted.value shouldEqual Sync

      }

      "reject if file doesn't exists" in {
        files
          .updateLink(nxv + "other", None, projectRef, None, None, Uri.Path.Empty, 1, Async)
          .rejectedWith[FileNotFound]
      }

      "reject if digest is not computed" in {
        files
          .updateLink("file2", None, projectRef, None, None, Uri.Path.Empty, 3, Async)
          .rejectedWith[DigestNotComputed]
      }

      "reject if storage does not exist" in {
        val storage = nxv + "other-storage"
        files
          .updateLink("file1", Some(storage), projectRef, None, None, Uri.Path.Empty, 2, Async)
          .rejected shouldEqual
          WrappedStorageRejection(StorageNotFound(storage, projectRef))
      }

      "reject if project does not exist" in {
        val projectRef = ProjectRef(org, Label.unsafe("other"))

        files.updateLink(file1, None, projectRef, None, None, Uri.Path.Empty, 2, Async).rejected shouldEqual
          WrappedProjectRejection(ProjectNotFound(projectRef))
      }

      "reject if project is deprecated" in {
        files
          .updateLink(file1, None, deprecatedProject.ref, None, None, Uri.Path.Empty, 2, Async)
          .rejected shouldEqual
          WrappedProjectRejection(ProjectIsDeprecated(deprecatedProject.ref))
      }

      "reject if organization is deprecated" in {
        files
          .updateLink(file1, None, projectWithDeprecatedOrg.ref, None, None, Uri.Path.Empty, 2, Async)
          .rejected shouldEqual
          WrappedOrganizationRejection(OrganizationIsDeprecated(orgDeprecated))
      }
    }

    "updating file attributes" should {

      val attr = attributes()

      "succeed" in {
        val attr = attributes(size = 20)
        files
          .updateAttributes("file1", projectRef, attr.mediaType, attr.bytes, attr.digest, 2, Sync)
          .accepted shouldEqual
          FileGen.resourceFor(file1, projectRef, diskRev, attr, rev = 3, createdBy = bob, updatedBy = bob)
        consistentWrite.valueFor(projectRef, file1, 3L).accepted.value shouldEqual Sync

      }

      "reject if file doesn't exists" in {
        files
          .updateAttributes(nxv + "other", projectRef, attr.mediaType, attr.bytes, attr.digest, 3, Async)
          .rejectedWith[FileNotFound]
      }

      "reject if project does not exist" in {
        val projectRef = ProjectRef(org, Label.unsafe("other"))

        files
          .updateAttributes("file1", projectRef, attr.mediaType, attr.bytes, attr.digest, 3, Async)
          .rejected shouldEqual
          WrappedProjectRejection(ProjectNotFound(projectRef))
      }

      "reject if project is deprecated" in {
        files
          .updateAttributes("file1", deprecatedProject.ref, attr.mediaType, attr.bytes, attr.digest, 3, Async)
          .rejected shouldEqual
          WrappedProjectRejection(ProjectIsDeprecated(deprecatedProject.ref))
      }

      "reject if organization is deprecated" in {
        files
          .updateAttributes(file1, projectWithDeprecatedOrg.ref, attr.mediaType, attr.bytes, attr.digest, 3, Async)
          .rejected shouldEqual
          WrappedOrganizationRejection(OrganizationIsDeprecated(orgDeprecated))
      }
    }

    "tagging a file" should {

      "succeed" in {
        files.tag(file1, projectRef, tag, tagRev = 1, 3, Sync).accepted shouldEqual
          FileGen.resourceFor(
            file1,
            projectRef,
            diskRev,
            attributes(size = 20),
            rev = 4,
            tags = Map(tag -> 1),
            createdBy = bob,
            updatedBy = bob
          )
        consistentWrite.valueFor(projectRef, file1, 4).accepted.value shouldEqual Sync

      }

      "reject if file doesn't exists" in {
        files.tag(nxv + "other", projectRef, tag, tagRev = 1, 4, Async).rejectedWith[FileNotFound]
      }

      "reject if project does not exist" in {
        val projectRef = ProjectRef(org, Label.unsafe("other"))

        files.tag(rdId, projectRef, tag, tagRev = 2, 4, Async).rejected shouldEqual
          WrappedProjectRejection(ProjectNotFound(projectRef))
      }

      "reject if project is deprecated" in {
        files.tag(rdId, deprecatedProject.ref, tag, tagRev = 2, 4, Async).rejected shouldEqual
          WrappedProjectRejection(ProjectIsDeprecated(deprecatedProject.ref))
      }

      "reject if organization is deprecated" in {
        files.tag(rdId, projectWithDeprecatedOrg.ref, tag, tagRev = 2, 4, Async).rejected shouldEqual
          WrappedOrganizationRejection(OrganizationIsDeprecated(orgDeprecated))
      }
    }

    "deprecating a file" should {

      "succeed" in {
        files.deprecate(file1, projectRef, 4, Sync).accepted shouldEqual
          FileGen.resourceFor(
            file1,
            projectRef,
            diskRev,
            attributes(size = 20),
            rev = 5,
            tags = Map(tag -> 1),
            deprecated = true,
            createdBy = bob,
            updatedBy = bob
          )
        consistentWrite.valueFor(projectRef, file1, 4L).accepted.value shouldEqual Sync

      }

      "reject if file doesn't exists" in {
        files.deprecate(nxv + "other", projectRef, 1, Async).rejectedWith[FileNotFound]
      }

      "reject if the revision passed is incorrect" in {
        files.deprecate(file1, projectRef, 3, Async).rejected shouldEqual
          IncorrectRev(provided = 3, expected = 5)
      }

      "reject if project does not exist" in {
        val projectRef = ProjectRef(org, Label.unsafe("other"))

        files.deprecate(file1, projectRef, 1, Async).rejected shouldEqual
          WrappedProjectRejection(ProjectNotFound(projectRef))
      }

      "reject if project is deprecated" in {
        files.deprecate(file1, deprecatedProject.ref, 1, Async).rejected shouldEqual
          WrappedProjectRejection(ProjectIsDeprecated(deprecatedProject.ref))
      }

      "reject if organization is deprecated" in {
        files.tag(file1, projectWithDeprecatedOrg.ref, tag, 1, 1, Async).rejected shouldEqual
          WrappedOrganizationRejection(OrganizationIsDeprecated(orgDeprecated))
      }

    }

    "fetching a file" should {
      val resourceRev1 =
        FileGen.resourceFor(file1, projectRef, diskRev, attributes("myfile.txt"), createdBy = bob, updatedBy = bob)

      val resourceRev5 = FileGen.resourceFor(
        file1,
        projectRef,
        diskRev,
        attributes(size = 20),
        rev = 5,
        tags = Map(tag -> 1),
        deprecated = true,
        createdBy = bob,
        updatedBy = bob
      )

      "succeed" in {
        files.fetch(file1, projectRef).accepted shouldEqual resourceRev5
      }

      "succeed by tag" in {
        files.fetch(IdSegmentRef(file1, tag), projectRef).accepted shouldEqual resourceRev1
      }

      "succeed by rev" in {
        files.fetch(IdSegmentRef(file1, 5), projectRef).accepted shouldEqual resourceRev5
        files.fetch(IdSegmentRef(file1, 1), projectRef).accepted shouldEqual resourceRev1
      }

      "reject if tag does not exist" in {
        val otherTag = TagLabel.unsafe("other")
        files.fetch(IdSegmentRef(file1, otherTag), projectRef).rejected shouldEqual TagNotFound(otherTag)
      }

      "reject if revision does not exist" in {
        files.fetch(IdSegmentRef(file1, 6), projectRef).rejected shouldEqual
          RevisionNotFound(provided = 6, current = 5)
      }

      "fail if it doesn't exist" in {
        val notFound = nxv + "notFound"
        files.fetch(notFound, projectRef).rejectedWith[FileNotFound]
        files.fetch(IdSegmentRef(notFound, tag), projectRef).rejectedWith[FileNotFound]
        files.fetch(IdSegmentRef(notFound, 2), projectRef).rejectedWith[FileNotFound]
      }

      "reject if project does not exist" in {
        val projectRef = ProjectRef(org, Label.unsafe("other"))
        files.fetch(rdId, projectRef).rejected shouldEqual
          WrappedProjectRejection(ProjectNotFound(projectRef))
      }

    }

    "fetching a file content" should {

      "succeed" in {
        val response = files.fetchContent(file1, projectRef).accepted
        consume(response.content) shouldEqual content
        response.filename shouldEqual "file.txt"
        response.contentType shouldEqual `text/plain(UTF-8)`
      }

      "succeed by tag" in {
        val response = files.fetchContent(IdSegmentRef(file1, tag), projectRef).accepted
        consume(response.content) shouldEqual content
        response.filename shouldEqual "myfile.txt"
        response.contentType shouldEqual `text/plain(UTF-8)`
      }

      "succeed by rev" in {
        val response = files.fetchContent(IdSegmentRef(file1, 1), projectRef).accepted
        consume(response.content) shouldEqual content
        response.filename shouldEqual "myfile.txt"
        response.contentType shouldEqual `text/plain(UTF-8)`
      }

      "reject if tag does not exist" in {
        val otherTag = TagLabel.unsafe("other")
        files.fetchContent(IdSegmentRef(file1, otherTag), projectRef).rejected shouldEqual TagNotFound(otherTag)
      }

      "reject if revision does not exist" in {
        files.fetchContent(IdSegmentRef(file1, 6), projectRef).rejected shouldEqual
          RevisionNotFound(provided = 6, current = 5)
      }

      "fail if it doesn't exist" in {
        val notFound = nxv + "notFound"
        files.fetchContent(notFound, projectRef).rejectedWith[FileNotFound]
        files.fetchContent(IdSegmentRef(notFound, tag), projectRef).rejectedWith[FileNotFound]
        files.fetchContent(IdSegmentRef(notFound, 2), projectRef).rejectedWith[FileNotFound]
      }

      "reject if project does not exist" in {
        val projectRef = ProjectRef(org, Label.unsafe("other"))
        files.fetchContent(rdId, projectRef).rejected shouldEqual
          WrappedProjectRejection(ProjectNotFound(projectRef))
      }

    }

    "fetching SSE" should {
      val allEvents = SSEUtils
        .list(
          file1       -> FileCreated,
          generatedId -> FileCreated,
          file2       -> FileCreated,
          file1       -> FileUpdated,
          file2       -> FileAttributesUpdated,
          file2       -> FileUpdated,
          file1       -> FileAttributesUpdated,
          file1       -> FileTagAdded,
          file1       -> FileDeprecated
        )
        .map { case (iri, tpe, seq) => (iri, tpe, Sequence(seq.value + 2)) } // the first 2 entries are for storages

      "get the different events from start" in eventually {
        val streams = List(
          files.events(NoOffset),
          files.events(org, NoOffset).accepted,
          files.events(projectRef, NoOffset).accepted
        )
        forAll(streams) { stream =>
          val events = stream
            .map { e => (e.event.id, e.eventType, e.offset) }
            .take(allEvents.size.toLong)
            .compile
            .toList

          events.accepted shouldEqual allEvents
        }
      }

      "get the different events from offset 2" in {
        val streams = List(
          files.events(Sequence(4L)),
          files.events(org, Sequence(4L)).accepted,
          files.events(projectRef, Sequence(4L)).accepted
        )
        forAll(streams) { stream =>
          val events = stream
            .map { e => (e.event.id, e.eventType, e.offset) }
            .take((allEvents.size - 2).toLong)
            .compile
            .toList

          events.accepted shouldEqual allEvents.drop(2)
        }
      }

      "reject if project does not exist" in {
        val projectRef = ProjectRef(org, Label.unsafe("other"))
        files.events(projectRef, NoOffset).rejected shouldEqual WrappedProjectRejection(ProjectNotFound(projectRef))
      }

      "reject if organization does not exist" in {
        val org = Label.unsafe("other")
        files.events(org, NoOffset).rejected shouldEqual WrappedOrganizationRejection(OrganizationNotFound(org))
      }
    }
  }

}
