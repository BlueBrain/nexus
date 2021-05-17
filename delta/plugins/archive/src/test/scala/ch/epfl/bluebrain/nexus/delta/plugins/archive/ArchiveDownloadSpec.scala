package ch.epfl.bluebrain.nexus.delta.plugins.archive

import akka.stream.alpakka.file.scaladsl.Archive
import akka.stream.scaladsl.FileIO
import akka.util.ByteString
import ch.epfl.bluebrain.nexus.delta.kernel.utils.UrlUtils
import ch.epfl.bluebrain.nexus.delta.plugins.archive.ArchiveDownload.ArchiveDownloadImpl
import ch.epfl.bluebrain.nexus.delta.plugins.archive.model.ArchiveReference.{FileReference, ResourceReference}
import ch.epfl.bluebrain.nexus.delta.plugins.archive.model.ArchiveRejection.{AuthorizationFailed, ResourceNotFound}
import ch.epfl.bluebrain.nexus.delta.plugins.archive.model.ArchiveResourceRepresentation.{CompactedJsonLd, Dot, ExpandedJsonLd, NTriples, SourceJson}
import ch.epfl.bluebrain.nexus.delta.plugins.archive.model.ArchiveValue
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.{FileFixtures, Files, FilesSetup}
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.StorageFixtures
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.AbsolutePath
import ch.epfl.bluebrain.nexus.delta.plugins.storage.{ConfigFixtures, RemoteContextResolutionFixture}
import ch.epfl.bluebrain.nexus.delta.rdf.utils.JsonKeyOrdering
import ch.epfl.bluebrain.nexus.delta.sdk.implicits._
import ch.epfl.bluebrain.nexus.delta.sdk.model.ResourceRef.Latest
import ch.epfl.bluebrain.nexus.delta.sdk.model.acls.AclAddress
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Identity.Subject
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.{Caller, Identity}
import ch.epfl.bluebrain.nexus.delta.sdk.model.{BaseUri, Label, NonEmptySet}
import ch.epfl.bluebrain.nexus.delta.sdk.testkit.{AbstractDBSpec, AclSetup}
import ch.epfl.bluebrain.nexus.delta.sdk.{AkkaSource, Permissions}
import io.circe.syntax.EncoderOps
import monix.execution.Scheduler
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.{CancelAfterFailure, Inspectors, TryValues}

import java.nio.file.{Files => JFiles}
import scala.concurrent.ExecutionContext
import scala.concurrent.duration._

