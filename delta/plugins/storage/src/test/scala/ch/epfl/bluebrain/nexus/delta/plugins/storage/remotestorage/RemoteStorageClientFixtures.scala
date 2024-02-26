package ch.epfl.bluebrain.nexus.delta.plugins.storage.remotestorage

import akka.actor.ActorSystem
import ch.epfl.bluebrain.nexus.delta.plugins.storage.remotestorage.RemoteStorageClientFixtures.{BucketName, RemoteStorageHostConfig}
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.remote.client.RemoteDiskStorageClient
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.remote.client.RemoteDiskStorageClient.RemoteDiskStorageClientImpl
import ch.epfl.bluebrain.nexus.delta.sdk.ConfigFixtures
import ch.epfl.bluebrain.nexus.delta.sdk.auth.{AuthTokenProvider, Credentials}
import ch.epfl.bluebrain.nexus.delta.sdk.http.{HttpClient, HttpClientConfig}
import ch.epfl.bluebrain.nexus.delta.sdk.model.BaseUri
import ch.epfl.bluebrain.nexus.testkit.scalatest.BaseSpec
import org.scalatest.BeforeAndAfterAll

import java.nio.file.attribute.PosixFilePermissions
import java.nio.file.{Files, Path}
import scala.concurrent.duration.DurationInt
import scala.jdk.DurationConverters.ScalaDurationOps

trait RemoteStorageClientFixtures extends BeforeAndAfterAll with ConfigFixtures { this: BaseSpec =>

  private val rwx             = PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwxrwxrwx"))
  private val tmpFolder: Path = Files.createTempDirectory("root", rwx)

  val storageVersion: String = "1.8.0-M12"

  protected val container: RemoteStorageContainer =
    new RemoteStorageContainer(storageVersion, tmpFolder)
      .withReuse(false)
      .withStartupTimeout(60.seconds.toJava)

  def hostConfig: RemoteStorageHostConfig =
    RemoteStorageHostConfig(container.getHost, container.getMappedPort(8080))

  def init(implicit as: ActorSystem): RemoteDiskStorageClient = {
    implicit val httpConfig: HttpClientConfig = httpClientConfig
    val httpClient: HttpClient                = HttpClient()
    val authTokenProvider: AuthTokenProvider  = AuthTokenProvider.anonymousForTest
    val baseUri                               = BaseUri(hostConfig.endpoint).rightValue
    new RemoteDiskStorageClientImpl(httpClient, authTokenProvider, baseUri, Credentials.Anonymous)
  }

  override def beforeAll(): Unit = {
    super.beforeAll()

    val bucket      = Files.createDirectory(tmpFolder.resolve(BucketName), rwx)
    val bucketNexus = Files.createDirectory(bucket.resolve("nexus"), rwx)
    val my          = Files.createDirectory(bucket.resolve("my"), rwx)

    (1 to 5).map(idx => s"file-$idx.txt").foreach { fileName =>
      val path = Files.createFile(my.resolve(fileName), rwx)
      path.toFile.setWritable(true, false)
      Files.writeString(path, "file content")
    }
    List(bucket, bucketNexus, my).foreach { path =>
      path.toFile.setWritable(true, false)
    }

    container.start()
  }

  override def afterAll(): Unit = {
    container.stop()
    super.afterAll()
  }
}

object RemoteStorageClientFixtures {
  val BucketName = "nexustest"
  val Content    = "file content"
  val Digest     = "e0ac3601005dfa1864f5392aabaf7d898b1b5bab854f1acb4491bcd806b76b0c"

  final case class RemoteStorageHostConfig(host: String, port: Int) {
    def endpoint: String = s"http://$host:$port/v1"
  }
}
