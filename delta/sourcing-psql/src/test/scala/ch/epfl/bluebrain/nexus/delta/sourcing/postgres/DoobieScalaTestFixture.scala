package ch.epfl.bluebrain.nexus.delta.sourcing.postgres

import ch.epfl.bluebrain.nexus.delta.sourcing.Transactors
import ch.epfl.bluebrain.nexus.testkit.TestHelpers
import ch.epfl.bluebrain.nexus.testkit.scalatest.bio.BIOValues
import monix.bio.Task
import monix.execution.Scheduler
import org.scalatest.{BeforeAndAfterAll, Suite}

trait DoobieScalaTestFixture extends BeforeAndAfterAll with PostgresDocker with TestHelpers with BIOValues {

  self: Suite =>

  implicit val classLoader: ClassLoader = getClass.getClassLoader

  var xas: Transactors                = _
  private var xasTeardown: Task[Unit] = _

  override def beforeAll(): Unit = {
    implicit val s: Scheduler = Scheduler.global
    super.beforeAll()
    val xasResource           = Transactors.test(container.getHost, container.getMappedPort(5432), "postgres", "postgres")
    val (x, t)                = xasResource.allocated
      .tapEval { case (x, _) =>
        Transactors.dropAndCreateDDLs.flatMap(x.execDDLs)
      }
      .runSyncUnsafe()
    xas = x
    xasTeardown = t
  }

  override def afterAll(): Unit = {
    implicit val s: Scheduler = Scheduler.global
    xasTeardown.runSyncUnsafe()
    super.afterAll()
  }

}
