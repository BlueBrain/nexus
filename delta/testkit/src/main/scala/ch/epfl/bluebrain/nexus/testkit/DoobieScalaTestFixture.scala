package ch.epfl.bluebrain.nexus.testkit

import ch.epfl.bluebrain.nexus.delta.kernel.database.Transactors
import ch.epfl.bluebrain.nexus.testkit.postgres.PostgresDocker
import monix.bio.Task
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

  var xas: Transactors                       = _
  private var transactorTeardown: Task[Unit] = _

  override def beforeAll(): Unit = {
    implicit val s: Scheduler   = Scheduler.global
    super.beforeAll()
    val (transactors, teardown) = Transactors
      .test(container.getHost, container.getMappedPort(5432), "postgres", "postgres")
      .allocated
      .runSyncUnsafe()
    (transactors.execDDL("/scripts/drop-tables.ddl") >> transactors.execDDL("/scripts/schema.ddl")).runSyncUnsafe()
    xas = transactors
    transactorTeardown = teardown
  }

  override def afterAll(): Unit = {
    implicit val s: Scheduler = Scheduler.global
    transactorTeardown.runSyncUnsafe()
    super.afterAll()
  }

}
