package ch.epfl.bluebrain.nexus.delta.plugins.storage.files

import akka.actor.typed.scaladsl.adapter._
import akka.actor.{typed, ActorSystem}
import akka.http.scaladsl.model.ContentTypes.`text/plain(UTF-8)`
import akka.http.scaladsl.model.{ContentType, Uri}
import akka.testkit.TestKit
import cats.effect.IO
import ch.epfl.bluebrain.nexus.delta.kernel.http.MediaTypeDetectorConfig
import ch.epfl.bluebrain.nexus.delta.plugins.storage.RemoteContextResolutionFixture
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.generators.FileGen
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.mocks.FileOperationsMock
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model.Digest.NotComputedDigest
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model.FileAttributes.FileAttributesOrigin.Storage
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model.FileRejection._
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model.{FileAttributes, FileCustomMetadata, FileDescription, FileId, FileUploadRequest}
import ch.epfl.bluebrain.nexus.delta.plugins.storage.remotestorage.RemoteStorageClientFixtures
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.StorageRejection.StorageNotFound
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.StorageType
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.StorageType.{RemoteDiskStorage => RemoteStorageType}
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.{AkkaSourceHelpers, FileOperations}
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.{StorageFixtures, Storages, StoragesConfig}
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.nxv
import ch.epfl.bluebrain.nexus.delta.sdk.ConfigFixtures
import ch.epfl.bluebrain.nexus.delta.sdk.acls.AclSimpleCheck
import ch.epfl.bluebrain.nexus.delta.sdk.acls.model.AclAddress
import ch.epfl.bluebrain.nexus.delta.sdk.directives.FileResponse
import ch.epfl.bluebrain.nexus.delta.sdk.error.ServiceError.AuthorizationFailed
import ch.epfl.bluebrain.nexus.delta.sdk.identities.model.{Caller, ServiceAccount}
import ch.epfl.bluebrain.nexus.delta.sdk.implicits._
import ch.epfl.bluebrain.nexus.delta.sdk.model._
import ch.epfl.bluebrain.nexus.delta.sdk.permissions.Permissions
import ch.epfl.bluebrain.nexus.delta.sdk.permissions.model.Permission
import ch.epfl.bluebrain.nexus.delta.sdk.projects.FetchContextDummy
import ch.epfl.bluebrain.nexus.delta.sdk.projects.model.ProjectRejection.{ProjectIsDeprecated, ProjectNotFound}
import ch.epfl.bluebrain.nexus.delta.sdk.resolvers.ResolverContextResolution
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.{Anonymous, Authenticated, Group, User}
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Tag.UserTag
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{Label, ProjectRef, ResourceRef}
import ch.epfl.bluebrain.nexus.delta.sourcing.postgres.DoobieScalaTestFixture
import ch.epfl.bluebrain.nexus.testkit.scalatest.ce.CatsEffectSpec
import org.scalatest.concurrent.Eventually
import org.scalatest.{Assertion, DoNotDiscover}

import java.net.URLDecoder
import java.util.UUID

