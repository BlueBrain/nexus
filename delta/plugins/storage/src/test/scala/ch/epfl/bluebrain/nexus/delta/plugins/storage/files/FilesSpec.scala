package ch.epfl.bluebrain.nexus.delta.plugins.storage.files

import akka.actor.typed.scaladsl.adapter._
import akka.actor.{typed, ActorSystem}
import akka.http.scaladsl.model.ContentTypes.`text/plain(UTF-8)`
import akka.http.scaladsl.model.Uri
import akka.testkit.TestKit
import cats.data.NonEmptyList
import cats.effect.IO
import cats.effect.unsafe.implicits.global
import ch.epfl.bluebrain.nexus.delta.kernel.http.MediaTypeDetectorConfig
import ch.epfl.bluebrain.nexus.delta.kernel.utils.TransactionalFileCopier
import ch.epfl.bluebrain.nexus.delta.plugins.storage.RemoteContextResolutionFixture
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model.Digest.NotComputedDigest
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model.FileAttributes.FileAttributesOrigin.Storage
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model.FileRejection._
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model.{CopyFileDestination, FileAttributes, FileId, FileRejection}
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.routes.CopyFileSource
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.StorageRejection.{DifferentStorageType, StorageNotFound}
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.StorageType.{RemoteDiskStorage => RemoteStorageType}
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.{StorageRejection, StorageStatEntry, StorageType}
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.AkkaSourceHelpers
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.remote.client.RemoteDiskStorageClient
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.{StorageFixtures, Storages, StoragesConfig, StoragesStatistics}
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.nxv
import ch.epfl.bluebrain.nexus.delta.sdk.ConfigFixtures
import ch.epfl.bluebrain.nexus.delta.sdk.acls.AclSimpleCheck
import ch.epfl.bluebrain.nexus.delta.sdk.acls.model.AclAddress
import ch.epfl.bluebrain.nexus.delta.sdk.auth.{AuthTokenProvider, Credentials}
import ch.epfl.bluebrain.nexus.delta.sdk.directives.FileResponse
import ch.epfl.bluebrain.nexus.delta.sdk.error.ServiceError.AuthorizationFailed
import ch.epfl.bluebrain.nexus.delta.sdk.http.HttpClient
import ch.epfl.bluebrain.nexus.delta.sdk.identities.model.{Caller, ServiceAccount}
import ch.epfl.bluebrain.nexus.delta.sdk.implicits._
import ch.epfl.bluebrain.nexus.delta.sdk.model._
import ch.epfl.bluebrain.nexus.delta.sdk.permissions.Permissions
import ch.epfl.bluebrain.nexus.delta.sdk.permissions.model.Permission
import ch.epfl.bluebrain.nexus.delta.sdk.projects.FetchContextDummy
import ch.epfl.bluebrain.nexus.delta.sdk.resolvers.ResolverContextResolution
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.{Anonymous, Authenticated, Group, User}
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Tag.UserTag
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{Label, ProjectRef, ResourceRef}
import ch.epfl.bluebrain.nexus.delta.sourcing.postgres.DoobieScalaTestFixture
import ch.epfl.bluebrain.nexus.testkit.remotestorage.RemoteStorageDocker
import ch.epfl.bluebrain.nexus.testkit.scalatest.ce.CatsEffectSpec
import org.scalatest.concurrent.Eventually
import org.scalatest.{Assertion, DoNotDiscover}

import java.net.URLDecoder
import java.util.UUID

