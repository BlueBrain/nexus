//package ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.s3
//
//import akka.actor.ActorSystem
//import akka.http.scaladsl.model.Uri
//import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.StorageFixtures
//import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.DigestAlgorithm
//import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.StorageRejection.StorageNotAccessible
//import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.StorageValue.S3StorageValue
//import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.s3.MinioSpec._
//import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.permissions.{read, write}
//import ch.epfl.bluebrain.nexus.delta.sdk.akka.ActorSystemSetup
//import ch.epfl.bluebrain.nexus.delta.sdk.syntax._
//import ch.epfl.bluebrain.nexus.testkit.minio.LocalStackS3
//import ch.epfl.bluebrain.nexus.testkit.mu.NexusSuite
//import ch.epfl.bluebrain.nexus.testkit.mu.ce.PatienceConfig
//import com.dimafeng.testcontainers.LocalStackV2Container
//import munit.AnyFixture
//import software.amazon.awssdk.regions.Region
//
//import scala.concurrent.duration.{Duration, DurationInt}
//
//class S3StorageAccessSpecLocalStack
//    extends NexusSuite
//    with StorageFixtures
//    with LocalStackS3.Fixture
//    with ActorSystemSetup.Fixture {
//
//  implicit private val patience: PatienceConfig = PatienceConfig(20.seconds, 10.milliseconds)
//
//  override def munitIOTimeout: Duration = 60.seconds
//
//  override def munitFixtures: Seq[AnyFixture[_]] = List(localStackS3, actorSystem)
//
//  private lazy val s3Container: LocalStackV2Container = localStackS3()
//  implicit private lazy val as: ActorSystem           = actorSystem()
//
//  private var storage: S3StorageValue = _
//
//  override def afterAll(): Unit = {
////    deleteBucket(storage).accepted
//    super.afterAll()
//  }
//
//  private lazy val access = new S3StorageAccess(config)
//  private val iri         = iri"http://localhost/s3"
//
//  test("List objects in an existing bucket") {
//    val endpointUri = Uri(s3Container.endpointOverride(LocalStackS3.ServiceType).toString)
//    storage = S3StorageValue(
//      default = false,
//      algorithm = DigestAlgorithm.default,
//      bucket = "bucket",
//      endpoint = Some(endpointUri),
//      region = Some(Region.US_EAST_1),
//      readPermission = read,
//      writePermission = write,
//      maxFileSize = 20
//    )
//    (createBucket(storage) *>
//      access(iri, storage)).eventually
//  }
//
//  test("Fail to list objects when bucket doesn't exist") {
//    access(iri, storage.copy(bucket = "other")).intercept[StorageNotAccessible]
//  }
//}
