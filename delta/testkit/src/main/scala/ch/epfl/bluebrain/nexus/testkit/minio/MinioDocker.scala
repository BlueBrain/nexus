package ch.epfl.bluebrain.nexus.testkit.minio

import ch.epfl.bluebrain.nexus.testkit.minio.MinioDocker._
import org.scalatest.{BeforeAndAfterAll, Suite}

import scala.concurrent.duration.DurationInt
import scala.jdk.DurationConverters.ScalaDurationOps

trait MinioDocker extends BeforeAndAfterAll { this: Suite =>

  protected val container: MinioContainer =
    new MinioContainer(RootUser, RootPassword, VirtualHost, Region)
      .withReuse(false)
      .withStartupTimeout(60.seconds.toJava)

  def hostConfig: MinioHostConfig = {
    MinioHostConfig(container.getHost, container.getMappedPort(9000))
  }

  override def beforeAll(): Unit = {
    super.beforeAll()
    container.start()
  }

  override def afterAll(): Unit = {
    container.stop()
    super.afterAll()
  }
}

object MinioDocker {
  val RootUser   = "my_key"
  val RootPassword   = "my_secret_key"
  val VirtualHost = "my-domain.com"
  val Region      = "eu-central-1"

  final case class MinioHostConfig(host: String, port: Int) {
    def endpoint: String = s"http://$VirtualHost:$port"
  }
}
