package ch.epfl.bluebrain.nexus.delta

import ch.epfl.bluebrain.nexus.testkit.IOValues
import ch.epfl.bluebrain.nexus.testkit.postgres.PostgresDocker
import ch.epfl.bluebrain.nexus.testkit.postgres.PostgresDocker._
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike
import org.scalatest.{DoNotDiscover, OptionValues}

@DoNotDiscover
class PostgresMainSpec
    extends AnyWordSpecLike
    with Matchers
    with IOValues
    with OptionValues
    with PostgresDocker
    with MainBehaviors {

  override protected def flavour: String = "postgres"

  override def beforeAll(): Unit = {
    super.beforeAll()
    System.setProperty("app.database.postgres.host", hostConfig.host)
    System.setProperty("app.database.postgres.port", hostConfig.port.toString)
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
