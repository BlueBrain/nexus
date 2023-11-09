package ch.epfl.bluebrain.nexus.delta.sourcing.postgres

import cats.effect.IO
import cats.implicits.toFlatMapOps
import ch.epfl.bluebrain.nexus.delta.sourcing.Transactors
import ch.epfl.bluebrain.nexus.testkit.TestHelpers
import ch.epfl.bluebrain.nexus.testkit.ce.CatsRunContext
import ch.epfl.bluebrain.nexus.testkit.clock.FixedClock
import ch.epfl.bluebrain.nexus.testkit.scalatest.ce.CatsIOValues
import org.scalatest.{BeforeAndAfterAll, Suite}

trait DoobieScalaTestFixture
    extends BeforeAndAfterAll
    with PostgresDocker
    with TestHelpers
    with CatsIOValues
    with FixedClock {

  self: Suite with CatsRunContext =>

  implicit val classLoader: ClassLoader = getClass.getClassLoader

  var xas: Transactors              = _
  private var xasTeardown: IO[Unit] = _

  override def beforeAll(): Unit = {
    super.beforeAll()
    val xasResource = Transactors.test(container.getHost, container.getMappedPort(5432), "postgres", "postgres")
    val (x, t)      = xasResource.allocated.flatTap { case (x, _) =>
      Transactors.dropAndCreateDDLs.flatMap(x.execDDLs)
    }.accepted
    xas = x
    xasTeardown = t
  }

  override def afterAll(): Unit = {
    xasTeardown.accepted
    super.afterAll()
  }

}
