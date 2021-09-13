package ch.epfl.bluebrain.nexus.delta

import ch.epfl.bluebrain.nexus.testkit.{ElasticSearchDocker, IOValues}
import ch.epfl.bluebrain.nexus.testkit.postgres.PostgresDocker._
import org.scalatest.OptionValues
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

class PostgresMainSpec
    extends AnyWordSpecLike
    with Matchers
    with IOValues
    with OptionValues
    with PostgresSpec
    with ElasticSearchDocker
    with MainBehaviors {

  override protected def flavour: String = "postgres"

  override def beforeAll(): Unit = {
    super.beforeAll()
    System.setProperty("app.database.postgres.host", postgresHostConfig.host)
    System.setProperty("app.database.postgres.port", postgresHostConfig.port.toString)
    System.setProperty("app.database.postgres.username", PostgresUser)
    System.setProperty("app.database.postgres.password", PostgresPassword)
    commonBeforeAll()
  }

  override def afterAll(): Unit = {
    System.clearProperty("app.database.postgres.host")
    System.clearProperty("app.database.postgres.port")
    System.clearProperty("app.database.postgres.username")
    System.clearProperty("app.database.postgres.password")
    commonAfterAll()
    super.afterAll()
  }

}
