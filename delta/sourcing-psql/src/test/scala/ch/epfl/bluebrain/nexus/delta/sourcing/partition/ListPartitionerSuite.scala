package ch.epfl.bluebrain.nexus.delta.sourcing.partition

import cats.syntax.all.*
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.nxv
import ch.epfl.bluebrain.nexus.delta.sourcing.PullRequest.PullRequestEvent.PullRequestCreated
import ch.epfl.bluebrain.nexus.delta.sourcing.PullRequest.PullRequestState.PullRequestActive
import ch.epfl.bluebrain.nexus.delta.sourcing.config.QueryConfig
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.Anonymous
import ch.epfl.bluebrain.nexus.delta.sourcing.model.ProjectRef
import ch.epfl.bluebrain.nexus.delta.sourcing.partition.DatabasePartitioner.DifferentPartitionStrategyDetected
import ch.epfl.bluebrain.nexus.delta.sourcing.postgres.Doobie.resource
import ch.epfl.bluebrain.nexus.delta.sourcing.postgres.{PartitionQueries, ScopedEventQueries, ScopedStateQueries}
import ch.epfl.bluebrain.nexus.delta.sourcing.{PullRequest, Transactors}
import ch.epfl.bluebrain.nexus.testkit.mu.NexusSuite
import doobie.syntax.all.*
import munit.{AnyFixture, Location}
import munit.catseffect.IOFixture

import java.time.Instant

class ListPartitionerSuite extends NexusSuite {

  private val partitioningStrategy = PartitionStrategy.List

  private val queryConfig = QueryConfig.stopping(10)

  private val hashDoobie: IOFixture[(DatabasePartitioner, Transactors)] =
    ResourceSuiteLocalFixture("doobie", resource(partitioningStrategy))

  override def munitFixtures: Seq[AnyFixture[?]] = List(hashDoobie)

  implicit private lazy val (partitioner: DatabasePartitioner, xas: Transactors) = hashDoobie()

  private lazy val eventStore = PullRequest.eventStore(queryConfig)

  private lazy val stateStore = PullRequest.stateStore(xas, queryConfig)

  private val project1 = ProjectRef.unsafe("org", "proj1")
  private val project2 = ProjectRef.unsafe("org", "proj2")

  private val id1 = nxv + "1"
  private val id2 = nxv + "2"

  private val event1 = PullRequestCreated(id1, project1, Instant.EPOCH, Anonymous)
  private val event2 = PullRequestCreated(id2, project2, Instant.EPOCH, Anonymous)

  private val state1 = PullRequestActive(id1, project1, 1, Instant.EPOCH, Anonymous, Instant.EPOCH, Anonymous)
  private val state2 = PullRequestActive(id2, project2, 1, Instant.EPOCH, Anonymous, Instant.EPOCH, Anonymous)

  private def populate =
    for {
      _ <- List(event1, event2).traverse(eventStore.save).transact(xas.write)
      _ <- List(state1, state2).traverse(stateStore.save).transact(xas.write)
    } yield ()

  private def expectedPartitions(number: Int)(implicit loc: Location) =
    PartitionQueries.partitionsOf("scoped_events").map(_.size).assertEquals(number) >>
      PartitionQueries.partitionsOf("scoped_states").map(_.size).assertEquals(number)

  test("Provision the partitions and save the config") {
    for {
      _            <- DatabasePartitioner.getConfig(partitioningStrategy, xas).assertEquals(true)
      _            <- expectedPartitions(0)
      _            <- partitioner.onCreateProject(project1)
      _            <- partitioner.onCreateProject(project2)
      // 3 partitions: 1 for the enclosing org and two for the projects
      _            <- expectedPartitions(3)
      _            <- populate
      // Both projects should be available
      _            <- ScopedEventQueries.distinctProjects.assertEquals(Set(project1, project2))
      _            <- ScopedStateQueries.distinctProjects.assertEquals(Set(project1, project2))
      _            <- partitioner.onDeleteProject(project1).transact(xas.write)
      // Only project2 should remain
      _            <- ScopedEventQueries.distinctProjects.assertEquals(Set(project2))
      _            <- ScopedStateQueries.distinctProjects.assertEquals(Set(project2))
      // Only the partition for the org and project2 should remain
      _            <- expectedPartitions(2)
      // Init again with the same value should be ok
      _            <- partitioner.onInit.assert
      // Init with another partition strategy should fail
      newStrategy   = PartitionStrategy.Hash(42)
      expectedError = DifferentPartitionStrategyDetected(partitioningStrategy, newStrategy)
      _            <- DatabasePartitioner(newStrategy, xas).interceptEquals(expectedError)
    } yield ()
  }

}
