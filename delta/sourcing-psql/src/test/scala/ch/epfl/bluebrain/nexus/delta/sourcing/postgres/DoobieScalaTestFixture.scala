package ch.epfl.bluebrain.nexus.delta.sourcing.postgres

import cats.effect.IO
import ch.epfl.bluebrain.nexus.delta.sourcing.partition.{DatabasePartitioner, PartitionStrategy}
import ch.epfl.bluebrain.nexus.delta.sourcing.{DDLLoader, Transactors}
import ch.epfl.bluebrain.nexus.testkit.Generators
import ch.epfl.bluebrain.nexus.testkit.clock.FixedClock
import ch.epfl.bluebrain.nexus.testkit.scalatest.ce.CatsIOValues
import org.scalatest.{BeforeAndAfterAll, Suite}

trait DoobieScalaTestFixture
    extends BeforeAndAfterAll
    with PostgresDocker
    with Generators
    with CatsIOValues
    with FixedClock {

  self: Suite =>

  var xas: Transactors              = _
  private var xasTeardown: IO[Unit] = _

  private val defaultPartitioningStrategy = PartitionStrategy.Hash(1)

  override def beforeAll(): Unit = {
    super.beforeAll()

    val (x, t) = Transactors
      .test(container.getHost, container.getMappedPort(5432), "postgres", "postgres", "postgres")
      .evalTap(DDLLoader.dropAndCreateDDLs(defaultPartitioningStrategy, _))
      .evalTap(DatabasePartitioner(defaultPartitioningStrategy, _))
      .allocated
      .accepted
    xas = x
    xasTeardown = t
  }

  override def afterAll(): Unit = {
    xasTeardown.accepted
    super.afterAll()
  }

}
