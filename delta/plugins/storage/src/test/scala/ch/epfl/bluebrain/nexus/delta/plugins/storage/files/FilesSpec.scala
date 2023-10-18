package ch.epfl.bluebrain.nexus.delta.plugins.storage.files

import akka.actor.typed.scaladsl.adapter._
import akka.actor.{typed, ActorSystem}
import akka.http.scaladsl.model.ContentTypes.`text/plain(UTF-8)`
import akka.http.scaladsl.model.Uri
import akka.testkit.TestKit
import ch.epfl.bluebrain.nexus.delta.kernel.http.MediaTypeDetectorConfig
import ch.epfl.bluebrain.nexus.delta.plugins.storage.RemoteContextResolutionFixture
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model.Digest.NotComputedDigest
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model.FileAttributes.FileAttributesOrigin.Storage
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model.FileRejection._
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model.{FileAttributes, FileOptions, FileRejection}
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.StorageRejection.StorageNotFound
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
import ch.epfl.bluebrain.nexus.testkit.{CirceLiteral, IOFixedClock, IOValues}
import monix.bio.IO
import monix.execution.Scheduler
import org.scalatest.concurrent.Eventually
import org.scalatest.matchers.should.Matchers
import org.scalatest.{DoNotDiscover, Inspectors, OptionValues}

