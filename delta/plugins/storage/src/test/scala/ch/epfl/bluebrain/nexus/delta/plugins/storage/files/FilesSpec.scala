package ch.epfl.bluebrain.nexus.delta.plugins.storage.files

import akka.http.scaladsl.model.ContentTypes.`text/plain(UTF-8)`
import akka.http.scaladsl.model.{ContentTypes, Uri}
import akka.persistence.query.{NoOffset, Sequence}
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.Files.{evaluate, next}
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model.Digest.{ComputedDigest, NotComputedDigest}
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model.FileAttributes.FileAttributesOrigin.{Client, Storage}
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model.FileCommand._
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model.FileEvent._
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model.FileRejection._
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model.FileState._
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model.{FileAttributes, FileEvent}
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.StorageRejection.StorageNotFound
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.StorageType.{DiskStorage => DiskStorageType, RemoteDiskStorage => RemoteStorageType}
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.{DigestAlgorithm, Secret, StorageEvent}
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.AkkaSourceHelpers
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.remote.RemoteStorageDocker.{BucketName, RemoteStorageEndpoint}
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.{StorageFixtures, Storages, StoragesConfig}
import ch.epfl.bluebrain.nexus.delta.plugins.storage.{ConfigFixtures, RemoteContextResolutionFixture}
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.nxv
import ch.epfl.bluebrain.nexus.delta.sdk.eventlog.EventLogUtils
import ch.epfl.bluebrain.nexus.delta.sdk.model.IdSegment.{IriSegment, StringSegment}
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
import ch.epfl.bluebrain.nexus.delta.sdk.{Organizations, Permissions, Projects}
import ch.epfl.bluebrain.nexus.sourcing.EventLog
import ch.epfl.bluebrain.nexus.testkit.{CirceLiteral, IOFixedClock, IOValues}
import monix.bio.IO
import monix.execution.Scheduler
import org.scalatest.matchers.should.Matchers
import org.scalatest.{DoNotDiscover, Inspectors}

