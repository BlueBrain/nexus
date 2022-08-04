package ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.s3

import akka.actor.ActorSystem
import akka.testkit.TestKit
import ch.epfl.bluebrain.nexus.delta.kernel.Secret
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.StorageFixtures
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.DigestAlgorithm
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.StorageRejection.StorageNotAccessible
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.StorageValue.S3StorageValue
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.s3.MinioSpec._
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.permissions.{read, write}
import ch.epfl.bluebrain.nexus.delta.sdk.syntax._
import ch.epfl.bluebrain.nexus.testkit.minio.MinioDocker
import ch.epfl.bluebrain.nexus.testkit.minio.MinioDocker._
import ch.epfl.bluebrain.nexus.testkit.{IOValues, TestHelpers}
import org.scalatest.concurrent.Eventually
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike
import org.scalatest.{BeforeAndAfterAll, DoNotDiscover}
import software.amazon.awssdk.regions.Region

@DoNotDiscover
class S3StorageAccessSpec(docker: MinioDocker)
    extends TestKit(ActorSystem("S3StorageAccessSpec"))
    with AnyWordSpecLike
    with Matchers
    with IOValues
    with Eventually
    with TestHelpers
    with StorageFixtures
    with BeforeAndAfterAll {

  private var storage: S3StorageValue = _

  override protected def beforeAll(): Unit = {
    storage = S3StorageValue(
      default = false,
      algorithm = DigestAlgorithm.default,
      bucket = "bucket",
      endpoint = Some(docker.hostConfig.endpoint),
      accessKey = Some(Secret(RootUser)),
      secretKey = Some(Secret(RootPassword)),
      region = Some(Region.EU_CENTRAL_1),
      readPermission = read,
      writePermission = write,
      maxFileSize = 20
    )
    createBucket(storage).hideErrors.accepted
  }

  override protected def afterAll(): Unit =
    deleteBucket(storage).hideErrors.accepted

  "An S3Storage access operations" should {
    val iri = iri"http://localhost/s3"

    val access = new S3StorageAccess()

    "succeed verifying the bucket" in eventually {
      access(iri, storage).accepted
    }

    "fail on wrong credentials" in {
      access(iri, storage.copy(secretKey = Some(Secret("other")))).rejectedWith[StorageNotAccessible]
    }

    "fail when bucket does not exist" in {
      access(iri, storage.copy(bucket = "other")).rejectedWith[StorageNotAccessible]
    }

  }
}
