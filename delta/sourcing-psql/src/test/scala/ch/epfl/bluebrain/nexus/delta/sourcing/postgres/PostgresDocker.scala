package ch.epfl.bluebrain.nexus.delta.sourcing.postgres

import ch.epfl.bluebrain.nexus.delta.sourcing.postgres.PostgresDocker._
import ch.epfl.bluebrain.nexus.testkit.postgres.PostgresContainer
import org.scalatest.{BeforeAndAfterAll, Suite}

import scala.concurrent.duration._
import scala.jdk.DurationConverters._

trait PostgresDocker extends BeforeAndAfterAll { this: Suite =>

  protected val container: PostgresContainer =
    new PostgresContainer(PostgresUser, PostgresPassword, PostgresDb)
      .withReuse(false)
      .withStartupTimeout(60.seconds.toJava)

  def hostConfig: PostgresHostConfig =
    PostgresHostConfig(container.getHost, container.getMappedPort(5432))

  override def beforeAll(): Unit = {
    super.beforeAll()
    container.start()
  }

  override def afterAll(): Unit = {
    container.stop()
    super.afterAll()
  }

}

object PostgresDocker {
  final case class PostgresHostConfig(host: String, port: Int)
}
