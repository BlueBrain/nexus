package ch.epfl.bluebrain.nexus.delta.plugins.storage.files

import akka.actor.typed.scaladsl.adapter._
import akka.actor.{typed, ActorSystem}
import akka.http.scaladsl.model.ContentTypes.`text/plain(UTF-8)`
import akka.http.scaladsl.model.Uri
import akka.testkit.TestKit
import ch.epfl.bluebrain.nexus.delta.kernel.Secret
import ch.epfl.bluebrain.nexus.delta.plugins.storage.RemoteContextResolutionFixture
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model.Digest.NotComputedDigest
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model.FileAttributes.FileAttributesOrigin.Storage
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model.FileRejection
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model.FileRejection._
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.StorageRejection
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.StorageRejection.StorageNotFound
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.StorageStatsCollection.StorageStatEntry
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.StorageType.{RemoteDiskStorage => RemoteStorageType}
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.AkkaSourceHelpers
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.{StorageFixtures, Storages, StoragesConfig, StoragesStatisticsSetup}
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.nxv
import ch.epfl.bluebrain.nexus.delta.sdk.ConfigFixtures
import ch.epfl.bluebrain.nexus.delta.sdk.acls.AclSimpleCheck
import ch.epfl.bluebrain.nexus.delta.sdk.acls.model.AclAddress
import ch.epfl.bluebrain.nexus.delta.sdk.http.HttpClient
import ch.epfl.bluebrain.nexus.delta.sdk.identities.model.{Caller, ServiceAccount}
import ch.epfl.bluebrain.nexus.delta.sdk.implicits._
import ch.epfl.bluebrain.nexus.delta.sdk.model._
import ch.epfl.bluebrain.nexus.delta.sdk.permissions.Permissions
import ch.epfl.bluebrain.nexus.delta.sdk.permissions.model.Permission
import ch.epfl.bluebrain.nexus.delta.sdk.projects.FetchContextDummy
import ch.epfl.bluebrain.nexus.delta.sdk.resolvers.ResolverContextResolution
import ch.epfl.bluebrain.nexus.delta.sdk.resolvers.model.ResourceResolutionReport
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.{Anonymous, Authenticated, Group, User}
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Tag.UserTag
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{Label, ProjectRef, ResourceRef}
import ch.epfl.bluebrain.nexus.testkit.remotestorage.RemoteStorageDocker
import ch.epfl.bluebrain.nexus.testkit.{CirceLiteral, DoobieScalaTestFixture, IOFixedClock, IOValues}
import monix.bio.IO
import monix.execution.Scheduler
import org.scalatest.concurrent.Eventually
import org.scalatest.matchers.should.Matchers
import org.scalatest.{DoNotDiscover, Inspectors, OptionValues}

