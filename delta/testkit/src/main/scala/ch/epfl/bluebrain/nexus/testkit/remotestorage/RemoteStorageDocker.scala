package ch.epfl.bluebrain.nexus.testkit.remotestorage

import ch.epfl.bluebrain.nexus.testkit.remotestorage.RemoteStorageDocker.{BucketName, RemoteStorageHostConfig}
import org.scalatest.{BeforeAndAfterAll, Suite}

import java.nio.file.attribute.PosixFilePermissions
import java.nio.file.{Files, Path}
import scala.concurrent.duration.DurationInt
import scala.jdk.DurationConverters.ScalaDurationOps

trait RemoteStorageDocker extends BeforeAndAfterAll { this: Suite =>

  private val rwx             = PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwxrwxrwx"))
  private val tmpFolder: Path = Files.createTempDirectory("root", rwx)

  protected val container: RemoteStorageContainer =
    new RemoteStorageContainer(tmpFolder)
      .withReuse(false)
      .withStartupTimeout(60.seconds.toJava)

  def hostConfig: RemoteStorageHostConfig =
    RemoteStorageHostConfig(container.getHost, container.getMappedPort(8080))

  override def beforeAll(): Unit = {
    super.beforeAll()

    val bucket      = Files.createDirectory(tmpFolder.resolve(BucketName), rwx)
    val bucketNexus = Files.createDirectory(bucket.resolve("nexus"), rwx)
    val my          = Files.createDirectory(bucket.resolve("my"), rwx)

    (1 to 4).map(idx => s"file-$idx.txt").foreach { fileName =>
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

object RemoteStorageDocker {
  val BucketName = "nexustest"
  val Content    = "file content"
  val Digest     = "e0ac3601005dfa1864f5392aabaf7d898b1b5bab854f1acb4491bcd806b76b0c"

  final case class RemoteStorageHostConfig(host: String, port: Int) {
    def endpoint: String = s"http://$host:$port/v1"
  }
}
