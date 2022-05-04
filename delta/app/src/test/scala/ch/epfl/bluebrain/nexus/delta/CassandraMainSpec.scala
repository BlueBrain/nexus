package ch.epfl.bluebrain.nexus.delta

import ch.epfl.bluebrain.nexus.testkit.IOValues
import ch.epfl.bluebrain.nexus.testkit.cassandra.CassandraDocker
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike
import org.scalatest.{DoNotDiscover, OptionValues}

@DoNotDiscover
class CassandraMainSpec
    extends AnyWordSpecLike
    with Matchers
    with IOValues
    with OptionValues
    with CassandraDocker
    with MainBehaviors {

  override protected def flavour: String = "cassandra"

  override def beforeAll(): Unit = {
    super.beforeAll()
    commonBeforeAll()
  }

  override def afterAll(): Unit = {
    commonAfterAll()
    super.afterAll()
  }

}