@DoNotDiscover
class FilesSpec(fixture: RemoteStorageClientFixtures)
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

  def description(filename: String): FileDescription = {
    FileDescription(filename, None, Some(FileCustomMetadata.empty))
  }

  def description(filename: String, contentType: ContentType): FileDescription = {
    FileDescription(filename, Some(contentType), Some(FileCustomMetadata.empty))
  }

  def descriptionWithName(filename: String, name: String): FileDescription =
    FileDescription(filename, None, Some(FileCustomMetadata(Some(name), None, None)))

  def descriptionWithMetadata(
      filename: String,
      name: String,
      description: String,
      keywords: Map[Label, String]
  ): FileDescription =
    FileDescription(filename, None, Some(FileCustomMetadata(Some(name), Some(description), Some(keywords))))

  "The Files operations bundle" when {
    implicit val typedSystem: typed.ActorSystem[Nothing] = system.toTyped
    implicit val caller: Caller                          = Caller(bob, Set(bob, Group("mygroup", realm), Authenticated(realm)))
    lazy val remoteDiskStorageClient                     = fixture.init

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

    val storageIri         = nxv + "other-storage"
    val storage: IdSegment = nxv + "other-storage"

    val fetchContext = FetchContextDummy(
      Map(project.ref -> project.context),
      Set(deprecatedProject.ref)
    )

    val aclCheck = AclSimpleCheck(
      (Anonymous, AclAddress.Root, Set(Permissions.resources.read)),
      (bob, AclAddress.Project(projectRef), Set(diskFields.readPermission.value, diskFields.writePermission.value)),
      (alice, AclAddress.Project(projectRef), Set(otherRead, otherWrite))
    ).accepted

    val maxFileSize = 300L

    val cfg = config.copy(
      disk = config.disk.copy(defaultMaxFileSize = maxFileSize, allowedVolumes = config.disk.allowedVolumes + path),
      remoteDisk = Some(config.remoteDisk.value.copy(defaultMaxFileSize = maxFileSize))
    )

    lazy val storages: Storages = Storages(
      fetchContext,
      ResolverContextResolution(rcr),
      IO.pure(allowedPerms),
      _ => IO.unit,
      xas,
      StoragesConfig(eventLogConfig, pagination, cfg),
      ServiceAccount(User("nexus-sa", Label.unsafe("sa"))),
      clock
    ).accepted

    lazy val fileOps: FileOperations = FileOperationsMock.forDiskAndRemoteDisk(remoteDiskStorageClient)

    lazy val files: Files = Files(
      fetchContext,
      aclCheck,
      storages,
      xas,
      FilesConfig(eventLogConfig, MediaTypeDetectorConfig.Empty),
      fileOps,
      clock
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
        val payload = diskFieldsJson deepMerge json"""{"maxFileSize": 300, "volume": "$path"}"""
        storages.create(diskId, projectRef, payload).accepted

        val payload2 =
          json"""{"@type": "RemoteDiskStorage", "endpoint": "${fixture.hostConfig.endpoint}", "folder": "${RemoteStorageClientFixtures.BucketName}", "readPermission": "$otherRead", "writePermission": "$otherWrite", "maxFileSize": 300, "default": false}"""
        storages.create(remoteId, projectRef, payload2).accepted
      }

      "succeed with the id passed" in {
        val request  = FileUploadRequest.from(entity("myfile.txt"))
        val expected = mkResource(file1, projectRef, diskRev, attributes("myfile.txt"))
        val actual   = files.create(fileId("file1"), Some(diskId), request, None).accepted
        actual shouldEqual expected
      }

      "succeed with the id passed and custom metadata" in {
        val metadata = genCustomMetadata()
        val id       = fileId(genString())
        val request  = FileUploadRequest(entity(genString()), Some(metadata), None)

        files.create(id, Some(diskId), request, None).accepted
        assertCorrectCustomMetadata(id, metadata)
      }

      "succeed when the file has special characters" in {
        val specialFileName = "-._~:?#[ ]@!$&'()*,;="
        val request         = FileUploadRequest.from(randomEntity(specialFileName, 1))

        files.create(fileId("specialFile"), Some(diskId), request, None).accepted
        val fetched = files.fetch(fileId("specialFile")).accepted

        val decodedFilenameFromLocation =
          URLDecoder.decode(fetched.value.attributes.location.path.lastSegment.get, "UTF-8")

        decodedFilenameFromLocation shouldEqual specialFileName
      }

      "succeed and tag with the id passed" in {
        withUUIDF(uuid2) {
          val request      = FileUploadRequest.from(entity("fileTagged.txt"))
          val file         = files.create(fileId("fileTagged"), Some(diskId), request, Some(tag)).accepted
          val attr         = attributes("fileTagged.txt", id = uuid2)
          val expectedData = mkResource(fileTagged, projectRef, diskRev, attr, tags = Tags(tag -> 1))
          val fileByTag    = files.fetch(FileId("fileTagged", tag, projectRef)).accepted

          file shouldEqual expectedData
          fileByTag.value.tags.tags should contain(tag)
        }
      }

      "succeed with randomly generated id" in {
        val expected = mkResource(generatedId, projectRef, diskRev, attributes("myfile2.txt"))
        val request  = FileUploadRequest.from(entity("myfile2.txt"))
        val actual   = files.create(None, projectRef, request, None).accepted
        val fetched  = files.fetch(FileId(actual.id, projectRef)).accepted

        actual shouldEqual expected
        fetched shouldEqual expected
      }

      "succeed with randomly generated id and custom metadata" in {
        withUUIDF(UUID.randomUUID()) {
          val metadata = genCustomMetadata()
          val request  = FileUploadRequest(entity(genString()), Some(metadata), None)
          val created  = files.create(None, projectRef, request, None).accepted
          assertCorrectCustomMetadata(fileIdIri(created.id), metadata)
        }
      }

      "succeed and tag with randomly generated id" in {
        withUUIDF(uuid2) {
          val attr      = attributes("fileTagged2.txt", id = uuid2)
          val expected  = mkResource(generatedId2, projectRef, diskRev, attr, tags = Tags(tag -> 1))
          val request   = FileUploadRequest.from(entity("fileTagged2.txt"))
          val file      = files.create(None, projectRef, request, Some(tag)).accepted
          val fileByTag = files.fetch(FileId(generatedId2, tag, projectRef)).accepted

          file shouldEqual expected
          fileByTag.value.tags.tags should contain(tag)
        }
      }

      "reject if no write permissions" in {
        val request = FileUploadRequest.from(entity())
        files.create(fileId("file2"), Some(remoteId), request, None).rejectedWith[AuthorizationFailed]
      }

      "reject if file id already exists" in {
        val request = FileUploadRequest.from(entity())
        files.create(fileId("file1"), None, request, None).rejected shouldEqual ResourceAlreadyExists(file1, projectRef)
      }

      val aliceCaller = Caller(alice, Set(alice, Group("mygroup", realm), Authenticated(realm)))

      "reject if the file exceeds max file size for the storage" in {
        val id      = fileId("file-too-large")
        val request = FileUploadRequest.from(randomEntity("large_file", (maxFileSize + 1).toInt))
        files.create(id, Some(remoteId), request, None)(aliceCaller).rejected shouldEqual FileTooLarge(maxFileSize)
      }

      "reject if storage does not exist" in {
        val request       = FileUploadRequest.from(entity())
        val expectedError = WrappedStorageRejection(StorageNotFound(storageIri, projectRef))
        files.create(fileId("file2"), Some(storage), request, None).rejected shouldEqual expectedError
      }

      "reject if project does not exist" in {
        val projectRef = ProjectRef(org, Label.unsafe("other"))
        val request    = FileUploadRequest.from(entity())
        files.create(None, projectRef, request, None).rejectedWith[ProjectNotFound]
      }

      "reject if project is deprecated" in {
        val request = FileUploadRequest.from(entity())
        files.create(Some(diskId), deprecatedProject.ref, request, None).rejectedWith[ProjectIsDeprecated]
      }
    }

    "linking a file" should {

      "reject if no write permissions" in {
        files
          .createLegacyLink(fileId("file2"), Some(remoteId), description("myfile.txt"), Uri.Path.Empty, None)
          .rejectedWith[AuthorizationFailed]
      }

      "succeed and tag with the id passed" in {
        aclCheck.append(AclAddress.Root, bob -> Set(otherWrite)).accepted
        val path      = Uri.Path("my/file-3.txt")
        val tempAttr  = attributes("myfile.txt").copy(digest = NotComputedDigest)
        val attr      =
          tempAttr.copy(
            location = Uri(s"file:///app/nexustest/nexus/${tempAttr.path}"),
            origin = Storage,
            mediaType = None
          )
        val expected  =
          mkResource(file2, projectRef, remoteRev, attr, storageType = RemoteStorageType, tags = Tags(tag -> 1))

        val result    = files
          .createLegacyLink(fileId("file2"), Some(remoteId), description("myfile.txt"), path, Some(tag))
          .accepted
        val fileByTag = files.fetch(FileId("file2", tag, projectRef)).accepted

        result shouldEqual expected
        fileByTag.value.tags.tags should contain(tag)
      }

      "succeed with custom user provided metadata" in {
        val (name, description, keywords) = (genString(), genString(), genKeywords())
        val fileDescription               = descriptionWithMetadata("file-5.txt", name, description, keywords)

        val id   = fileId(genString())
        val path = Uri.Path(s"my/file-5.txt")

        files.createLegacyLink(id, Some(remoteId), fileDescription, path, None).accepted
        val fetchedFile = files.fetch(id).accepted

        fetchedFile.value.attributes.name should contain(name)
        fetchedFile.value.attributes.description should contain(description)
        fetchedFile.value.attributes.keywords shouldEqual keywords
      }

      "reject if file id already exists" in {
        files
          .createLegacyLink(fileId("file2"), Some(remoteId), description("myfile.txt"), Uri.Path.Empty, None)
          .rejected shouldEqual
          ResourceAlreadyExists(file2, projectRef)
      }

      "reject if storage does not exist" in {
        files
          .createLegacyLink(fileId("file3"), Some(storage), description("myfile.txt"), Uri.Path.Empty, None)
          .rejected shouldEqual
          WrappedStorageRejection(StorageNotFound(storageIri, projectRef))
      }

      "reject if project does not exist" in {
        val projectRef = ProjectRef(org, Label.unsafe("other"))
        files
          .createLegacyLink(None, projectRef, description("myfile.txt"), Uri.Path.Empty, None)
          .rejectedWith[ProjectNotFound]
      }

      "reject if project is deprecated" in {
        files
          .createLegacyLink(Some(remoteId), deprecatedProject.ref, description("myfile.txt"), Uri.Path.Empty, None)
          .rejectedWith[ProjectIsDeprecated]
      }
    }

    "updating a file" should {

      "succeed" in {
        val request = FileUploadRequest.from(entity())
        files.update(fileId("file1"), None, 1, request, None).accepted shouldEqual
          FileGen.resourceFor(file1, projectRef, diskRev, attributes(), rev = 2, createdBy = bob, updatedBy = bob)
      }

      "succeed with custom metadata" in {
        val metadata      = genCustomMetadata()
        val id            = fileId(genString())
        val createRequest = FileUploadRequest.from(entity(genString()))
        val updateRequest = FileUploadRequest(randomEntity(genString(), 10), Some(metadata), None)

        files.create(id, Some(diskId), createRequest, None).accepted
        files.update(id, None, 1, updateRequest, None).accepted

        assertCorrectCustomMetadata(id, metadata)
      }

      "reject if file doesn't exists" in {
        val request = FileUploadRequest.from(entity())
        files.update(fileIdIri(nxv + "other"), None, 1, request, None).rejectedWith[FileNotFound]
      }

      "reject if storage does not exist" in {
        val request = FileUploadRequest.from(entity())
        files.update(fileId("file1"), Some(storage), 2, request, None).rejected shouldEqual
          WrappedStorageRejection(StorageNotFound(storageIri, projectRef))
      }

      "reject if project does not exist" in {
        val projectRef = ProjectRef(org, Label.unsafe("other"))
        val request    = FileUploadRequest.from(entity())

        files.update(FileId(file1, projectRef), None, 2, request, None).rejectedWith[ProjectNotFound]
      }

      "reject if project is deprecated" in {
        val id      = FileId(file1, deprecatedProject.ref)
        val request = FileUploadRequest.from(entity())
        files.update(id, None, 2, request, None).rejectedWith[ProjectIsDeprecated]
      }
    }

    "updating the custom metadata of a file" should {

      "succeed" in {
        val id       = fileId(genString())
        val metadata = genCustomMetadata()
        val request  = FileUploadRequest.from(entity(genString()))

        files.create(id, Some(diskId), request, None).accepted
        files.updateMetadata(id, 1, metadata, None).accepted

        files.fetch(id).accepted.rev shouldEqual 2
        assertCorrectCustomMetadata(id, metadata)
      }

      "succeed with tag" in {
        val id       = fileId(genString())
        val metadata = genCustomMetadata()
        val request  = FileUploadRequest.from(entity(genString()))

        files.create(id, Some(diskId), request, None).accepted
        files.updateMetadata(id, 1, metadata, Some(tag)).accepted
        val updatedFile = files.fetch(id).accepted

        updatedFile.rev shouldEqual 2
        assertCorrectCustomMetadata(id, metadata)
        updatedFile.value.tags.tags should contain(tag)
      }

      "reject if the wrong revision is specified" in {
        val id       = fileId(genString())
        val metadata = genCustomMetadata()
        val request  = FileUploadRequest.from(entity(genString()))

        files.create(id, Some(diskId), request, None).accepted
        val expectedError = IncorrectRev(expected = 1, provided = 2)
        files.updateMetadata(id, 2, metadata, None).rejected shouldEqual expectedError
      }

      "reject if file doesn't exists" in {
        val nonExistentFile = fileIdIri(nxv + genString())

        files
          .updateMetadata(nonExistentFile, 1, genCustomMetadata(), None)
          .rejectedWith[FileNotFound]
      }

      "reject if project does not exist" in {
        val nonexistentProject       = ProjectRef(org, Label.unsafe(genString()))
        val fileInNonexistentProject = FileId(genString(), nonexistentProject)

        files
          .updateMetadata(fileInNonexistentProject, 1, genCustomMetadata(), None)
          .rejectedWith[ProjectNotFound]
      }

      "reject if project is deprecated" in {
        val fileInDeprecatedProject = FileId(genString(), deprecatedProject.ref)

        files
          .updateMetadata(fileInDeprecatedProject, 1, genCustomMetadata(), None)
          .rejectedWith[ProjectIsDeprecated]
      }

    }

    "updating remote disk file attributes" should {

      "reject if digest is already computed" in {
        files.updateAttributes(file1, projectRef).rejectedWith[DigestAlreadyComputed]
      }

      "succeed" in {
        val tempAttr  = attributes("myfile.txt")
        val attr      = tempAttr.copy(location = Uri(s"file:///app/nexustest/nexus/${tempAttr.path}"), origin = Storage)
        val expected  = mkResource(
          file2,
          projectRef,
          remoteRev,
          attr,
          storageType = RemoteStorageType,
          rev = 2,
          tags = Tags(tag -> 1)
        )
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
          mkResource(
            file2,
            projectRef,
            remoteRev,
            attr,
            storageType = RemoteStorageType,
            rev = 3,
            tags = Tags(tag -> 1, newTag -> 3)
          )
        val actual   = files
          .updateLegacyLink(
            fileId("file2"),
            Some(remoteId),
            description("file-4.txt", `text/plain(UTF-8)`),
            path,
            2,
            Some(newTag)
          )
          .accepted
        val byTag    = files.fetch(FileId("file2", newTag, projectRef)).accepted

        actual shouldEqual expected
        byTag shouldEqual expected
      }

      "succeed if also updating custom metadata" in {
        val id   = fileId(genString())
        val path = Uri.Path("my/file-6.txt")

        val (name, desc, keywords) = (genString(), genString(), genKeywords())

        val originalFileDescription = description("file-6.txt")
        val updatedFileDescription  = descriptionWithMetadata("file-6.txt", name, desc, keywords)

        files.createLegacyLink(id, Some(remoteId), originalFileDescription, path, None).accepted

        val fetched = files.fetch(id).accepted
        files.updateAttributes(fetched.id, projectRef).accepted
        files.updateLegacyLink(id, Some(remoteId), updatedFileDescription, path, 2, None)

        eventually {
          files.fetch(id).map { fetched =>
            fetched.value.attributes.name should contain(name)
            fetched.value.attributes.description should contain(desc)
            fetched.value.attributes.keywords shouldEqual keywords
          }
        }

      }

      "reject if file doesn't exists" in {
        files
          .updateLegacyLink(fileIdIri(nxv + "other"), None, description("myfile.txt"), Uri.Path.Empty, 1, None)
          .rejectedWith[FileNotFound]
      }

      "reject if digest is not computed" in {
        files
          .updateLegacyLink(fileId("file2"), None, description("myfile.txt"), Uri.Path.Empty, 3, None)
          .rejectedWith[DigestNotComputed]
      }

      "reject if storage does not exist" in {
        val storage = nxv + "other-storage"
        files
          .updateLegacyLink(fileId("file1"), Some(storage), description("myfile.txt"), Uri.Path.Empty, 2, None)
          .rejected shouldEqual
          WrappedStorageRejection(StorageNotFound(storage, projectRef))
      }

      "reject if project does not exist" in {
        val projectRef = ProjectRef(org, Label.unsafe("other"))

        files
          .updateLegacyLink(FileId(file1, projectRef), None, description("myfile.txt"), Uri.Path.Empty, 2, None)
          .rejectedWith[ProjectNotFound]
      }

      "reject if project is deprecated" in {
        files
          .updateLegacyLink(
            FileId(file1, deprecatedProject.ref),
            None,
            description("myfile.txt"),
            Uri.Path.Empty,
            2,
            None
          )
          .rejectedWith[ProjectIsDeprecated]
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

        files.tag(FileId(rdId, projectRef), tag, tagRev = 2, 4).rejectedWith[ProjectNotFound]
      }

      "reject if project is deprecated" in {
        files.tag(FileId(rdId, deprecatedProject.ref), tag, tagRev = 2, 4).rejectedWith[ProjectIsDeprecated]
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

        files.deprecate(FileId(file1, projectRef), 1).rejectedWith[ProjectNotFound]
      }

      "reject if project is deprecated" in {
        files.deprecate(FileId(file1, deprecatedProject.ref), 1).rejectedWith[ProjectIsDeprecated]
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
        files.deprecate(FileId(nxv + "id", wrongProject), 1).rejectedWith[ProjectNotFound]
      }

      "reject if project is deprecated" in {
        files.undeprecate(FileId(nxv + "id", deprecatedProject.ref), 2).rejectedWith[ProjectIsDeprecated]
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
        files.fetch(FileId(rdId, projectRef)).rejectedWith[ProjectNotFound]
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
        files.fetchContent(FileId(rdId, projectRef)).rejectedWith[ProjectNotFound]
      }

    }

    def givenAFile(assertion: FileId => Assertion): Assertion = {
      val filename = genString()
      val id       = fileId(filename)
      val request  = FileUploadRequest.from(randomEntity(filename, 1))
      files.create(id, Some(diskId), request, None).accepted
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

    def assertCorrectCustomMetadata(id: FileId, metadata: FileCustomMetadata): Assertion = {
      val fetched = files.fetch(id).accepted
      fetched.value.attributes.name shouldEqual metadata.name
      fetched.value.attributes.description shouldEqual metadata.description
      fetched.value.attributes.keywords shouldEqual metadata.keywords.get
    }
  }

}