@DoNotDiscover
class FilesSpec(docker: RemoteStorageDocker)
    extends TestKit(ActorSystem("FilesSpec"))
    with CatsEffectSpec
    with DoobieScalaTestFixture
    with ConfigFixtures
    with StorageFixtures
    with AkkaSourceHelpers
    with RemoteContextResolutionFixture
    with FileFixtures
    with Eventually {

  private val realm = Label.unsafe("myrealm")
  private val bob   = User("Bob", realm)
  private val alice = User("Alice", realm)

  "The Files operations bundle" when {
    implicit val typedSystem: typed.ActorSystem[Nothing] = system.toTyped
    implicit val httpClient: HttpClient                  = HttpClient()(httpClientConfig, system)
    implicit val caller: Caller                          = Caller(bob, Set(bob, Group("mygroup", realm), Authenticated(realm)))
    implicit val authTokenProvider: AuthTokenProvider    = AuthTokenProvider.anonymousForTest
    val remoteDiskStorageClient                          = new RemoteDiskStorageClient(httpClient, authTokenProvider, Credentials.Anonymous)

    val tag        = UserTag.unsafe("tag")
    val otherRead  = Permission.unsafe("other/read")
    val otherWrite = Permission.unsafe("other/write")

    val allowedPerms = Set(
      diskFields.readPermission.value,
      diskFields.writePermission.value,
      otherRead,
      otherWrite
    )

    val remoteIdIri         = nxv + "remote"
    val remoteId: IdSegment = remoteIdIri
    val remoteRev           = ResourceRef.Revision(iri"$remoteIdIri?rev=1", remoteIdIri, 1)

    val diskIdIri         = nxv + "disk"
    val diskId: IdSegment = nxv + "disk"
    val diskRev           = ResourceRef.Revision(iri"$diskId?rev=1", diskIdIri, 1)

    val smallDiskId: IdSegment = nxv + "smalldisk"

    val storageIri         = nxv + "other-storage"
    val storage: IdSegment = nxv + "other-storage"

    val fetchContext = FetchContextDummy(
      Map(project.ref -> project.context, project2.ref -> project2.context),
      Set(deprecatedProject.ref)
    )

    val aclCheck = AclSimpleCheck(
      (Anonymous, AclAddress.Root, Set(Permissions.resources.read)),
      (bob, AclAddress.Project(projectRef), Set(diskFields.readPermission.value, diskFields.writePermission.value)),
      (bob, AclAddress.Project(projectRefOrg2), Set(diskFields.readPermission.value, diskFields.writePermission.value)),
      (alice, AclAddress.Project(projectRef), Set(otherRead, otherWrite))
    ).accepted

    val cfg = config.copy(
      disk = config.disk.copy(defaultMaxFileSize = 500, allowedVolumes = config.disk.allowedVolumes + path),
      remoteDisk = Some(config.remoteDisk.value.copy(defaultMaxFileSize = 500))
    )

    val storageStatistics: StoragesStatistics = {
      case (`smallDiskId`, _) => IO.pure { StorageStatEntry(10L, 0L) }
      case (_, _)             => IO.pure { StorageStatEntry(10L, 100L) }
    }

    lazy val storages: Storages = Storages(
      fetchContext.mapRejection(StorageRejection.ProjectContextRejection),
      ResolverContextResolution(rcr),
      IO.pure(allowedPerms),
      (_, _) => IO.unit,
      xas,
      StoragesConfig(eventLogConfig, pagination, cfg),
      ServiceAccount(User("nexus-sa", Label.unsafe("sa"))),
      clock
    ).accepted

    lazy val files: Files = Files(
      fetchContext.mapRejection(FileRejection.ProjectContextRejection),
      aclCheck,
      storages,
      storageStatistics,
      xas,
      cfg,
      FilesConfig(eventLogConfig, MediaTypeDetectorConfig.Empty),
      remoteDiskStorageClient,
      clock,
      TransactionalFileCopier.mk()
    )

    def fileId(file: String): FileId = FileId(file, projectRef)
    def fileIdIri(iri: Iri): FileId  = FileId(iri, projectRef)

    def mkResource(
        id: Iri,
        project: ProjectRef,
        storage: ResourceRef.Revision,
        attributes: FileAttributes,
        storageType: StorageType = StorageType.DiskStorage,
        rev: Int = 1,
        deprecated: Boolean = false,
        tags: Tags = Tags.empty
    ): FileResource =
      FileGen.resourceFor(id, project, storage, attributes, storageType, rev, deprecated, tags, bob, bob)

    "creating a file" should {

      "create storages for files" in {
        val payload = diskFieldsJson deepMerge json"""{"capacity": 320, "maxFileSize": 300, "volume": "$path"}"""
        storages.create(diskId, projectRef, payload).accepted
        storages.create(diskId, projectRefOrg2, payload).accepted

        val payload2 =
          json"""{"@type": "RemoteDiskStorage", "endpoint": "${docker.hostConfig.endpoint}", "folder": "${RemoteStorageDocker.BucketName}", "readPermission": "$otherRead", "writePermission": "$otherWrite", "maxFileSize": 300, "default": false}"""
        storages.create(remoteId, projectRef, payload2).accepted
        storages.create(remoteId, projectRefOrg2, payload2).accepted
      }

      "succeed with the id passed" in {
        val expected = mkResource(file1, projectRef, diskRev, attributes("myfile.txt"))
        val actual   = files.create(fileId("file1"), Some(diskId), entity("myfile.txt"), None).accepted
        actual shouldEqual expected
      }

      "succeed when the file has special characters" in {
        val specialFileName = "-._~:?#[ ]@!$&'()*,;="

        files.create(fileId("specialFile"), Some(diskId), randomEntity(specialFileName, 1), None).accepted
        val fetched = files.fetch(fileId("specialFile")).accepted

        val decodedFilenameFromLocation =
          URLDecoder.decode(fetched.value.attributes.location.path.lastSegment.get, "UTF-8")

        decodedFilenameFromLocation shouldEqual specialFileName
      }

      "succeed and tag with the id passed" in {
        withUUIDF(uuid2) {
          val file         = files
            .create(fileId("fileTagged"), Some(diskId), entity("fileTagged.txt"), Some(tag))
            .accepted
          val attr         = attributes("fileTagged.txt", id = uuid2)
          val expectedData = mkResource(fileTagged, projectRef, diskRev, attr, tags = Tags(tag -> 1))
          val fileByTag    = files.fetch(FileId("fileTagged", tag, projectRef)).accepted

          file shouldEqual expectedData
          fileByTag.value.tags.tags should contain(tag)
        }
      }

      "succeed with randomly generated id" in {
        val expected = mkResource(generatedId, projectRef, diskRev, attributes("myfile2.txt"))
        val actual   = files.create(None, projectRef, entity("myfile2.txt"), None).accepted
        val fetched  = files.fetch(FileId(actual.id, projectRef)).accepted

        actual shouldEqual expected
        fetched shouldEqual expected
      }

      "succeed and tag with randomly generated id" in {
        withUUIDF(uuid2) {
          val attr      = attributes("fileTagged2.txt", id = uuid2)
          val expected  = mkResource(generatedId2, projectRef, diskRev, attr, tags = Tags(tag -> 1))
          val file      = files
            .create(None, projectRef, entity("fileTagged2.txt"), Some(tag))
            .accepted
          val fileByTag = files.fetch(FileId(generatedId2, tag, projectRef)).accepted

          file shouldEqual expected
          fileByTag.value.tags.tags should contain(tag)
        }
      }

      "reject if no write permissions" in {
        files
          .create(fileId("file2"), Some(remoteId), entity(), None)
          .rejectedWith[AuthorizationFailed]
      }

      "reject if file id already exists" in {
        files.create(fileId("file1"), None, entity(), None).rejected shouldEqual
          ResourceAlreadyExists(file1, projectRef)
      }

      val aliceCaller = Caller(alice, Set(alice, Group("mygroup", realm), Authenticated(realm)))

      "reject if the file exceeds max file size for the storage" in {
        files
          .create(fileId("file-too-long"), Some(remoteId), randomEntity("large_file", 280), None)(aliceCaller)
          .rejected shouldEqual FileTooLarge(300L, None)
      }

      "reject if the file exceeds the remaining available space on the storage" in {
        files
          .create(fileId("file-too-long"), Some(diskId), randomEntity("large_file", 250), None)
          .rejected shouldEqual FileTooLarge(300L, Some(220))
      }

      "reject if storage does not exist" in {
        files.create(fileId("file2"), Some(storage), entity(), None).rejected shouldEqual
          WrappedStorageRejection(StorageNotFound(storageIri, projectRef))
      }

      "reject if project does not exist" in {
        val projectRef = ProjectRef(org, Label.unsafe("other"))
        files.create(None, projectRef, entity(), None).rejectedWith[ProjectContextRejection]
      }

      "reject if project is deprecated" in {
        files.create(Some(diskId), deprecatedProject.ref, entity(), None).rejectedWith[ProjectContextRejection]
      }
    }

    "linking a file" should {

      "reject if no write permissions" in {
        files
          .createLink(fileId("file2"), Some(remoteId), None, None, Uri.Path.Empty, None)
          .rejectedWith[AuthorizationFailed]
      }

      "succeed and tag with the id passed" in {
        aclCheck.append(AclAddress.Root, bob -> Set(otherWrite)).accepted
        val path     = Uri.Path("my/file-3.txt")
        val tempAttr = attributes("myfile.txt").copy(digest = NotComputedDigest)
        val attr     =
          tempAttr.copy(
            location = Uri(s"file:///app/nexustest/nexus/${tempAttr.path}"),
            origin = Storage,
            mediaType = None
          )
        val expected = mkResource(file2, projectRef, remoteRev, attr, RemoteStorageType, tags = Tags(tag -> 1))

        val result    = files
          .createLink(fileId("file2"), Some(remoteId), Some("myfile.txt"), None, path, Some(tag))
          .accepted
        val fileByTag = files.fetch(FileId("file2", tag, projectRef)).accepted

        result shouldEqual expected
        fileByTag.value.tags.tags should contain(tag)
      }

      "reject if no filename" in {
        files
          .createLink(fileId("file3"), Some(remoteId), None, None, Uri.Path("a/b/"), None)
          .rejectedWith[InvalidFileLink]
      }

      "reject if file id already exists" in {
        files
          .createLink(fileId("file2"), Some(remoteId), None, None, Uri.Path.Empty, None)
          .rejected shouldEqual
          ResourceAlreadyExists(file2, projectRef)
      }

      "reject if storage does not exist" in {
        files
          .createLink(fileId("file3"), Some(storage), None, None, Uri.Path.Empty, None)
          .rejected shouldEqual
          WrappedStorageRejection(StorageNotFound(storageIri, projectRef))
      }

      "reject if project does not exist" in {
        val projectRef = ProjectRef(org, Label.unsafe("other"))
        files.createLink(None, projectRef, None, None, Uri.Path.Empty, None).rejectedWith[ProjectContextRejection]
      }

      "reject if project is deprecated" in {
        files
          .createLink(Some(remoteId), deprecatedProject.ref, None, None, Uri.Path.Empty, None)
          .rejectedWith[ProjectContextRejection]
      }
    }

    "updating a file" should {

      "succeed" in {
        files.update(fileId("file1"), None, 1, entity(), None).accepted shouldEqual
          FileGen.resourceFor(file1, projectRef, diskRev, attributes(), rev = 2, createdBy = bob, updatedBy = bob)
      }

      "reject if file doesn't exists" in {
        files.update(fileIdIri(nxv + "other"), None, 1, entity(), None).rejectedWith[FileNotFound]
      }

      "reject if storage does not exist" in {
        files.update(fileId("file1"), Some(storage), 2, entity(), None).rejected shouldEqual
          WrappedStorageRejection(StorageNotFound(storageIri, projectRef))
      }

      "reject if project does not exist" in {
        val projectRef = ProjectRef(org, Label.unsafe("other"))

        files.update(FileId(file1, projectRef), None, 2, entity(), None).rejectedWith[ProjectContextRejection]
      }

      "reject if project is deprecated" in {
        files
          .update(FileId(file1, deprecatedProject.ref), None, 2, entity(), None)
          .rejectedWith[ProjectContextRejection]
      }
    }

    "updating remote disk file attributes" should {

      "reject if digest is already computed" in {
        files.updateAttributes(file1, projectRef).rejectedWith[DigestAlreadyComputed]
      }

      "succeed" in {
        val tempAttr  = attributes("myfile.txt")
        val attr      = tempAttr.copy(location = Uri(s"file:///app/nexustest/nexus/${tempAttr.path}"), origin = Storage)
        val expected  = mkResource(file2, projectRef, remoteRev, attr, RemoteStorageType, rev = 2, tags = Tags(tag -> 1))
        val updatedF2 = for {
          _ <- files.updateAttributes(file2, projectRef)
          f <- files.fetch(fileIdIri(file2))
        } yield f
        updatedF2.accepted shouldEqual expected
      }
    }

    "updating a file linking" should {

      "succeed and tag" in {
        val path     = Uri.Path("my/file-4.txt")
        val tempAttr = attributes("file-4.txt").copy(digest = NotComputedDigest)
        val attr     = tempAttr.copy(location = Uri(s"file:///app/nexustest/nexus/${tempAttr.path}"), origin = Storage)
        val newTag   = UserTag.unsafe(genString())
        val expected =
          mkResource(file2, projectRef, remoteRev, attr, RemoteStorageType, rev = 3, tags = Tags(tag -> 1, newTag -> 3))
        val actual   = files
          .updateLink(fileId("file2"), Some(remoteId), None, Some(`text/plain(UTF-8)`), path, 2, Some(newTag))
          .accepted
        val byTag    = files.fetch(FileId("file2", newTag, projectRef)).accepted

        actual shouldEqual expected
        byTag shouldEqual expected
      }

      "reject if file doesn't exists" in {
        files
          .updateLink(fileIdIri(nxv + "other"), None, None, None, Uri.Path.Empty, 1, None)
          .rejectedWith[FileNotFound]
      }

      "reject if digest is not computed" in {
        files
          .updateLink(fileId("file2"), None, None, None, Uri.Path.Empty, 3, None)
          .rejectedWith[DigestNotComputed]
      }

      "reject if storage does not exist" in {
        val storage = nxv + "other-storage"
        files
          .updateLink(fileId("file1"), Some(storage), None, None, Uri.Path.Empty, 2, None)
          .rejected shouldEqual
          WrappedStorageRejection(StorageNotFound(storage, projectRef))
      }

      "reject if project does not exist" in {
        val projectRef = ProjectRef(org, Label.unsafe("other"))

        files
          .updateLink(FileId(file1, projectRef), None, None, None, Uri.Path.Empty, 2, None)
          .rejectedWith[ProjectContextRejection]
      }

      "reject if project is deprecated" in {
        files
          .updateLink(FileId(file1, deprecatedProject.ref), None, None, None, Uri.Path.Empty, 2, None)
          .rejectedWith[ProjectContextRejection]
      }
    }

    "tagging a file" should {

      "succeed" in {
        val expected = mkResource(file1, projectRef, diskRev, attributes(), rev = 3, tags = Tags(tag -> 1))
        val actual   = files.tag(fileIdIri(file1), tag, tagRev = 1, 2).accepted
        actual shouldEqual expected
      }

      "reject if file doesn't exists" in {
        files.tag(fileIdIri(nxv + "other"), tag, tagRev = 1, 3).rejectedWith[FileNotFound]
      }

      "reject if project does not exist" in {
        val projectRef = ProjectRef(org, Label.unsafe("other"))

        files.tag(FileId(rdId, projectRef), tag, tagRev = 2, 4).rejectedWith[ProjectContextRejection]
      }

      "reject if project is deprecated" in {
        files.tag(FileId(rdId, deprecatedProject.ref), tag, tagRev = 2, 4).rejectedWith[ProjectContextRejection]
      }
    }

    "copying a file" should {

      "succeed from disk storage based on a tag" in {
        // TODO: adding uuids whenever we want a new independent test is not sustainable. If we truly want to test this every
        // time we should generate a new "Files" with a new UUIDF (and other dependencies we want to control).
        // Alternatively we could normalise the expected values to not care about any generated Ids
        val newFileUuid = UUID.randomUUID()
        withUUIDF(newFileUuid) {
          val source      = CopyFileSource(projectRef, NonEmptyList.of(FileId("file1", tag, projectRef)))
          val destination = CopyFileDestination(projectRefOrg2, Some(diskId), None)

          val expectedDestId   = project2.base.iri / newFileUuid.toString
          val expectedFilename = "myfile.txt"
          val expectedAttr     = attributes(filename = expectedFilename, projRef = projectRefOrg2, id = newFileUuid)
          val expected         = mkResource(expectedDestId, projectRefOrg2, diskRev, expectedAttr)

          val actual = files.copyFiles(source, destination).unsafeRunSync()
          actual shouldEqual NonEmptyList.of(expected)

          val fetched = files.fetch(FileId(newFileUuid.toString, projectRefOrg2)).accepted
          fetched shouldEqual expected
        }
      }

      "succeed from disk storage based on a rev and should tag the new file" in {
        val newFileUuid = UUID.randomUUID()
        withUUIDF(newFileUuid) {
          val source      = CopyFileSource(projectRef, NonEmptyList.of(FileId("file1", 2, projectRef)))
          val newTag      = UserTag.unsafe(genString())
          val destination = CopyFileDestination(projectRefOrg2, Some(diskId), Some(newTag))

          val expectedDestId   = project2.base.iri / newFileUuid.toString
          val expectedFilename = "file.txt"
          val expectedAttr     = attributes(filename = expectedFilename, projRef = projectRefOrg2, id = newFileUuid)
          val expected         = mkResource(expectedDestId, projectRefOrg2, diskRev, expectedAttr, tags = Tags(newTag -> 1))

          val actual = files.copyFiles(source, destination).accepted
          actual shouldEqual NonEmptyList.of(expected)

          val fetchedByTag = files.fetch(FileId(newFileUuid.toString, newTag, projectRefOrg2)).accepted
          fetchedByTag shouldEqual expected
        }
      }

      "reject if the source file doesn't exist" in {
        val destination = CopyFileDestination(projectRefOrg2, None, None)
        val source      = CopyFileSource(projectRef, NonEmptyList.of(fileIdIri(nxv + "other")))
        files.copyFiles(source, destination).rejectedWith[FileNotFound]
      }

      "reject if the destination storage doesn't exist" in {
        val destination = CopyFileDestination(projectRefOrg2, Some(storage), None)
        val source      = CopyFileSource(projectRef, NonEmptyList.of(fileId("file1")))
        files.copyFiles(source, destination).rejected shouldEqual
          WrappedStorageRejection(StorageNotFound(storageIri, projectRefOrg2))
      }

      "reject if copying between different storage types" in {
        val expectedError = DifferentStorageType(remoteIdIri, StorageType.RemoteDiskStorage, StorageType.DiskStorage)
        val destination   = CopyFileDestination(projectRefOrg2, Some(remoteId), None)
        val source        = CopyFileSource(projectRef, NonEmptyList.of(FileId("file1", projectRef)))
        files.copyFiles(source, destination).rejected shouldEqual
          WrappedStorageRejection(expectedError)
      }

      val smallDiskCapacity = 9
      val smallDiskMaxSize  = 5

      "reject if total size of source files exceed remaining available space on the destination storage" in {
        givenAFileWithSize(5) { fileId1 =>
          givenAFileWithSize(5) { fileId2 =>
            val smallDiskPayload =
              diskFieldsJson deepMerge json"""{"capacity": $smallDiskCapacity, "maxFileSize": $smallDiskMaxSize, "volume": "$path"}"""
            storages.create(smallDiskId, projectRefOrg2, smallDiskPayload).accepted

            val source        = CopyFileSource(projectRef, NonEmptyList.of(fileId1, fileId2))
            val destination   = CopyFileDestination(projectRefOrg2, Some(smallDiskId), None)
            val expectedError = FileTooLarge(smallDiskMaxSize.toLong, Some(smallDiskCapacity.toLong))

            files.copyFiles(source, destination).rejected shouldEqual expectedError
          }
        }
      }

      "reject if any of the files exceed max file size of the destination storage" in {
        givenAFileWithSize(1) { fileId1 =>
          givenAFileWithSize(smallDiskMaxSize + 1) { fileId2 =>
            val source        = CopyFileSource(projectRef, NonEmptyList.of(fileId1, fileId2))
            val destination   = CopyFileDestination(projectRefOrg2, Some(smallDiskId), None)
            val expectedError = FileTooLarge(smallDiskMaxSize.toLong, Some(smallDiskCapacity.toLong))

            files.copyFiles(source, destination).rejected shouldEqual expectedError
          }
        }
      }
    }

    "deleting a tag" should {
      "succeed" in {
        val expected = mkResource(file1, projectRef, diskRev, attributes(), rev = 4)
        val actual   = files.deleteTag(fileIdIri(file1), tag, 3).accepted
        actual shouldEqual expected
      }
      "reject if the file doesn't exist" in {
        files.deleteTag(fileIdIri(nxv + "other"), tag, 1).rejectedWith[FileNotFound]
      }
      "reject if the revision passed is incorrect" in {
        files.deleteTag(fileIdIri(file1), tag, 3).rejected shouldEqual IncorrectRev(expected = 4, provided = 3)
      }
      "reject if the tag doesn't exist" in {
        files.deleteTag(fileIdIri(file1), UserTag.unsafe("unknown"), 5).rejected
      }
    }

    "deprecating a file" should {

      "succeed" in {
        val expected = mkResource(file1, projectRef, diskRev, attributes(), rev = 5, deprecated = true)
        val actual   = files.deprecate(fileIdIri(file1), 4).accepted
        actual shouldEqual expected
      }

      "reject if file doesn't exists" in {
        files.deprecate(fileIdIri(nxv + "other"), 1).rejectedWith[FileNotFound]
      }

      "reject if the revision passed is incorrect" in {
        files.deprecate(fileIdIri(file1), 3).rejected shouldEqual
          IncorrectRev(provided = 3, expected = 5)
      }

      "reject if project does not exist" in {
        val projectRef = ProjectRef(org, Label.unsafe("other"))

        files.deprecate(FileId(file1, projectRef), 1).rejectedWith[ProjectContextRejection]
      }

      "reject if project is deprecated" in {
        files.deprecate(FileId(file1, deprecatedProject.ref), 1).rejectedWith[ProjectContextRejection]
      }

      "allow tagging after deprecation" in {
        val expected =
          mkResource(file1, projectRef, diskRev, attributes(), rev = 6, tags = Tags(tag -> 4), deprecated = true)
        val actual   = files.tag(fileIdIri(file1), tag, tagRev = 4, 5).accepted
        actual shouldEqual expected
      }

    }

    "undeprecating a file" should {

      "succeed" in {
        givenADeprecatedFile { id =>
          files.undeprecate(id, 2).accepted.deprecated shouldEqual false
          assertActive(id)
        }
      }

      "reject if file doesn't exists" in {
        files.undeprecate(fileId("404"), 1).rejectedWith[FileNotFound]
      }

      "reject if file is not deprecated" in {
        givenAFile { id =>
          files.undeprecate(id, 1).assertRejectedWith[FileIsNotDeprecated]
          assertRemainsActive(id)
        }
      }

      "reject if the revision passed is incorrect" in {
        givenADeprecatedFile { id =>
          files.undeprecate(id, 3).assertRejectedEquals(IncorrectRev(3, 2))
          assertRemainsDeprecated(id)
        }
      }

      "reject if project does not exist" in {
        val wrongProject = ProjectRef(org, Label.unsafe("other"))
        files.deprecate(FileId(nxv + "id", wrongProject), 1).rejectedWith[ProjectContextRejection]
      }

      "reject if project is deprecated" in {
        files.undeprecate(FileId(nxv + "id", deprecatedProject.ref), 2).rejectedWith[ProjectContextRejection]
      }

    }

    "fetching a file" should {
      val resourceRev1 = mkResource(file1, projectRef, diskRev, attributes("myfile.txt"))
      val resourceRev4 = mkResource(file1, projectRef, diskRev, attributes(), rev = 4)
      val resourceRev6 =
        mkResource(file1, projectRef, diskRev, attributes(), rev = 6, tags = Tags(tag -> 4), deprecated = true)

      "succeed" in {
        files.fetch(fileIdIri(file1)).accepted shouldEqual resourceRev6
      }

      "succeed by tag" in {
        files.fetch(FileId(file1, tag, projectRef)).accepted shouldEqual resourceRev4
      }

      "succeed by rev" in {
        files.fetch(FileId(file1, 6, projectRef)).accepted shouldEqual resourceRev6
        files.fetch(FileId(file1, 1, projectRef)).accepted shouldEqual resourceRev1
      }

      "reject if tag does not exist" in {
        val otherTag = UserTag.unsafe("other")
        files.fetch(FileId(file1, otherTag, projectRef)).rejected shouldEqual TagNotFound(otherTag)
      }

      "reject if revision does not exist" in {
        files.fetch(FileId(file1, 8, projectRef)).rejected shouldEqual
          RevisionNotFound(provided = 8, current = 6)
      }

      "fail if it doesn't exist" in {
        val notFound = nxv + "notFound"
        files.fetch(fileIdIri(notFound)).rejectedWith[FileNotFound]
        files.fetch(FileId(notFound, tag, projectRef)).rejectedWith[FileNotFound]
        files.fetch(FileId(notFound, 2, projectRef)).rejectedWith[FileNotFound]
      }

      "reject if project does not exist" in {
        val projectRef = ProjectRef(org, Label.unsafe("other"))
        files.fetch(FileId(rdId, projectRef)).rejectedWith[ProjectContextRejection]
      }

    }

    def consumeContent(response: FileResponse): String = {
      consume(response.content.map(_.rightValue).accepted)
    }

    "fetching a file content" should {

      "succeed" in {
        val response = files.fetchContent(fileIdIri(file1)).accepted
        consumeContent(response) shouldEqual content
        response.metadata.filename shouldEqual "file.txt"
        response.metadata.contentType shouldEqual `text/plain(UTF-8)`
      }

      "succeed by tag" in {
        val response = files.fetchContent(FileId(file1, tag, projectRef)).accepted
        consumeContent(response) shouldEqual content
        response.metadata.filename shouldEqual "file.txt"
        response.metadata.contentType shouldEqual `text/plain(UTF-8)`
      }

      "succeed by rev" in {
        val response = files.fetchContent(FileId(file1, 1, projectRef)).accepted
        consumeContent(response) shouldEqual content
        response.metadata.filename shouldEqual "myfile.txt"
        response.metadata.contentType shouldEqual `text/plain(UTF-8)`
      }

      "reject if tag does not exist" in {
        val otherTag = UserTag.unsafe("other")
        files.fetchContent(FileId(file1, otherTag, projectRef)).rejected shouldEqual TagNotFound(otherTag)
      }

      "reject if revision does not exist" in {
        files.fetchContent(FileId(file1, 8, projectRef)).rejected shouldEqual
          RevisionNotFound(provided = 8, current = 6)
      }

      "fail if it doesn't exist" in {
        val notFound = nxv + "notFound"
        files.fetchContent(fileIdIri(notFound)).rejectedWith[FileNotFound]
        files.fetchContent(FileId(notFound, tag, projectRef)).rejectedWith[FileNotFound]
        files.fetchContent(FileId(notFound, 2, projectRef)).rejectedWith[FileNotFound]
      }

      "reject if project does not exist" in {
        val projectRef = ProjectRef(org, Label.unsafe("other"))
        files.fetchContent(FileId(rdId, projectRef)).rejectedWith[ProjectContextRejection]
      }

    }

    def givenAFile(assertion: FileId => Assertion): Assertion = givenAFileWithSize(1)(assertion)

    def givenAFileWithSize(size: Int)(assertion: FileId => Assertion): Assertion = {
      val filename = genString()
      val id       = fileId(filename)
      files.create(id, Some(diskId), randomEntity(filename, size), None).accepted
      files.fetch(id).accepted
      assertion(id)
    }

    def givenADeprecatedFile(assertion: FileId => Assertion): Assertion =
      givenAFile { id =>
        files.deprecate(id, 1).accepted
        files.fetch(id).accepted.deprecated shouldEqual true
        assertion(id)
      }

    def assertRemainsDeprecated(id: FileId): Assertion =
      files.fetch(id).accepted.deprecated shouldEqual true
    def assertActive(id: FileId): Assertion            =
      files.fetch(id).accepted.deprecated shouldEqual false
    def assertRemainsActive(id: FileId): Assertion     =
      assertActive(id)
  }

}