class ArchiveDownloadSpec
    extends AbstractDBSpec
    with ScalaFutures
    with TryValues
    with Inspectors
    with CancelAfterFailure
    with ConfigFixtures
    with StorageFixtures
    with FileFixtures
    with RemoteContextResolutionFixture {

  implicit override def patienceConfig: PatienceConfig = PatienceConfig(3.seconds, 50.millis)

  implicit private val scheduler: Scheduler = Scheduler.global
  implicit val ec: ExecutionContext         = system.dispatcher

  implicit private val subject: Subject = Identity.User("user", Label.unsafe("realm"))
  implicit private val caller: Caller   = Caller.unsafe(subject)
  implicit private val baseUri: BaseUri = BaseUri("http://localhost", Label.unsafe("v1"))

  implicit private val jsonKeyOrdering: JsonKeyOrdering =
    JsonKeyOrdering.default(topKeys =
      List("@context", "@id", "@type", "reason", "details", "sourceId", "projectionId", "_total", "_results")
    )

  private val cfg = config.copy(
    disk = config.disk.copy(defaultMaxFileSize = 500, allowedVolumes = config.disk.allowedVolumes + path)
  )

  private val acls              = AclSetup
    .init(
      (
        subject,
        AclAddress.Root,
        Set(Permissions.resources.read, diskFields.readPermission.value, diskFields.writePermission.value)
      )
    )
    .accepted
  private val (files, storages) = FilesSetup.init(org, project, acls, cfg)
  private val storageJson       = diskFieldsJson.map(_ deepMerge json"""{"maxFileSize": 300, "volume": "$path"}""")
  storages.create(diskId, projectRef, storageJson).accepted

  private def archiveMapOf(source: AkkaSource): Map[String, String] = {
    val path   = JFiles.createTempFile("test", ".tar")
    source.runWith(FileIO.toPath(path)).futureValue
    val result = FileIO
      .fromPath(path)
      .via(Archive.tarReader())
      .mapAsync(1) { case (metadata, source) =>
        source
          .runFold(ByteString.empty) { case (bytes, elem) =>
            bytes ++ elem
          }
          .map { bytes =>
            (metadata.filePath, bytes.utf8String)
          }
      }
      .runFold(Map.empty[String, String]) { case (map, elem) =>
        map + elem
      }
      .futureValue
    result
  }

  "An ArchiveDownload" should {
    val id1   = iri"http://localhost/${genString()}"
    val file1 = files.create(id1, Some(diskId), project.ref, entity()).accepted

    val archiveDownload = new ArchiveDownloadImpl(List(Files.referenceExchange(files)), acls, files)

    "provide a tar for both resources and files" in {
      val value    = ArchiveValue.unsafe(
        NonEmptySet.of(
          ResourceReference(Latest(id1), None, None, None),
          FileReference(Latest(id1), None, None)
        )
      )
      val source   = archiveDownload.apply(value, project.ref, ignoreNotFound = false).accepted
      val result   = archiveMapOf(source)
      val expected = Map(
        s"${project.ref.toString}/${UrlUtils.encode(file1.id.toString)}.json" -> file1.toCompactedJsonLd.accepted.json.sort.spaces2,
        s"${project.ref.toString}/${file1.value.attributes.filename}"         -> content
      )
      result shouldEqual expected
    }

    "provide a tar for both resources and files with different paths and formats" in {
      val list = List(
        SourceJson      -> file1.value.asJson.sort.spaces2,
        CompactedJsonLd -> file1.toCompactedJsonLd.accepted.json.sort.spaces2,
        ExpandedJsonLd  -> file1.toExpandedJsonLd.accepted.json.sort.spaces2,
        NTriples        -> file1.toNTriples.accepted.value,
        Dot             -> file1.toDot.accepted.value
      )
      forAll(list) { case (repr, expectedString) =>
        val filePath     = AbsolutePath.apply(s"/${genString()}/file.txt").rightValue
        val resourcePath = AbsolutePath.apply(s"/${genString()}/file.json").rightValue
        val value        = ArchiveValue.unsafe(
          NonEmptySet.of(
            ResourceReference(Latest(id1), None, Some(resourcePath), Some(repr)),
            FileReference(Latest(id1), None, Some(filePath))
          )
        )
        val source       = archiveDownload.apply(value, project.ref, ignoreNotFound = false).accepted
        if (repr == Dot) {
          archiveMapOf(source)(resourcePath.value.toString).contains(s"""digraph "$id1"""") shouldEqual true
        } else if (repr == NTriples) {
          archiveMapOf(source)(resourcePath.value.toString).contains(s"""<$id1>""") shouldEqual true
        } else {
          val expected = Map(
            resourcePath.value.toString -> expectedString,
            filePath.value.toString     -> content
          )
          archiveMapOf(source) shouldEqual expected
        }
      }
    }

    "fail to provide a tar when a resource is not found" in {
      val value = ArchiveValue.unsafe(
        NonEmptySet.of(
          ResourceReference(Latest(iri"http://localhost/${genString()}"), None, None, None),
          FileReference(Latest(id1), None, None)
        )
      )
      archiveDownload.apply(value, project.ref, ignoreNotFound = false).rejectedWith[ResourceNotFound]
    }

    "fail to provide a tar when a file is not found" in {
      val value = ArchiveValue.unsafe(
        NonEmptySet.of(
          ResourceReference(Latest(id1), None, None, None),
          FileReference(Latest(iri"http://localhost/${genString()}"), None, None)
        )
      )
      archiveDownload.apply(value, project.ref, ignoreNotFound = false).rejectedWith[ResourceNotFound]
    }

    "ignore missing resources" in {
      val value    = ArchiveValue.unsafe(
        NonEmptySet.of(
          ResourceReference(Latest(iri"http://localhost/${genString()}"), None, None, None),
          FileReference(Latest(id1), None, None)
        )
      )
      val source   = archiveDownload.apply(value, project.ref, ignoreNotFound = true).accepted
      val result   = archiveMapOf(source)
      val expected = Map(
        s"${project.ref.toString}/${file1.value.attributes.filename}" -> content
      )
      result shouldEqual expected
    }

    "ignore missing files" in {
      val value    = ArchiveValue.unsafe(
        NonEmptySet.of(
          ResourceReference(Latest(id1), None, None, None),
          FileReference(Latest(iri"http://localhost/${genString()}"), None, None)
        )
      )
      val source   = archiveDownload.apply(value, project.ref, ignoreNotFound = true).accepted
      val result   = archiveMapOf(source)
      val expected = Map(
        s"${project.ref.toString}/${UrlUtils.encode(file1.id.toString)}.json" -> file1.toCompactedJsonLd.accepted.json.sort.spaces2
      )
      result shouldEqual expected
    }

    "fail to provide a tar when access to a resource is not found" in {
      val value = ArchiveValue.unsafe(
        NonEmptySet.of(ResourceReference(Latest(id1), None, None, None))
      )
      archiveDownload
        .apply(value, project.ref, ignoreNotFound = true)(Caller.Anonymous)
        .rejectedWith[AuthorizationFailed]
    }

    "fail to provide a tar when access to a file is not found" in {
      val value = ArchiveValue.unsafe(
        NonEmptySet.of(FileReference(Latest(id1), None, None))
      )
      archiveDownload
        .apply(value, project.ref, ignoreNotFound = true)(Caller.Anonymous)
        .rejectedWith[AuthorizationFailed]
    }
  }
}
