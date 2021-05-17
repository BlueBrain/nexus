package ch.epfl.bluebrain.nexus.delta.sdk

import akka.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import akka.cluster.typed.{Cluster, Join}
import akka.persistence.query.{NoOffset, Offset, Sequence}
import ch.epfl.bluebrain.nexus.delta.kernel.RetryStrategyConfig.AlwaysGiveUp
import ch.epfl.bluebrain.nexus.delta.kernel.utils.UUIDF
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.nxv
import ch.epfl.bluebrain.nexus.delta.sdk.cache.KeyValueStoreConfig
import ch.epfl.bluebrain.nexus.delta.sdk.generators.ResolverGen
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Identity
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.ProjectCountsCollection.ProjectCount
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.{ProjectCountsCollection, ProjectRef}
import ch.epfl.bluebrain.nexus.delta.sdk.model.resolvers.ResolverEvent.ResolverCreated
import ch.epfl.bluebrain.nexus.delta.sdk.model.{Envelope, Event}
import ch.epfl.bluebrain.nexus.delta.sourcing.config.SaveProgressConfig
import ch.epfl.bluebrain.nexus.delta.sourcing.projections.{Projection, ProjectionProgress}
import ch.epfl.bluebrain.nexus.testkit.{CirceLiteral, IOFixedClock, IOValues}
import com.typesafe.config.ConfigFactory
import fs2.Stream
import monix.bio.Task
import monix.execution.Scheduler
import org.scalatest.OptionValues
import org.scalatest.concurrent.Eventually
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

import java.time.Instant
import scala.concurrent.duration._

class ProjectsCountsSpec
    extends ScalaTestWithActorTestKit(
      ConfigFactory.parseResources("akka-cluster-test.conf").withFallback(ConfigFactory.load()).resolve()
    )
    with AnyWordSpecLike
    with Matchers
    with IOFixedClock
    with IOValues
    with OptionValues
    with CirceLiteral
    with Eventually {

  implicit private val sc: Scheduler = Scheduler.global
  implicit val uuidF: UUIDF          = UUIDF.random
  private val cluster                = Cluster(system)
  cluster.manager ! Join(cluster.selfMember.address)

  private val project1 = ProjectRef.unsafe("a1", "b1")
  private val project2 = ProjectRef.unsafe("a2", "b2")
  private val project3 = ProjectRef.unsafe("a3", "b3")

  private def project(idx: Int): ProjectRef = {
    if (idx <= 25) project1
    else if (idx <= 40) project2
    else project3
  }
  private val now = Instant.now()

  private val globalStream: Stream[Task, Envelope[Event]] =
    Stream
      .iterable[Task, Envelope[Event]](
        (1 until 51).map { idx =>
          val r = ResolverGen.inProject(nxv + idx.toString, project(idx), idx)
          Envelope(
            ResolverCreated(
              r.id,
              r.project,
              r.value,
              json"""{"created": $idx}""",
              1L,
              now.plusSeconds(1 * idx.toLong),
              Identity.Anonymous
            ),
            Sequence(idx.toLong),
            s"resolver-${r.id}",
            idx.toLong
          )
        }
      )
      .metered(10.millis)

  private def stream(offset: Offset): Stream[Task, Envelope[Event]] =
    offset match {
      case NoOffset        => globalStream
      case Sequence(value) => globalStream.take(value)
      case _               => throw new IllegalArgumentException("")
    }

  private val projection                                  = Projection.inMemory(ProjectCountsCollection.empty).accepted
  implicit private val persistProgress                    = SaveProgressConfig(1, 5.millis)
  implicit private val keyValueStore: KeyValueStoreConfig = KeyValueStoreConfig(5.seconds, 2.seconds, AlwaysGiveUp)

  "ProjectsCounts" should {

    "be computed" in {
      val counts = ProjectsCounts(projection, stream).accepted
      eventually {
        val currentCounts = counts.get().accepted
        currentCounts.get(project1).value shouldEqual ProjectCount(25, now.plusSeconds(25))
        currentCounts.get(project2).value shouldEqual ProjectCount(15, now.plusSeconds(40))
        currentCounts.get(project3).value // it has already consumed some element
        currentCounts.get(ProjectRef.unsafe("other", "other")) shouldEqual None
      }
    }

    "retrieve its offset" in eventually {
      val currentProgress = projection.progress(ProjectsCounts.projectionId).accepted
      val counts          = ProjectCountsCollection(
        Map(
          project1 -> ProjectCount(25, now.plusSeconds(25)),
          project2 -> ProjectCount(15, now.plusSeconds(40)),
          project3 -> ProjectCount(10, now.plusSeconds(50))
        )
      )
      currentProgress shouldEqual ProjectionProgress(Sequence(50), now.plusSeconds(50), 50, 0L, 0L, 0L, counts)
    }
  }

}
