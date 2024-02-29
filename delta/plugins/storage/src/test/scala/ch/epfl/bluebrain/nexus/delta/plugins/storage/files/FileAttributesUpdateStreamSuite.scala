package ch.epfl.bluebrain.nexus.delta.plugins.storage.files

import akka.http.scaladsl.model.{ContentTypes, Uri}
import cats.effect.IO
import cats.effect.kernel.Ref
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.generators.FileGen
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model.Digest.{ComputedDigest, NotComputedDigest}
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model.FileAttributes.FileAttributesOrigin.Client
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model.FileRejection.DigestNotComputed
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model.{FileAttributes, FileState}
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.StorageFixtures
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.Storage.RemoteDiskStorage
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.{DigestAlgorithm, StorageType}
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.nxv
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{Label, ProjectRef, ResourceRef}
import ch.epfl.bluebrain.nexus.testkit.mu.NexusSuite
import fs2.Stream

import java.util.UUID
import scala.concurrent.duration._

class FileAttributesUpdateStreamSuite extends NexusSuite with StorageFixtures {

  private val project = ProjectRef.unsafe("org", "projg")
  private val id      = nxv + "file"

  private val storage = RemoteDiskStorage(nxv + "remote", project, remoteVal, json"""{"disk": "value"}""")

  private val storageRef = ResourceRef.Revision(storage.id, 1)

  private val mediaType  = Some(ContentTypes.`text/plain(UTF-8)`)
  private val attributes = FileAttributes(
    UUID.randomUUID(),
    location = "http://localhost/my/file.txt",
    path = Uri.Path("my/file.txt"),
    filename = "myfile.txt",
    mediaType = mediaType,
    keywords = Map(Label.unsafe("key") -> "value"),
    None,
    None,
    bytes = 10,
    NotComputedDigest,
    Client
  )
  private val validFile  =
    FileGen.state(id, project, storageRef, attributes, storageType = StorageType.RemoteDiskStorage)

  private def updateStream(updateAttributes: IO[Unit]) = new FileAttributesUpdateStream.Impl(
    _ => Stream.empty,
    (_, _) => IO.pure(storage),
    (_, _) => updateAttributes,
    50.millis
  )

  private def assertProcess(file: FileState, expectedAttempts: Int) =
    for {
      ref <- Ref.of[IO, Int](0)
      _   <- updateStream(ref.update(_ + 1)).processFile(file)
      _   <- ref.get.assertEquals(expectedAttempts)
    } yield ()

  private def assertSuccess(file: FileState) = assertProcess(file, 1)

  private def assertSkipped(file: FileState) = assertProcess(file, 0)

  test("A valid file is successfully processed.") {
    assertSuccess(validFile)
  }

  test("A deprecated file is skipped.") {
    val deprecated = validFile.copy(deprecated = true)
    assertSkipped(deprecated)
  }

  test("A local file is skipped.") {
    val localFile = validFile.copy(storageType = StorageType.DiskStorage)
    assertSkipped(localFile)
  }

  test("A file with a computed digest is skipped.") {
    val newAttributes    = attributes.copy(digest = ComputedDigest(DigestAlgorithm.default, "something"))
    val alreadyProcessed = validFile.copy(attributes = newAttributes)
    assertSkipped(alreadyProcessed)
  }

  test("A file is processed again when the digest is not yet computed.") {
    for {
      ref             <- Ref.of[IO, Int](0)
      updateAttributes = ref.updateAndGet(_ + 1).flatMap { attempt => IO.raiseWhen(attempt < 2)(DigestNotComputed(id)) }
      _               <- updateStream(updateAttributes).processFile(validFile)
      _               <- ref.get.assertEquals(2)
    } yield ()
  }

}