@DoNotDiscover
class FilesSpec(docker: RemoteStorageDocker)
    extends TestKit(ActorSystem("FilesSpec"))
    with DoobieScalaTestFixture
    with OptionValues
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

  private val realm = Label.unsafe("myrealm")
  private val bob   = User("Bob", realm)
  private val alice = User("Alice", realm)

  "The Files operations bundle" when {
    implicit val typedSystem: typed.ActorSystem[Nothing] = system.toTyped
    implicit val httpClient: HttpClient                  = HttpClient()(httpClientConfig, system, sc)
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

    val remoteId  = nxv + "remote"
    val remoteRev = ResourceRef.Revision(iri"$remoteId?rev=1", remoteId, 1)

    val fetchContext = FetchContextDummy(
      Map(project.ref -> project.context),
      Set(deprecatedProject.ref)
    )

    val aclCheck = AclSimpleCheck(
      (Anonymous, AclAddress.Root, Set(Permissions.resources.read)),
      (bob, AclAddress.Project(projectRef), Set(diskFields.readPermission.value, diskFields.writePermission.value)),
      (alice, AclAddress.Project(projectRef), Set(otherRead, otherWrite))
    ).accepted

    val cfg = config.copy(
      disk = config.disk.copy(defaultMaxFileSize = 500, allowedVolumes = config.disk.allowedVolumes + path),
      remoteDisk = Some(config.remoteDisk.value.copy(defaultMaxFileSize = 500))
    )

    val storageStatistics: StoragesStatistics =
      (_, _) => IO.pure { StorageStatEntry(10L, 100L) }

    lazy val storages: Storages = Storages(
      fetchContext.mapRejection(StorageRejection.ProjectContextRejection),
      ResolverContextResolution(rcr),
      IO.pure(allowedPerms),
      (_, _) => IO.unit,
      xas,
      StoragesConfig(eventLogConfig, pagination, cfg),
      ServiceAccount(User("nexus-sa", Label.unsafe("sa")))
    ).accepted

    lazy val files: Files = Files(
      fetchContext.mapRejection(FileRejection.ProjectContextRejection),
      aclCheck,
      storages,
      storageStatistics,
      xas,
      cfg,
      FilesConfig(eventLogConfig, MediaTypeDetectorConfig.Empty),
      remoteDiskStorageClient
    )

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

        val payload2 =
          json"""{"@type": "RemoteDiskStorage", "endpoint": "${docker.hostConfig.endpoint}", "folder": "${RemoteStorageDocker.BucketName}", "readPermission": "$otherRead", "writePermission": "$otherWrite", "maxFileSize": 300, "default": false}"""
        storages.create(remoteId, projectRef, payload2).accepted
      }

      "succeed with the id passed" in {
        val expected = mkResource(file1, projectRef, diskRev, attributes("myfile.txt"))
        val actual   = files.create("file1", projectRef, entity("myfile.txt"), FileOptions(diskId)).accepted
        actual shouldEqual expected
      }

      "succeed and tag with the id passed" in {
        withUUIDF(uuid2) {
          val file         = files
            .create("fileTagged", projectRef, entity("fileTagged.txt"), FileOptions(diskId, tag))
            .accepted
          val attr         = attributes("fileTagged.txt", id = uuid2)
          val expectedData = mkResource(fileTagged, projectRef, diskRev, attr, tags = Tags(tag -> 1))
          val fileByTag    = files.fetch(IdSegmentRef("fileTagged", tag), projectRef).accepted

          file shouldEqual expectedData
          fileByTag.value.tags.tags should contain(tag)
        }
      }

      "succeed with randomly generated id" in {
        val expected = mkResource(generatedId, projectRef, diskRev, attributes("myfile2.txt"))
        val actual   = files.create(projectRef, entity("myfile2.txt"), FileOptions()).accepted
        val fetched  = files.fetch(actual.id, projectRef).accepted

        actual shouldEqual expected
        fetched shouldEqual expected
      }

      "succeed and tag with randomly generated id" in {
        withUUIDF(uuid2) {
          val attr      = attributes("fileTagged2.txt", id = uuid2)
          val expected  = mkResource(generatedId2, projectRef, diskRev, attr, tags = Tags(tag -> 1))
          val file      = files.create(projectRef, entity("fileTagged2.txt"), FileOptions(tag)).accepted
          val fileByTag = files.fetch(IdSegmentRef(generatedId2, tag), projectRef).accepted

          file shouldEqual expected
          fileByTag.value.tags.tags should contain(tag)
        }
      }

      "reject if no write permissions" in {
        files
          .create("file2", projectRef, entity(), FileOptions(remoteId))
          .rejectedWith[AuthorizationFailed]
      }

      "reject if file id already exists" in {
        files.create("file1", projectRef, entity(), FileOptions()).rejected shouldEqual
          ResourceAlreadyExists(file1, projectRef)
      }

      val aliceCaller = Caller(alice, Set(alice, Group("mygroup", realm), Authenticated(realm)))

      "reject if the file exceeds max file size for the storage" in {
        files
          .create("file-too-long", projectRef, randomEntity("large_file", 280), FileOptions(remoteId))(aliceCaller)
          .rejected shouldEqual FileTooLarge(300L, None)
      }

      "reject if the file exceeds the remaining available space on the storage" in {
        files
          .create("file-too-long", projectRef, randomEntity("large_file", 250), FileOptions(diskId))
          .rejected shouldEqual FileTooLarge(300L, Some(220))
      }

      "reject if storage does not exist" in {
        val storage = nxv + "other-storage"
        files.create("file2", projectRef, entity(), FileOptions(storage)).rejected shouldEqual
          WrappedStorageRejection(StorageNotFound(storage, projectRef))
      }

      "reject if project does not exist" in {
        val projectRef = ProjectRef(org, Label.unsafe("other"))
        files.create(projectRef, entity(), FileOptions()).rejectedWith[ProjectContextRejection]
      }

      "reject if project is deprecated" in {
        files.create(deprecatedProject.ref, entity(), FileOptions(diskId)).rejectedWith[ProjectContextRejection]
      }
    }

    "linking a file" should {

      "reject if no write permissions" in {
        files
          .createLink("file2", projectRef, Uri.Path.Empty, FileOptions(remoteId))
          .rejectedWith[AuthorizationFailed]
      }

      "succeed and tag with the id passed" in {
        aclCheck.append(AclAddress.Root, bob -> Set(otherWrite)).accepted
        val path     = Uri.Path("my/file-3.txt")
        val tempAttr = attributes("myfile.txt").copy(digest = NotComputedDigest)
        val attr     =
          tempAttr.copy(location = s"file:///app/nexustest/nexus/${tempAttr.path}", origin = Storage, mediaType = None)
        val expected = mkResource(file2, projectRef, remoteRev, attr, RemoteStorageType, tags = Tags(tag -> 1))

        val result    = files
          .createLink("file2", projectRef, path, FileOptions(remoteId, "myfile.txt", tag))
          .accepted
        val fileByTag = files.fetch(IdSegmentRef("file2", tag), projectRef).accepted

        result shouldEqual expected
        fileByTag.value.tags.tags should contain(tag)
      }

      "reject if no filename" in {
        files
          .createLink("file3", projectRef, Uri.Path("a/b/"), FileOptions(remoteId))
          .rejectedWith[InvalidFileLink]
      }

      "reject if file id already exists" in {
        files
          .createLink("file2", projectRef, Uri.Path.Empty, FileOptions(remoteId))
          .rejected shouldEqual
          ResourceAlreadyExists(file2, projectRef)
      }

      "reject if storage does not exist" in {
        val storage = nxv + "other-storage"
        files
          .createLink("file3", projectRef, Uri.Path.Empty, FileOptions(storage))
          .rejected shouldEqual
          WrappedStorageRejection(StorageNotFound(storage, projectRef))
      }

      "reject if project does not exist" in {
        val projectRef = ProjectRef(org, Label.unsafe("other"))
        files.createLink(projectRef, Uri.Path.Empty, FileOptions()).rejectedWith[ProjectContextRejection]
      }

      "reject if project is deprecated" in {
        files
          .createLink(deprecatedProject.ref, Uri.Path.Empty, FileOptions(remoteId))
          .rejectedWith[ProjectContextRejection]
      }
    }

    "updating a file" should {

      "succeed" in {
        files.update("file1", None, projectRef, 1, entity()).accepted shouldEqual
          FileGen.resourceFor(file1, projectRef, diskRev, attributes(), rev = 2, createdBy = bob, updatedBy = bob)
      }

      "reject if file doesn't exists" in {
        files.update(nxv + "other", None, projectRef, 1, entity()).rejectedWith[FileNotFound]
      }

      "reject if storage does not exist" in {
        val storage = nxv + "other-storage"
        files.update("file1", Some(storage), projectRef, 2, entity()).rejected shouldEqual
          WrappedStorageRejection(StorageNotFound(storage, projectRef))
      }

      "reject if project does not exist" in {
        val projectRef = ProjectRef(org, Label.unsafe("other"))

        files.update(file1, None, projectRef, 2, entity()).rejectedWith[ProjectContextRejection]
      }

      "reject if project is deprecated" in {
        files.update(file1, None, deprecatedProject.ref, 2, entity()).rejectedWith[ProjectContextRejection]
      }
    }

    "updating remote disk file attributes" should {

      "reject if digest is already computed" in {
        files.updateAttributes(file1, projectRef).rejectedWith[DigestAlreadyComputed]
      }

      "succeed" in {
        val tempAttr  = attributes("myfile.txt")
        val attr      = tempAttr.copy(location = s"file:///app/nexustest/nexus/${tempAttr.path}", origin = Storage)
        val expected  = mkResource(file2, projectRef, remoteRev, attr, RemoteStorageType, rev = 2, tags = Tags(tag -> 1))
        val updatedF2 = for {
          _ <- files.updateAttributes(file2, projectRef)
          f <- files.fetch(file2, projectRef)
        } yield f
        updatedF2.accepted shouldEqual expected
      }
    }

    "updating a file linking" should {

      "succeed" in {
        val path     = Uri.Path("my/file-4.txt")
        val tempAttr = attributes("file-4.txt").copy(digest = NotComputedDigest)
        val attr     = tempAttr.copy(location = s"file:///app/nexustest/nexus/${tempAttr.path}", origin = Storage)
        val expected = mkResource(file2, projectRef, remoteRev, attr, RemoteStorageType, rev = 3, tags = Tags(tag -> 1))
        files
          .updateLink("file2", projectRef, path, 2, FileOptions(remoteId, `text/plain(UTF-8)`))
          .accepted shouldEqual expected
      }

      "reject if file doesn't exists" in {
        files
          .updateLink(nxv + "other", projectRef, Uri.Path.Empty, 1, FileOptions())
          .rejectedWith[FileNotFound]
      }

      "reject if digest is not computed" in {
        files
          .updateLink("file2", projectRef, Uri.Path.Empty, 3, FileOptions())
          .rejectedWith[DigestNotComputed]
      }

      "reject if storage does not exist" in {
        val storage = nxv + "other-storage"
        files
          .updateLink("file1", projectRef, Uri.Path.Empty, 2, FileOptions(storage))
          .rejected shouldEqual
          WrappedStorageRejection(StorageNotFound(storage, projectRef))
      }

      "reject if project does not exist" in {
        val projectRef = ProjectRef(org, Label.unsafe("other"))

        files.updateLink(file1, projectRef, Uri.Path.Empty, 2, FileOptions()).rejectedWith[ProjectContextRejection]
      }

      "reject if project is deprecated" in {
        files
          .updateLink(file1, deprecatedProject.ref, Uri.Path.Empty, 2, FileOptions())
          .rejectedWith[ProjectContextRejection]
      }
    }

    "tagging a file" should {

      "succeed" in {
        val expected = mkResource(file1, projectRef, diskRev, attributes(), rev = 3, tags = Tags(tag -> 1))
        val actual   = files.tag(file1, projectRef, tag, tagRev = 1, 2).accepted
        actual shouldEqual expected
      }

      "reject if file doesn't exists" in {
        files.tag(nxv + "other", projectRef, tag, tagRev = 1, 3).rejectedWith[FileNotFound]
      }

      "reject if project does not exist" in {
        val projectRef = ProjectRef(org, Label.unsafe("other"))

        files.tag(rdId, projectRef, tag, tagRev = 2, 4).rejectedWith[ProjectContextRejection]
      }

      "reject if project is deprecated" in {
        files.tag(rdId, deprecatedProject.ref, tag, tagRev = 2, 4).rejectedWith[ProjectContextRejection]
      }
    }

    "deleting a tag" should {
      "succeed" in {
        val expected = mkResource(file1, projectRef, diskRev, attributes(), rev = 4)
        val actual   = files.deleteTag(file1, projectRef, tag, 3).accepted
        actual shouldEqual expected
      }
      "reject if the file doesn't exist" in {
        files.deleteTag(nxv + "other", projectRef, tag, 1).rejectedWith[FileNotFound]
      }
      "reject if the revision passed is incorrect" in {
        files.deleteTag(file1, projectRef, tag, 3).rejected shouldEqual IncorrectRev(expected = 4, provided = 3)
      }
      "reject if the tag doesn't exist" in {
        files.deleteTag(file1, projectRef, UserTag.unsafe("unknown"), 5).rejected
      }
    }

    "deprecating a file" should {

      "succeed" in {
        val expected = mkResource(file1, projectRef, diskRev, attributes(), rev = 5, deprecated = true)
        val actual   = files.deprecate(file1, projectRef, 4).accepted
        actual shouldEqual expected
      }

      "reject if file doesn't exists" in {
        files.deprecate(nxv + "other", projectRef, 1).rejectedWith[FileNotFound]
      }

      "reject if the revision passed is incorrect" in {
        files.deprecate(file1, projectRef, 3).rejected shouldEqual
          IncorrectRev(provided = 3, expected = 5)
      }

      "reject if project does not exist" in {
        val projectRef = ProjectRef(org, Label.unsafe("other"))

        files.deprecate(file1, projectRef, 1).rejectedWith[ProjectContextRejection]
      }

      "reject if project is deprecated" in {
        files.deprecate(file1, deprecatedProject.ref, 1).rejectedWith[ProjectContextRejection]
      }

      "allow tagging after deprecation" in {
        val expected =
          mkResource(file1, projectRef, diskRev, attributes(), rev = 6, tags = Tags(tag -> 4), deprecated = true)
        val actual   = files.tag(file1, projectRef, tag, tagRev = 4, 5).accepted
        actual shouldEqual expected
      }

    }

    "fetching a file" should {
      val resourceRev1 = mkResource(file1, projectRef, diskRev, attributes("myfile.txt"))
      val resourceRev4 = mkResource(file1, projectRef, diskRev, attributes(), rev = 4)
      val resourceRev6 =
        mkResource(file1, projectRef, diskRev, attributes(), rev = 6, tags = Tags(tag -> 4), deprecated = true)

      "succeed" in {
        files.fetch(file1, projectRef).accepted shouldEqual resourceRev6
      }

      "succeed by tag" in {
        files.fetch(IdSegmentRef(file1, tag), projectRef).accepted shouldEqual resourceRev4
      }

      "succeed by rev" in {
        files.fetch(IdSegmentRef(file1, 6), projectRef).accepted shouldEqual resourceRev6
        files.fetch(IdSegmentRef(file1, 1), projectRef).accepted shouldEqual resourceRev1
      }

      "reject if tag does not exist" in {
        val otherTag = UserTag.unsafe("other")
        files.fetch(IdSegmentRef(file1, otherTag), projectRef).rejected shouldEqual TagNotFound(otherTag)
      }

      "reject if revision does not exist" in {
        files.fetch(IdSegmentRef(file1, 8), projectRef).rejected shouldEqual
          RevisionNotFound(provided = 8, current = 6)
      }

      "fail if it doesn't exist" in {
        val notFound = nxv + "notFound"
        files.fetch(notFound, projectRef).rejectedWith[FileNotFound]
        files.fetch(IdSegmentRef(notFound, tag), projectRef).rejectedWith[FileNotFound]
        files.fetch(IdSegmentRef(notFound, 2), projectRef).rejectedWith[FileNotFound]
      }

      "reject if project does not exist" in {
        val projectRef = ProjectRef(org, Label.unsafe("other"))
        files.fetch(rdId, projectRef).rejectedWith[ProjectContextRejection]
      }

    }

    def consumeContent(response: FileResponse): String = {
      consume(response.content.accepted)
    }

    "fetching a file content" should {

      "succeed" in {
        val response = files.fetchContent(file1, projectRef).accepted
        consumeContent(response) shouldEqual content
        response.metadata.filename shouldEqual "file.txt"
        response.metadata.contentType shouldEqual `text/plain(UTF-8)`
      }

      "succeed by tag" in {
        val response = files.fetchContent(IdSegmentRef(file1, tag), projectRef).accepted
        consumeContent(response) shouldEqual content
        response.metadata.filename shouldEqual "file.txt"
        response.metadata.contentType shouldEqual `text/plain(UTF-8)`
      }

      "succeed by rev" in {
        val response = files.fetchContent(IdSegmentRef(file1, 1), projectRef).accepted
        consumeContent(response) shouldEqual content
        response.metadata.filename shouldEqual "myfile.txt"
        response.metadata.contentType shouldEqual `text/plain(UTF-8)`
      }

      "reject if tag does not exist" in {
        val otherTag = UserTag.unsafe("other")
        files.fetchContent(IdSegmentRef(file1, otherTag), projectRef).rejected shouldEqual TagNotFound(otherTag)
      }

      "reject if revision does not exist" in {
        files.fetchContent(IdSegmentRef(file1, 8), projectRef).rejected shouldEqual
          RevisionNotFound(provided = 8, current = 6)
      }

      "fail if it doesn't exist" in {
        val notFound = nxv + "notFound"
        files.fetchContent(notFound, projectRef).rejectedWith[FileNotFound]
        files.fetchContent(IdSegmentRef(notFound, tag), projectRef).rejectedWith[FileNotFound]
        files.fetchContent(IdSegmentRef(notFound, 2), projectRef).rejectedWith[FileNotFound]
      }

      "reject if project does not exist" in {
        val projectRef = ProjectRef(org, Label.unsafe("other"))
        files.fetchContent(rdId, projectRef).rejectedWith[ProjectContextRejection]
      }

    }
  }

}
