package ch.epfl.bluebrain.nexus.delta.sdk.views.indexing

import akka.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import akka.cluster.typed.{Cluster, Join}
import akka.persistence.query.{NoOffset, Offset, Sequence}
import ch.epfl.bluebrain.nexus.delta.kernel.RetryStrategyConfig.AlwaysGiveUp
import ch.epfl.bluebrain.nexus.delta.kernel.utils.UUIDF
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.nxv
import ch.epfl.bluebrain.nexus.delta.sdk.generators.ResolverGen
import ch.epfl.bluebrain.nexus.delta.sdk.model.Envelope
import ch.epfl.bluebrain.nexus.delta.sdk.model.Event.ProjectScopedEvent
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Identity
import ch.epfl.bluebrain.nexus.delta.sdk.model.resolvers.ResolverEvent.ResolverCreated
import ch.epfl.bluebrain.nexus.delta.sdk.syntax._
import ch.epfl.bluebrain.nexus.delta.sdk.views.model.ProjectsEventsInstantCollection
import ch.epfl.bluebrain.nexus.delta.sourcing.config.SaveProgressConfig
import ch.epfl.bluebrain.nexus.delta.sourcing.projections.{Projection, ProjectionProgress}
import ch.epfl.bluebrain.nexus.delta.sourcing.model.ProjectRef
import ch.epfl.bluebrain.nexus.testkit.{CirceLiteral, IOFixedClock, IOValues}
import com.typesafe.config.ConfigFactory
import fs2.Stream
import fs2.concurrent.SignallingRef
import monix.bio.Task
import monix.execution.Scheduler
import org.scalatest.OptionValues
import org.scalatest.concurrent.Eventually
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

import java.time.Instant
import scala.concurrent.duration._

class IndexingStreamAwakeStreamSpec
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
    if (idx <= 2) project1
    else if (idx <= 4) project2
    else project3
  }
  private val now = Instant.now()

  private val globalStream: Stream[Task, Envelope[ProjectScopedEvent]] =
    Stream
      .iterable[Task, Envelope[ProjectScopedEvent]](
        (1 until 7).map { idx =>
          val r = ResolverGen.inProject(nxv + idx.toString, project(idx), idx)
          Envelope(
            ResolverCreated(
              r.id,
              r.project,
              r.value,
              json"""{"created": $idx}""",
              1L,
              now.plusSeconds(idx.toLong),
              Identity.Anonymous
            ),
            Sequence(idx.toLong),
            s"resolver-${r.id}",
            idx.toLong
          )
        }
      )
      .metered(10.millis)

  private def stream(offset: Offset): Stream[Task, Envelope[ProjectScopedEvent]] =
    offset match {
      case NoOffset        => globalStream
      case Sequence(value) => globalStream.take(value)
      case _               => throw new IllegalArgumentException("")
    }

  private val projection     = Projection.inMemory(ProjectsEventsInstantCollection.empty).accepted
  private val ref            = SignallingRef[Task, Seq[(ProjectRef, FiniteDuration)]](Seq.empty).accepted
  private val onEventInstant = new OnEventInstant {
    override def awakeIndexingStream(
        project: ProjectRef,
        prevEvent: Option[Instant],
        currentEvent: Instant
    ): Task[Unit] =
      ref.update(_ :+ ((project, currentEvent.diff(prevEvent.getOrElse(currentEvent)))))
  }

  "IndexingStreamAwakeStream" should {

    val indexingStreamAwake =
      IndexingStreamAwake(projection, stream, onEventInstant, AlwaysGiveUp, SaveProgressConfig(1, 5.millis)).accepted

    "trigger awake callbacks" in {
      eventually {
        ref.get.accepted shouldEqual List(
          project1 -> 0.millis,
          project1 -> 1000.millis,
          project2 -> 0.millis,
          project2 -> 1000.millis,
          project3 -> 0.millis,
          project3 -> 1000.millis
        )
      }
    }

    "retrieve its offset" in eventually {
      val currentProgress = projection.progress(IndexingStreamAwake.projectionId).accepted
      val values          = ProjectsEventsInstantCollection(
        Map(
          project1 -> now.plusSeconds(2),
          project2 -> now.plusSeconds(4),
          project3 -> now.plusSeconds(6)
        )
      )
      currentProgress shouldEqual ProjectionProgress(Sequence(6), now.plusSeconds(6), 7, 0, 0, 0, values)
    }

    "delete the provided project" in {
      indexingStreamAwake.remove(project1).accepted

      eventually {
        val currentProgress = projection.progress(IndexingStreamAwake.projectionId).accepted
        val values          = ProjectsEventsInstantCollection(
          Map(
            project2 -> now.plusSeconds(4),
            project3 -> now.plusSeconds(6)
          )
        )
        currentProgress shouldEqual ProjectionProgress(Sequence(6), now.plusSeconds(6), 8, 0, 0, 0, values)
      }
    }
  }

}
