package ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.s3

import akka.actor.ActorSystem
import akka.http.scaladsl.model.HttpEntity
import cats.effect.IO
import ch.epfl.bluebrain.nexus.delta.kernel.utils.UUIDF
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.StorageFixtures
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.DigestAlgorithm
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.Storage.S3Storage
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.StorageValue.S3StorageValue
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.s3.client.S3StorageClient
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.AkkaSourceHelpers
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.permissions.{read, write}
import ch.epfl.bluebrain.nexus.delta.rdf.syntax.iriStringContextSyntax
import ch.epfl.bluebrain.nexus.delta.sdk.actor.ActorSystemSetup
import ch.epfl.bluebrain.nexus.delta.sourcing.model.ProjectRef
import ch.epfl.bluebrain.nexus.testkit.mu.NexusSuite
import io.circe.Json
import io.laserdisc.pure.s3.tagless.S3AsyncClientOp
import munit.AnyFixture

import java.util.UUID
import scala.concurrent.duration.{Duration, DurationInt}

class S3StorageFetchSaveSpec
    extends NexusSuite
    with StorageFixtures
    with ActorSystemSetup.Fixture
    with LocalStackS3StorageClient.Fixture
    with AkkaSourceHelpers
    with S3Helpers {

  override def munitIOTimeout: Duration = 120.seconds

  override def munitFixtures: Seq[AnyFixture[_]] = List(localStackS3Client, actorSystem)

  private val uuid                  = UUID.fromString("8049ba90-7cc6-4de5-93a1-802c04200dcc")
  implicit private val uuidf: UUIDF = UUIDF.fixed(uuid)

  implicit private lazy val (s3StorageClient: S3StorageClient, underlying: S3AsyncClientOp[IO], _) =
    localStackS3Client()
  implicit private lazy val as: ActorSystem                                                        = actorSystem()
  private lazy val s3Save                                                                          = new S3StorageSaveFile(s3StorageClient)

  test("Save and fetch an object in a bucket") {
    givenAnS3Bucket { bucket =>
      val s3Fetch      = new S3StorageFetchFile(s3StorageClient, bucket)
      val storageValue = S3StorageValue(
        default = false,
        algorithm = DigestAlgorithm.default,
        bucket = bucket,
        readPermission = read,
        writePermission = write,
        maxFileSize = 20
      )

      val iri     = iri"http://localhost/s3"
      val project = ProjectRef.unsafe("org", "project")
      val storage = S3Storage(iri, project, storageValue, Json.obj())

      val filename = "myfile.txt"
      val content  = genString()
      val entity   = HttpEntity(content)

      val result = for {
        attr   <- s3Save.apply(storage, filename, entity)
        source <- s3Fetch.apply(attr.path)
      } yield consume(source)

      assertIO(result, content)
    }
  }
}