import java.time.Instant
import scala.concurrent.ExecutionContext

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
    with FileFixtures {
  implicit private val sc: Scheduler = Scheduler.global
  implicit val ec: ExecutionContext  = system.dispatcher

  private val epoch = Instant.EPOCH
  private val time2 = Instant.ofEpochMilli(10L)
  private val realm = Label.unsafe("myrealm")
  private val bob   = User("Bob", realm)
  private val alice = User("Alice", realm)

  private val id               = nxv + "file"
  private val myTag            = TagLabel.unsafe("myTag")
  private val mediaType        = ContentTypes.`text/plain(UTF-8)`
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

    "evaluating an incoming command" should {

      "create a new event from a CreateFile command" in {
        val createCmd = CreateFile(id, projectRef, storageRef, DiskStorageType, attributes, bob)

        evaluate(Initial, createCmd).accepted shouldEqual
          FileCreated(id, projectRef, storageRef, DiskStorageType, attributes, 1, epoch, bob)
      }

      "create a new event from a UpdateFile command" in {
        val updateCmd = UpdateFile(id, projectRef, storageRef, DiskStorageType, attributes, 1, alice)
        val current   =
          FileGen.currentState(id, projectRef, remoteStorageRef, attributes.copy(bytes = 1), RemoteStorageType)

        evaluate(current, updateCmd).accepted shouldEqual
          FileUpdated(id, projectRef, storageRef, DiskStorageType, attributes, 2, epoch, alice)
      }

      "create a new event from a UpdateFileAttributes command" in {
        val updateAttrCmd = UpdateFileAttributes(id, projectRef, mediaType, 10, dig, 1, alice)
        val current       = FileGen.currentState(id, projectRef, remoteStorageRef, attributes.copy(bytes = 1))

        evaluate(current, updateAttrCmd).accepted shouldEqual
          FileAttributesUpdated(id, projectRef, mediaType, 10, dig, 2, epoch, alice)
      }

      "create a new event from a TagFile command" in {
        val current = FileGen.currentState(id, projectRef, storageRef, attributes, rev = 2)
        evaluate(current, TagFile(id, projectRef, targetRev = 2, myTag, 2, alice)).accepted shouldEqual
          FileTagAdded(id, projectRef, targetRev = 2, myTag, 3, epoch, alice)
      }

      "create a new event from a DeprecateFile command" in {
        val current = FileGen.currentState(id, projectRef, storageRef, attributes, rev = 2)
        evaluate(current, DeprecateFile(id, projectRef, 2, alice)).accepted shouldEqual
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
          evaluate(current, cmd).rejected shouldEqual IncorrectRev(provided = 2, expected = 1)
        }
      }

      "reject with FileAlreadyExists" in {
        val current = FileGen.currentState(id, projectRef, storageRef, attributes)
        evaluate(current, CreateFile(id, projectRef, storageRef, DiskStorageType, attributes, bob))
          .rejectedWith[FileAlreadyExists]
      }

      "reject with FileNotFound" in {
        val commands = List(
          UpdateFile(id, projectRef, storageRef, DiskStorageType, attributes, 2, alice),
          UpdateFileAttributes(id, projectRef, mediaType, 10, dig, 2, alice),
          TagFile(id, projectRef, targetRev = 1, myTag, 2, alice),
          DeprecateFile(id, projectRef, 2, alice)
        )
        forAll(commands) { cmd =>
          evaluate(Initial, cmd).rejectedWith[FileNotFound]
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
          evaluate(current, cmd).rejectedWith[FileIsDeprecated]
        }
      }

      "reject with RevisionNotFound" in {
        val current = FileGen.currentState(id, projectRef, storageRef, attributes)
        evaluate(current, TagFile(id, projectRef, targetRev = 3, myTag, 1, alice)).rejected shouldEqual
          RevisionNotFound(provided = 3, current = 1)
      }

      "reject with DigestNotComputed" in {
        val current = FileGen.currentState(id, projectRef, storageRef, attributes.copy(digest = NotComputedDigest))
        val cmd     = UpdateFile(id, projectRef, storageRef, DiskStorageType, attributes, 1, alice)
        evaluate(current, cmd).rejected shouldEqual DigestNotComputed(id)
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

    val filesConfig = FilesConfig(aggregate, indexing)

    def projectSetup =
      ProjectSetup
        .init(
          orgsToCreate = org :: orgDeprecated :: Nil,
          projectsToCreate = project :: deprecatedProject :: projectWithDeprecatedOrg :: Nil,
          projectsToDeprecate = deprecatedProject.ref :: Nil,
          organizationsToDeprecate = orgDeprecated :: Nil
        )

    def aclsSetup = AclSetup
      .init(
        (Anonymous, AclAddress.Root, Set(Permissions.resources.read)),
        (bob, AclAddress.Project(projectRef), Set(diskFields.readPermission.value, diskFields.writePermission.value)),
        (alice, AclAddress.Project(projectRef), Set(otherRead, otherWrite))
      )

    def storagesSetup(orgs: Organizations, projects: Projects) = {
      val cfg           = config.copy(
        disk = config.disk.copy(defaultMaxFileSize = 500, allowedVolumes = config.disk.allowedVolumes + path),
        remoteDisk = Some(config.remoteDisk.value.copy(defaultMaxFileSize = 500))
      )
      val storageConfig = StoragesConfig(aggregate, keyValueStore, pagination, indexing, cfg)
      for {
        eventLog <- EventLog.postgresEventLog[Envelope[StorageEvent]](EventLogUtils.toEnvelope).hideErrors
        perms    <- PermissionsDummy(allowedPerms)
        storages <- Storages(storageConfig, eventLog, perms, orgs, projects)
      } yield storages
    }

    val (files, storages, acls) = (for {
      _                <- IO.delay(beforeAll()).hideErrors
      eventLog         <- EventLog.postgresEventLog[Envelope[FileEvent]](EventLogUtils.toEnvelope).hideErrors
      (orgs, projects) <- projectSetup
      acls             <- aclsSetup
      storages         <- storagesSetup(orgs, projects)
      files            <- Files(filesConfig, eventLog, acls, orgs, projects, storages)
    } yield (files, storages, acls)).accepted

    "creating a file" should {

      "create storages for files" in {
        val payload = diskFieldsJson.map(_ deepMerge json"""{"maxFileSize": 300, "volume": "$path"}""")
        storages.create(IriSegment(diskId), projectRef, payload).accepted

        val payload2 =
          json"""{"@type": "RemoteDiskStorage", "endpoint": "$RemoteStorageEndpoint", "folder": "$BucketName", "readPermission": "$otherRead", "writePermission": "$otherWrite", "maxFileSize": 300, "default": false}"""
        storages.create(IriSegment(remoteId), projectRef, Secret(payload2)).accepted
      }

      "succeed with the id passed" in {
        files
          .create(StringSegment("file1"), Some(IriSegment(diskId)), projectRef, entity("myfile.txt"))
          .accepted shouldEqual
          FileGen.resourceFor(file1, projectRef, diskRev, attributes("myfile.txt"), createdBy = bob, updatedBy = bob)
      }

      "succeed with randomly generated id" in {
        files.create(None, projectRef, entity("myfile2.txt")).accepted shouldEqual
          FileGen.resourceFor(
            generatedId,
            projectRef,
            diskRev,
            attributes("myfile2.txt"),
            createdBy = bob,
            updatedBy = bob
          )
      }

      "reject if no write permissions" in {
        files
          .create(StringSegment("file2"), Some(IriSegment(remoteId)), projectRef, entity())
          .rejectedWith[AuthorizationFailed]
      }

      "reject if file id already exists" in {
        files.create(StringSegment("file1"), None, projectRef, entity()).rejected shouldEqual
          FileAlreadyExists(file1, projectRef)
      }

      "reject if storage does not exist" in {
        val storage = nxv + "other-storage"
        files.create(StringSegment("file2"), Some(IriSegment(storage)), projectRef, entity()).rejected shouldEqual
          WrappedStorageRejection(StorageNotFound(storage, projectRef))
      }

      "reject if project does not exist" in {
        val projectRef = ProjectRef(org, Label.unsafe("other"))
        files.create(None, projectRef, entity()).rejected shouldEqual
          WrappedProjectRejection(ProjectNotFound(projectRef))
      }

      "reject if project is deprecated" in {
        files.create(Some(IriSegment(diskId)), deprecatedProject.ref, entity()).rejected shouldEqual
          WrappedProjectRejection(ProjectIsDeprecated(deprecatedProject.ref))
      }

      "reject if organization is deprecated" in {
        files.create(None, projectWithDeprecatedOrg.ref, entity()).rejected shouldEqual
          WrappedOrganizationRejection(OrganizationIsDeprecated(orgDeprecated))
      }
    }

    "linking a file" should {

      "reject if no write permissions" in {
        files
          .createLink(StringSegment("file2"), Some(IriSegment(remoteId)), projectRef, None, None, Uri.Path.Empty)
          .rejectedWith[AuthorizationFailed]
      }

      "succeed with the id passed" in {
        acls.append(Acl(AclAddress.Root, bob -> Set(otherWrite)), 1).accepted
        val path     = Uri.Path("my/file-3.txt")
        val tempAttr = attributes("myfile.txt").copy(digest = NotComputedDigest)
        val attr     =
          tempAttr.copy(location = s"file:///app/nexustest/nexus/${tempAttr.path}", origin = Storage)
        files
          .createLink(StringSegment("file2"), Some(IriSegment(remoteId)), projectRef, Some("myfile.txt"), None, path)
          .accepted shouldEqual
          FileGen.resourceFor(file2, projectRef, remoteRev, attr, RemoteStorageType, createdBy = bob, updatedBy = bob)
      }

      "reject if no filename" in {
        files
          .createLink(StringSegment("file3"), Some(IriSegment(remoteId)), projectRef, None, None, Uri.Path("a/b/"))
          .rejectedWith[InvalidFileLink]
      }

      "reject if file id already exists" in {
        files
          .createLink(StringSegment("file2"), Some(IriSegment(remoteId)), projectRef, None, None, Uri.Path.Empty)
          .rejected shouldEqual
          FileAlreadyExists(file2, projectRef)
      }

      "reject if storage does not exist" in {
        val storage = nxv + "other-storage"
        files
          .createLink(StringSegment("file3"), Some(IriSegment(storage)), projectRef, None, None, Uri.Path.Empty)
          .rejected shouldEqual
          WrappedStorageRejection(StorageNotFound(storage, projectRef))
      }

      "reject if project does not exist" in {
        val projectRef = ProjectRef(org, Label.unsafe("other"))
        files.createLink(None, projectRef, None, None, Uri.Path.Empty).rejected shouldEqual
          WrappedProjectRejection(ProjectNotFound(projectRef))
      }

      "reject if project is deprecated" in {
        files
          .createLink(Some(IriSegment(remoteId)), deprecatedProject.ref, None, None, Uri.Path.Empty)
          .rejected shouldEqual
          WrappedProjectRejection(ProjectIsDeprecated(deprecatedProject.ref))
      }

      "reject if organization is deprecated" in {
        files.createLink(None, projectWithDeprecatedOrg.ref, None, None, Uri.Path.Empty).rejected shouldEqual
          WrappedOrganizationRejection(OrganizationIsDeprecated(orgDeprecated))
      }
    }

    "updating a file" should {

      "succeed" in {
        files.update(StringSegment("file1"), None, projectRef, 1, entity()).accepted shouldEqual
          FileGen.resourceFor(file1, projectRef, diskRev, attributes(), rev = 2, createdBy = bob, updatedBy = bob)
      }

      "reject if file doesn't exists" in {
        files.update(IriSegment(nxv + "other"), None, projectRef, 1, entity()).rejectedWith[FileNotFound]
      }

      "reject if storage does not exist" in {
        val storage = nxv + "other-storage"
        files.update(StringSegment("file1"), Some(IriSegment(storage)), projectRef, 2, entity()).rejected shouldEqual
          WrappedStorageRejection(StorageNotFound(storage, projectRef))
      }

      "reject if project does not exist" in {
        val projectRef = ProjectRef(org, Label.unsafe("other"))

        files.update(IriSegment(file1), None, projectRef, 2, entity()).rejected shouldEqual
          WrappedProjectRejection(ProjectNotFound(projectRef))
      }

      "reject if project is deprecated" in {
        files.update(IriSegment(file1), None, deprecatedProject.ref, 2, entity()).rejected shouldEqual
          WrappedProjectRejection(ProjectIsDeprecated(deprecatedProject.ref))
      }

      "reject if organization is deprecated" in {
        files.update(IriSegment(file1), None, projectWithDeprecatedOrg.ref, 2, entity()).rejected shouldEqual
          WrappedOrganizationRejection(OrganizationIsDeprecated(orgDeprecated))
      }
    }

    "updating remote disk file attributes" should {

      "reject if digest is already computed" in {
        files.updateAttributes(file1, projectRef).rejectedWith[DigestAlreadyComputed]
      }

      "succeed" in {
        val tempAttr = attributes("myfile.txt")
        val attr     =
          tempAttr.copy(location = s"file:///app/nexustest/nexus/${tempAttr.path}", origin = Storage)
        files.updateAttributes(file2, projectRef).accepted shouldEqual
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
      }
    }

    "updating a file linking" should {

      "succeed" in {
        val path     = Uri.Path("my/file-4.txt")
        val tempAttr = attributes("file-4.txt").copy(digest = NotComputedDigest)
        val attr     =
          tempAttr.copy(location = s"file:///app/nexustest/nexus/${tempAttr.path}", origin = Storage)
        files
          .updateLink(StringSegment("file2"), Some(IriSegment(remoteId)), projectRef, None, None, path, 2)
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
      }

      "reject if file doesn't exists" in {
        files
          .updateLink(IriSegment(nxv + "other"), None, projectRef, None, None, Uri.Path.Empty, 1)
          .rejectedWith[FileNotFound]
      }

      "reject if digest is not computed" in {
        files
          .updateLink(StringSegment("file2"), None, projectRef, None, None, Uri.Path.Empty, 3)
          .rejectedWith[DigestNotComputed]
      }

      "reject if storage does not exist" in {
        val storage = nxv + "other-storage"
        files
          .updateLink(StringSegment("file1"), Some(IriSegment(storage)), projectRef, None, None, Uri.Path.Empty, 2)
          .rejected shouldEqual
          WrappedStorageRejection(StorageNotFound(storage, projectRef))
      }

      "reject if project does not exist" in {
        val projectRef = ProjectRef(org, Label.unsafe("other"))

        files.updateLink(IriSegment(file1), None, projectRef, None, None, Uri.Path.Empty, 2).rejected shouldEqual
          WrappedProjectRejection(ProjectNotFound(projectRef))
      }

      "reject if project is deprecated" in {
        files
          .updateLink(IriSegment(file1), None, deprecatedProject.ref, None, None, Uri.Path.Empty, 2)
          .rejected shouldEqual
          WrappedProjectRejection(ProjectIsDeprecated(deprecatedProject.ref))
      }

      "reject if organization is deprecated" in {
        files
          .updateLink(IriSegment(file1), None, projectWithDeprecatedOrg.ref, None, None, Uri.Path.Empty, 2)
          .rejected shouldEqual
          WrappedOrganizationRejection(OrganizationIsDeprecated(orgDeprecated))
      }
    }

    "updating file attributes" should {

      val attr = attributes()

      "succeed" in {
        val attr = attributes(size = 20)
        files
          .updateAttributes(StringSegment("file1"), projectRef, attr.mediaType, attr.bytes, attr.digest, 2)
          .accepted shouldEqual
          FileGen.resourceFor(file1, projectRef, diskRev, attr, rev = 3, createdBy = bob, updatedBy = bob)
      }

      "reject if file doesn't exists" in {
        files
          .updateAttributes(IriSegment(nxv + "other"), projectRef, attr.mediaType, attr.bytes, attr.digest, 3)
          .rejectedWith[FileNotFound]
      }

      "reject if project does not exist" in {
        val projectRef = ProjectRef(org, Label.unsafe("other"))

        files
          .updateAttributes(StringSegment("file1"), projectRef, attr.mediaType, attr.bytes, attr.digest, 3)
          .rejected shouldEqual
          WrappedProjectRejection(ProjectNotFound(projectRef))
      }

      "reject if project is deprecated" in {
        files
          .updateAttributes(StringSegment("file1"), deprecatedProject.ref, attr.mediaType, attr.bytes, attr.digest, 3)
          .rejected shouldEqual
          WrappedProjectRejection(ProjectIsDeprecated(deprecatedProject.ref))
      }

      "reject if organization is deprecated" in {
        files
          .updateAttributes(IriSegment(file1), projectWithDeprecatedOrg.ref, attr.mediaType, attr.bytes, attr.digest, 3)
          .rejected shouldEqual
          WrappedOrganizationRejection(OrganizationIsDeprecated(orgDeprecated))
      }
    }

    "tagging a file" should {

      "succeed" in {
        files.tag(IriSegment(file1), projectRef, tag, tagRev = 1, 3).accepted shouldEqual
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
      }

      "reject if file doesn't exists" in {
        files.tag(IriSegment(nxv + "other"), projectRef, tag, tagRev = 1, 4).rejectedWith[FileNotFound]
      }

      "reject if project does not exist" in {
        val projectRef = ProjectRef(org, Label.unsafe("other"))

        files.tag(IriSegment(rdId), projectRef, tag, tagRev = 2, 4).rejected shouldEqual
          WrappedProjectRejection(ProjectNotFound(projectRef))
      }

      "reject if project is deprecated" in {
        files.tag(IriSegment(rdId), deprecatedProject.ref, tag, tagRev = 2, 4).rejected shouldEqual
          WrappedProjectRejection(ProjectIsDeprecated(deprecatedProject.ref))
      }

      "reject if organization is deprecated" in {
        files.tag(IriSegment(rdId), projectWithDeprecatedOrg.ref, tag, tagRev = 2, 4).rejected shouldEqual
          WrappedOrganizationRejection(OrganizationIsDeprecated(orgDeprecated))
      }
    }

    "deprecating a file" should {

      "succeed" in {
        files.deprecate(IriSegment(file1), projectRef, 4).accepted shouldEqual
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
      }

      "reject if file doesn't exists" in {
        files.deprecate(IriSegment(nxv + "other"), projectRef, 1).rejectedWith[FileNotFound]
      }

      "reject if the revision passed is incorrect" in {
        files.deprecate(IriSegment(file1), projectRef, 3).rejected shouldEqual
          IncorrectRev(provided = 3, expected = 5)
      }

      "reject if project does not exist" in {
        val projectRef = ProjectRef(org, Label.unsafe("other"))

        files.deprecate(IriSegment(file1), projectRef, 1).rejected shouldEqual
          WrappedProjectRejection(ProjectNotFound(projectRef))
      }

      "reject if project is deprecated" in {
        files.deprecate(IriSegment(file1), deprecatedProject.ref, 1).rejected shouldEqual
          WrappedProjectRejection(ProjectIsDeprecated(deprecatedProject.ref))
      }

      "reject if organization is deprecated" in {
        files.tag(IriSegment(file1), projectWithDeprecatedOrg.ref, tag, 1, 1).rejected shouldEqual
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
        files.fetch(IriSegment(file1), projectRef).accepted shouldEqual resourceRev5
      }

      "succeed by tag" in {
        files.fetchBy(IriSegment(file1), projectRef, tag).accepted shouldEqual resourceRev1
      }

      "succeed by rev" in {
        files.fetchAt(IriSegment(file1), projectRef, 5).accepted shouldEqual resourceRev5
        files.fetchAt(IriSegment(file1), projectRef, 1).accepted shouldEqual resourceRev1
      }

      "reject if tag does not exist" in {
        val otherTag = TagLabel.unsafe("other")
        files.fetchBy(IriSegment(file1), projectRef, otherTag).rejected shouldEqual TagNotFound(otherTag)
      }

      "reject if revision does not exist" in {
        files.fetchAt(IriSegment(file1), projectRef, 6).rejected shouldEqual
          RevisionNotFound(provided = 6, current = 5)
      }

      "fail if it doesn't exist" in {
        val notFound = nxv + "notFound"
        files.fetch(IriSegment(notFound), projectRef).rejectedWith[FileNotFound]
        files.fetchBy(IriSegment(notFound), projectRef, tag).rejectedWith[FileNotFound]
        files.fetchAt(IriSegment(notFound), projectRef, 2L).rejectedWith[FileNotFound]
      }

      "reject if project does not exist" in {
        val projectRef = ProjectRef(org, Label.unsafe("other"))
        files.fetch(IriSegment(rdId), projectRef).rejected shouldEqual
          WrappedProjectRejection(ProjectNotFound(projectRef))
      }

    }

    "fetching a file content" should {

      "succeed" in {
        val response = files.fetchContent(IriSegment(file1), projectRef).accepted
        consume(response.content) shouldEqual content
        response.filename shouldEqual "file.txt"
        response.contentType shouldEqual `text/plain(UTF-8)`
      }

      "succeed by tag" in {
        val response = files.fetchContentBy(IriSegment(file1), projectRef, tag).accepted
        consume(response.content) shouldEqual content
        response.filename shouldEqual "myfile.txt"
        response.contentType shouldEqual `text/plain(UTF-8)`
      }

      "succeed by rev" in {
        val response = files.fetchContentAt(IriSegment(file1), projectRef, 1).accepted
        consume(response.content) shouldEqual content
        response.filename shouldEqual "myfile.txt"
        response.contentType shouldEqual `text/plain(UTF-8)`
      }

      "reject if tag does not exist" in {
        val otherTag = TagLabel.unsafe("other")
        files.fetchContentBy(IriSegment(file1), projectRef, otherTag).rejected shouldEqual TagNotFound(otherTag)
      }

      "reject if revision does not exist" in {
        files.fetchContentAt(IriSegment(file1), projectRef, 6).rejected shouldEqual
          RevisionNotFound(provided = 6, current = 5)
      }

      "fail if it doesn't exist" in {
        val notFound = nxv + "notFound"
        files.fetchContent(IriSegment(notFound), projectRef).rejectedWith[FileNotFound]
        files.fetchContentBy(IriSegment(notFound), projectRef, tag).rejectedWith[FileNotFound]
        files.fetchContentAt(IriSegment(notFound), projectRef, 2L).rejectedWith[FileNotFound]
      }

      "reject if project does not exist" in {
        val projectRef = ProjectRef(org, Label.unsafe("other"))
        files.fetchContent(IriSegment(rdId), projectRef).rejected shouldEqual
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

      "get the different events from start" in {
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
