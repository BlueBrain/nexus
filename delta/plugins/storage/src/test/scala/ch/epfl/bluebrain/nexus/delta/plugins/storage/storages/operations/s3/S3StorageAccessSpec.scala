package ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.s3

import akka.actor.ActorSystem
import akka.testkit.TestKit
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.StorageRejection.StorageNotAccessible
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.StorageValue.S3StorageValue
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.{DigestAlgorithm, Secret}
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.s3.MinioDocker._
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.s3.MinioSpec._
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.permissions.{read, write}
import ch.epfl.bluebrain.nexus.delta.sdk.syntax._
import ch.epfl.bluebrain.nexus.testkit.{IOValues, TestHelpers}
import org.scalatest.{BeforeAndAfterAll, DoNotDiscover}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike
import software.amazon.awssdk.regions.Region

@DoNotDiscover
class S3StorageAccessSpec
    extends TestKit(ActorSystem("S3StorageAccessSpec"))
    with AnyWordSpecLike
    with Matchers
    with IOValues
    with TestHelpers
    with BeforeAndAfterAll {

  private val storage = S3StorageValue(
    default = false,
    algorithm = DigestAlgorithm.default,
    bucket = "bucket",
    endpoint = Some(s"http://$VirtualHost:$MinioServicePort"),
    accessKey = Some(Secret(AccessKey)),
    secretKey = Some(Secret(SecretKey)),
    region = Some(Region.EU_CENTRAL_1),
    readPermission = read,
    writePermission = write,
    maxFileSize = 20
  )

  override protected def beforeAll(): Unit =
    createBucket(storage).hideErrors.accepted

  override protected def afterAll(): Unit =
    deleteBucket(storage).hideErrors.accepted

  "An S33Storage access operations" should {
    val iri = iri"http://localhost/s3"

    val access = new S3StorageAccess()

    "succeed verifying the bucket" in {
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
