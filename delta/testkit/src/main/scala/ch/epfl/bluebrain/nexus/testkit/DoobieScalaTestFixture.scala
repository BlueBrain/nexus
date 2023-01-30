package ch.epfl.bluebrain.nexus.testkit

import ch.epfl.bluebrain.nexus.delta.kernel.database.Transactors
import ch.epfl.bluebrain.nexus.testkit.postgres.PostgresDocker
import monix.execution.Scheduler
import org.scalatest.BeforeAndAfterAll
import org.scalatest.wordspec.AnyWordSpecLike

trait DoobieScalaTestFixture
    extends AnyWordSpecLike
    with BeforeAndAfterAll
    with PostgresDocker
    with TestHelpers
    with IOValues {

  implicit private val classLoader: ClassLoader = getClass.getClassLoader

  var xas: Transactors = _

  override def beforeAll(): Unit = {
    implicit val s: Scheduler = Scheduler.global
    super.beforeAll()
    xas =
      Transactors.sharedFrom(container.getHost, container.getMappedPort(5432), "postgres", "postgres").runSyncUnsafe()
    (xas.execDDL("/scripts/drop-tables.ddl") >> xas.execDDL("/scripts/schema.ddl")).runSyncUnsafe()
  }

}
