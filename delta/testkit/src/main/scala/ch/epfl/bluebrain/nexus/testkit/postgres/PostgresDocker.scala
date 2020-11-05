package ch.epfl.bluebrain.nexus.testkit.postgres

import java.sql.DriverManager

import ch.epfl.bluebrain.nexus.testkit.DockerSupport.DockerKitWithFactory
import ch.epfl.bluebrain.nexus.testkit.postgres.PostgresDocker.PostgresHostConfig
import com.whisk.docker.scalatest.DockerTestKit
import com.whisk.docker.{DockerCommandExecutor, DockerContainer, DockerContainerState, DockerReadyChecker}
import org.scalatest.wordspec.AnyWordSpecLike

import scala.concurrent.{ExecutionContext, Future}
import scala.util.Try

trait PostgresDocker extends DockerKitWithFactory {
  import scala.concurrent.duration._

  val PostgresAdvertisedPort = 5432
  val PostgresExposedPort    = 44444
  val PostgresUser           = "postgres"
  val PostgresPassword       = "postgres"

  val postgresHostConfig: PostgresHostConfig =
    PostgresHostConfig(
      dockerExecutor.host,
      PostgresExposedPort
    )

  val postgresContainer: DockerContainer = DockerContainer("library/postgres:12.2")
    .withPorts((PostgresAdvertisedPort, Some(PostgresExposedPort)))
    .withEnv(s"POSTGRES_USER=$PostgresUser", s"POSTGRES_PASSWORD=$PostgresPassword")
    .withReadyChecker(
      new PostgresReadyChecker(PostgresUser, PostgresPassword, postgresHostConfig)
        .looped(15, 1.second)
    )

  abstract override def dockerContainers: List[DockerContainer] =
    postgresContainer :: super.dockerContainers
}

class PostgresReadyChecker(user: String, password: String, config: PostgresHostConfig) extends DockerReadyChecker {

  override def apply(
      container: DockerContainerState
  )(implicit docker: DockerCommandExecutor, ec: ExecutionContext): Future[Boolean] =
    Future {
      Try {
        Class.forName("org.postgresql.Driver")
        val url = s"jdbc:postgresql://${config.host}:${config.port}/"
        Option(DriverManager.getConnection(url, user, password)).map(_.close).isDefined
      }.getOrElse(false)
    }
}

object PostgresDocker {

  final case class PostgresHostConfig(host: String, port: Int)

  trait PostgresSpec extends AnyWordSpecLike with DockerTestKit with PostgresDocker

}