import java.time.Instant

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

    val storageStatistics       =
      StoragesStatisticsSetup.init(Map(project -> Map(diskId -> StorageStatEntry(10L, 100L, Some(Instant.EPOCH)))))

    lazy val storages: Storages = Storages(
      fetchContext.mapRejection(StorageRejection.ProjectContextRejection),
      new ResolverContextResolution(rcr, (_, _, _) => IO.raiseError(ResourceResolutionReport())),
      IO.pure(allowedPerms),
      (_, _) => IO.unit,
      crypto,
      xas,
      StoragesConfig(eventLogConfig, pagination, config),
      ServiceAccount(User("nexus-sa", Label.unsafe("sa")))
    ).accepted
    lazy val files: Files       = Files(
      fetchContext.mapRejection(FileRejection.ProjectContextRejection),
      aclCheck,
      storages,
      storageStatistics,
      xas,
      cfg,
      FilesConfig(eventLogConfig)
    )

    "creating a file" should {

      "create storages for files" in {
        val payload = diskFieldsJson.map(_ deepMerge json"""{"capacity": 320, "maxFileSize": 300, "volume": "$path"}""")
        storages.create(diskId, projectRef, payload).accepted

        val payload2 =
          json"""{"@type": "RemoteDiskStorage", "endpoint": "${docker.hostConfig.endpoint}", "folder": "${RemoteStorageDocker.BucketName}", "readPermission": "$otherRead", "writePermission": "$otherWrite", "maxFileSize": 300, "default": false}"""
        storages.create(remoteId, projectRef, Secret(payload2)).accepted
      }

      "succeed with the id passed" in {
        files
          .create("file1", Some(diskId), projectRef, entity("myfile.txt"))
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
          .create("file2", Some(remoteId), projectRef, entity())
          .rejectedWith[AuthorizationFailed]
      }

      "reject if file id already exists" in {
        files.create("file1", None, projectRef, entity()).rejected shouldEqual
          ResourceAlreadyExists(file1, projectRef)
      }

      val aliceCaller = Caller(alice, Set(alice, Group("mygroup", realm), Authenticated(realm)))

      "reject if the file exceeds max file size for the storage" in {
        files
          .create("file-too-long", Some(remoteId), projectRef, randomEntity("large_file", 280))(aliceCaller)
          .rejected shouldEqual FileTooLarge(300L, None)
      }

      "reject if the file exceeds the remaining available space on the storage" in {
        files
          .create("file-too-long", Some(diskId), projectRef, randomEntity("large_file", 250))
          .rejected shouldEqual FileTooLarge(300L, Some(220))
      }

      "reject if storage does not exist" in {
        val storage = nxv + "other-storage"
        files.create("file2", Some(storage), projectRef, entity()).rejected shouldEqual
          WrappedStorageRejection(StorageNotFound(storage, projectRef))
      }

      "reject if project does not exist" in {
        val projectRef = ProjectRef(org, Label.unsafe("other"))
        files.create(None, projectRef, entity()).rejectedWith[ProjectContextRejection]
      }

      "reject if project is deprecated" in {
        files.create(Some(diskId), deprecatedProject.ref, entity()).rejectedWith[ProjectContextRejection]
      }
    }

    "linking a file" should {

      "reject if no write permissions" in {
        files
          .createLink("file2", Some(remoteId), projectRef, None, None, Uri.Path.Empty)
          .rejectedWith[AuthorizationFailed]
      }

      "succeed with the id passed" in {
        aclCheck.append(AclAddress.Root, bob -> Set(otherWrite)).accepted
        val path     = Uri.Path("my/file-3.txt")
        val tempAttr = attributes("myfile.txt").copy(digest = NotComputedDigest)
        val attr     =
          tempAttr.copy(location = s"file:///app/nexustest/nexus/${tempAttr.path}", origin = Storage, mediaType = None)
        files
          .createLink("file2", Some(remoteId), projectRef, Some("myfile.txt"), None, path)
          .accepted shouldEqual
          FileGen.resourceFor(file2, projectRef, remoteRev, attr, RemoteStorageType, createdBy = bob, updatedBy = bob)
      }

      "reject if no filename" in {
        files
          .createLink("file3", Some(remoteId), projectRef, None, None, Uri.Path("a/b/"))
          .rejectedWith[InvalidFileLink]
      }

      "reject if file id already exists" in {
        files
          .createLink("file2", Some(remoteId), projectRef, None, None, Uri.Path.Empty)
          .rejected shouldEqual
          ResourceAlreadyExists(file2, projectRef)
      }

      "reject if storage does not exist" in {
        val storage = nxv + "other-storage"
        files
          .createLink("file3", Some(storage), projectRef, None, None, Uri.Path.Empty)
          .rejected shouldEqual
          WrappedStorageRejection(StorageNotFound(storage, projectRef))
      }

      "reject if project does not exist" in {
        val projectRef = ProjectRef(org, Label.unsafe("other"))
        files.createLink(None, projectRef, None, None, Uri.Path.Empty).rejectedWith[ProjectContextRejection]
      }

      "reject if project is deprecated" in {
        files
          .createLink(Some(remoteId), deprecatedProject.ref, None, None, Uri.Path.Empty)
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
          .updateLink("file2", Some(remoteId), projectRef, None, Some(`text/plain(UTF-8)`), path, 2)
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
          .updateLink(nxv + "other", None, projectRef, None, None, Uri.Path.Empty, 1)
          .rejectedWith[FileNotFound]
      }

      "reject if digest is not computed" in {
        files
          .updateLink("file2", None, projectRef, None, None, Uri.Path.Empty, 3)
          .rejectedWith[DigestNotComputed]
      }

      "reject if storage does not exist" in {
        val storage = nxv + "other-storage"
        files
          .updateLink("file1", Some(storage), projectRef, None, None, Uri.Path.Empty, 2)
          .rejected shouldEqual
          WrappedStorageRejection(StorageNotFound(storage, projectRef))
      }

      "reject if project does not exist" in {
        val projectRef = ProjectRef(org, Label.unsafe("other"))

        files.updateLink(file1, None, projectRef, None, None, Uri.Path.Empty, 2).rejectedWith[ProjectContextRejection]
      }

      "reject if project is deprecated" in {
        files
          .updateLink(file1, None, deprecatedProject.ref, None, None, Uri.Path.Empty, 2)
          .rejectedWith[ProjectContextRejection]
      }
    }

    "updating file attributes" should {

      val attr = attributes()

      "succeed" in {
        val attr = attributes(size = 20)
        files
          .updateAttributes("file1", projectRef, attr.mediaType, attr.bytes, attr.digest, 2)
          .accepted shouldEqual
          FileGen.resourceFor(file1, projectRef, diskRev, attr, rev = 3, createdBy = bob, updatedBy = bob)
      }

      "reject if file doesn't exists" in {
        files
          .updateAttributes(nxv + "other", projectRef, attr.mediaType, attr.bytes, attr.digest, 3)
          .rejectedWith[FileNotFound]
      }

      "reject if project does not exist" in {
        val projectRef = ProjectRef(org, Label.unsafe("other"))

        files
          .updateAttributes("file1", projectRef, attr.mediaType, attr.bytes, attr.digest, 3)
          .rejectedWith[ProjectContextRejection]
      }

      "reject if project is deprecated" in {
        files
          .updateAttributes("file1", deprecatedProject.ref, attr.mediaType, attr.bytes, attr.digest, 3)
          .rejectedWith[ProjectContextRejection]
      }
    }

    "tagging a file" should {

      "succeed" in {
        files.tag(file1, projectRef, tag, tagRev = 1, 3).accepted shouldEqual
          FileGen.resourceFor(
            file1,
            projectRef,
            diskRev,
            attributes(size = 20),
            rev = 4,
            tags = Tags(tag -> 1),
            createdBy = bob,
            updatedBy = bob
          )
      }

      "reject if file doesn't exists" in {
        files.tag(nxv + "other", projectRef, tag, tagRev = 1, 4).rejectedWith[FileNotFound]
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
        files.deleteTag(file1, projectRef, tag, 4).accepted shouldEqual
          FileGen.resourceFor(
            file1,
            projectRef,
            diskRev,
            attributes(size = 20),
            rev = 5,
            createdBy = bob,
            updatedBy = bob
          )
      }
      "reject if the file doesn't exist" in {
        files.deleteTag(nxv + "other", projectRef, tag, 1).rejectedWith[FileNotFound]
      }
      "reject if the revision passed is incorrect" in {
        files.deleteTag(file1, projectRef, tag, 4).rejected shouldEqual IncorrectRev(expected = 5, provided = 4)
      }
      "reject if the tag doesn't exist" in {
        files.deleteTag(file1, projectRef, UserTag.unsafe("unknown"), 5).rejected
      }
    }

    "deprecating a file" should {

      "succeed" in {
        files.deprecate(file1, projectRef, 5).accepted shouldEqual
          FileGen.resourceFor(
            file1,
            projectRef,
            diskRev,
            attributes(size = 20),
            rev = 6,
            deprecated = true,
            createdBy = bob,
            updatedBy = bob
          )
      }

      "reject if file doesn't exists" in {
        files.deprecate(nxv + "other", projectRef, 1).rejectedWith[FileNotFound]
      }

      "reject if the revision passed is incorrect" in {
        files.deprecate(file1, projectRef, 3).rejected shouldEqual
          IncorrectRev(provided = 3, expected = 6)
      }

      "reject if project does not exist" in {
        val projectRef = ProjectRef(org, Label.unsafe("other"))

        files.deprecate(file1, projectRef, 1).rejectedWith[ProjectContextRejection]
      }

      "reject if project is deprecated" in {
        files.deprecate(file1, deprecatedProject.ref, 1).rejectedWith[ProjectContextRejection]
      }

      "allow tagging after deprecation" in {
        files.tag(file1, projectRef, tag, tagRev = 4, 6).accepted shouldEqual
          FileGen.resourceFor(
            file1,
            projectRef,
            diskRev,
            attributes(size = 20),
            rev = 7,
            tags = Tags(tag -> 4),
            createdBy = bob,
            updatedBy = bob,
            deprecated = true
          )
      }

    }

    "fetching a file" should {
      val resourceRev1 =
        FileGen.resourceFor(file1, projectRef, diskRev, attributes("myfile.txt"), createdBy = bob, updatedBy = bob)

      val resourceRev4 = FileGen.resourceFor(
        file1,
        projectRef,
        diskRev,
        attributes(size = 20),
        rev = 4,
        tags = Tags(tag -> 1),
        createdBy = bob,
        updatedBy = bob
      )

      val resourceRev6 = FileGen.resourceFor(
        file1,
        projectRef,
        diskRev,
        attributes(size = 20),
        rev = 7,
        tags = Tags(tag -> 4),
        deprecated = true,
        createdBy = bob,
        updatedBy = bob
      )

      "succeed" in {
        files.fetch(file1, projectRef).accepted shouldEqual resourceRev6
      }

      "succeed by tag" in {
        files.fetch(IdSegmentRef(file1, tag), projectRef).accepted shouldEqual resourceRev4
      }

      "succeed by rev" in {
        files.fetch(IdSegmentRef(file1, 7), projectRef).accepted shouldEqual resourceRev6
        files.fetch(IdSegmentRef(file1, 1), projectRef).accepted shouldEqual resourceRev1
      }

      "reject if tag does not exist" in {
        val otherTag = UserTag.unsafe("other")
        files.fetch(IdSegmentRef(file1, otherTag), projectRef).rejected shouldEqual TagNotFound(otherTag)
      }

      "reject if revision does not exist" in {
        files.fetch(IdSegmentRef(file1, 8), projectRef).rejected shouldEqual
          RevisionNotFound(provided = 8, current = 7)
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
        response.filename shouldEqual "file.txt"
        response.contentType shouldEqual `text/plain(UTF-8)`
      }

      "succeed by rev" in {
        val response = files.fetchContent(IdSegmentRef(file1, 1), projectRef).accepted
        consume(response.content) shouldEqual content
        response.filename shouldEqual "myfile.txt"
        response.contentType shouldEqual `text/plain(UTF-8)`
      }

      "reject if tag does not exist" in {
        val otherTag = UserTag.unsafe("other")
        files.fetchContent(IdSegmentRef(file1, otherTag), projectRef).rejected shouldEqual TagNotFound(otherTag)
      }

      "reject if revision does not exist" in {
        files.fetchContent(IdSegmentRef(file1, 8), projectRef).rejected shouldEqual
          RevisionNotFound(provided = 8, current = 7)
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
