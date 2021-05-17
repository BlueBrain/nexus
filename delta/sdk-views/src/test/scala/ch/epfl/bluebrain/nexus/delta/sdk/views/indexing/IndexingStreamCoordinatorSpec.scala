package ch.epfl.bluebrain.nexus.delta.sdk.views.indexing

import akka.actor.testkit.typed.scaladsl.{ScalaTestWithActorTestKit, TestProbe}
import akka.cluster.typed.{Cluster, Join}
import ch.epfl.bluebrain.nexus.delta.kernel.RetryStrategy
import ch.epfl.bluebrain.nexus.delta.kernel.utils.UUIDF
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.ProjectRef
import ch.epfl.bluebrain.nexus.delta.sdk.syntax._
import ch.epfl.bluebrain.nexus.delta.sdk.views.indexing.IndexingStream.ProgressStrategy
import ch.epfl.bluebrain.nexus.delta.sdk.views.indexing.IndexingStreamBehaviour.{Restart, Stop}
import ch.epfl.bluebrain.nexus.delta.sdk.views.indexing.IndexingStreamCoordinatorSpec._
import ch.epfl.bluebrain.nexus.delta.sdk.views.model.ViewIndex
import ch.epfl.bluebrain.nexus.delta.sourcing.projections.Projection
import ch.epfl.bluebrain.nexus.delta.sourcing.projections.ProjectionId.ViewProjectionId
import ch.epfl.bluebrain.nexus.delta.sourcing.projections.ProjectionProgress.NoProgress
import ch.epfl.bluebrain.nexus.testkit.IOValues
import com.typesafe.config.ConfigFactory
import fs2.Stream
import monix.bio.{Task, UIO}
import monix.execution.Scheduler
import org.scalatest.concurrent.Eventually
import org.scalatest.wordspec.AnyWordSpecLike
import org.scalatest.{CancelAfterFailure, Inspectors, OptionValues}

import java.time.Instant
import java.util.UUID
import scala.collection.concurrent
import scala.collection.mutable.{Set => MutableSet}
import scala.concurrent.duration._

class IndexingStreamCoordinatorSpec
    extends ScalaTestWithActorTestKit(
      ConfigFactory.load().resolve()
    )
    with AnyWordSpecLike
    with Eventually
    with OptionValues
    with IOValues
    with CancelAfterFailure
    with Inspectors {

  implicit override def patienceConfig: PatienceConfig = PatienceConfig(6.seconds, 3.milliseconds)
  implicit val uuidF: UUIDF                            = UUIDF.random

  val revisions: concurrent.Map[Iri, Long] = new concurrent.TrieMap

  implicit private val sc: Scheduler = Scheduler.global
  private val cluster                = Cluster(system)
  cluster.manager ! Join(cluster.selfMember.address)

  private val project = ProjectRef.unsafe("org", "proj")

  // View which keeps the same revision
  private val view1          = iri"view1"
  // View that gets updated and then deprecated
  private val view2          = iri"view2"
  // View already deprecated
  private val deprecatedView = iri"deprecatedView"
  // Unknown view
  private val unknownView    = iri"unknownView"

  private def projectionId(id: Iri, rev: Long) = ViewProjectionId(s"${id}_$rev")

  private def viewIndex(
      id: Iri,
      rev: Long,
      deprecated: Boolean,
      uuid: UUID = UUID.randomUUID()
  ): Option[ViewIndex[Unit]] = Some(
    ViewIndex(
      project,
      id,
      uuid,
      projectionId(id, rev),
      s"${id}_$rev",
      rev,
      deprecated = deprecated,
      None,
      Instant.EPOCH,
      ()
    )
  )

  private val probe: TestProbe[ProbeCommand]                               = createTestProbe[ProbeCommand]()
  private val view1Uuid                                                    = UUID.randomUUID()
  private val view2Uuid                                                    = UUID.randomUUID()
  private val fetchView: (Iri, ProjectRef) => UIO[Option[ViewIndex[Unit]]] = (id: Iri, _: ProjectRef) =>
    UIO.pure {
      val newRevision = revisions.updateWith(id) {
        _.map(_ + 1).orElse(Some(1L))
      }
      (id, newRevision) match {
        case (`view1`, _)                     => viewIndex(id, 1L, deprecated = false, view1Uuid)
        case (`view2`, Some(rev)) if rev < 3L => viewIndex(id, rev, deprecated = false, view2Uuid)
        case (`view2`, Some(rev))             => viewIndex(id, rev, deprecated = true, view2Uuid)
        case (`deprecatedView`, Some(rev))    => viewIndex(id, rev, deprecated = true)
        case (_, _)                           => None
      }
    } <* UIO.pure(probe.ref ! InitView(id))

  private val never      = RetryStrategy.alwaysGiveUp[Throwable]((_, _) => Task.unit)
  private val projection = Projection.inMemory(()).accepted

  private val buildStream: IndexingStream[Unit] = new IndexingStream[Unit] {
    override def apply(view: ViewIndex[Unit], strategy: IndexingStream.ProgressStrategy): Stream[Task, Unit] =
      Stream
        .eval {
          UIO.pure(probe.ref ! BuildStream(view.id, view.rev, strategy)) >>
            projection.recordProgress(view.projectionId, NoProgress)
        }
        .flatMap { _ =>
          Stream
            .repeatEval {
              for {
                savedProgress <- projection.progress(view.projectionId)
                _             <- projection.recordProgress(
                                   view.projectionId,
                                   savedProgress.copy(processed = savedProgress.processed + 1L)
                                 )
              } yield ()
            }
            .metered(10.millis)
            .onFinalize(
              UIO(probe.ref ! StreamStopped(view.id, view.rev))
            )
        }

  }
  private val indexingCleanupValues             = MutableSet.empty[ViewIndex[Unit]]
  private val indexingCleanup                   = new IndexingCleanup[Unit] {
    override def apply(view: ViewIndex[Unit]): UIO[Unit] = UIO.delay(indexingCleanupValues.add(view)).void
  }

  private val controller  = new IndexingStreamController[Unit]("v")
  private val coordinator = IndexingStreamCoordinator[Unit](controller, fetchView, buildStream, indexingCleanup, never)

  "An IndexingStreamCoordinator" should {

    def nbProcessed(id: Iri, rev: Long) =
      projection.progress(ViewProjectionId(s"${id}_$rev")).accepted.processed

    "start a view" in {
      coordinator.run(view1, project, 1L).accepted

      probe.expectMessage(InitView(view1))
      probe.expectMessage(BuildStream(view1, 1L, ProgressStrategy.default))
      probe.expectNoMessage()

      eventually {
        nbProcessed(view1, 1L) should be > 0L
      }
    }

    "not restart the view if the view has the same revision" in {
      val current = nbProcessed(view1, 1L)
      coordinator.run(view1, project, 2L).accepted

      probe.expectMessage(InitView(view1))
      probe.expectNoMessage()

      eventually {
        nbProcessed(view1, 1L) should be > current
      }
    }

    "restart the view if it stops thanks to the remembering entities feature" in {
      val current = nbProcessed(view1, 1L)
      controller.send(view1, project, Stop).accepted

      probe.expectMessage(StreamStopped(view1, 1L))
      probe.expectMessage(InitView(view1))
      probe.expectMessage(BuildStream(view1, 1L, ProgressStrategy.default))
      probe.expectNoMessage()

      eventually {
        nbProcessed(view1, 1L) should be > current
      }
    }

    "restart the view from the beginning" in {
      controller.send(view1, project, Restart(ProgressStrategy.FullRestart)).accepted
      probe.receiveMessages(2) should contain theSameElementsAs
        Seq(StreamStopped(view1, 1L), BuildStream(view1, 1L, ProgressStrategy.FullRestart))
      probe.expectNoMessage()
    }

    "start another view" in {
      coordinator.run(view2, project, 1L).accepted

      probe.expectMessage(InitView(view2))
      probe.expectMessage(BuildStream(view2, 1L, ProgressStrategy.default))
      probe.expectNoMessage()
    }

    "start another revision of the same view" in {
      (coordinator.run(view2, project, 2L) >> UIO.sleep(100.millis)).accepted // a new revision should be fetched
      val processedRev1 = nbProcessed(view2, 1L)

      probe.expectMessage(InitView(view2))
      probe.receiveMessages(2) should contain theSameElementsAs
        Seq(
          StreamStopped(view2, 1L),
          BuildStream(view2, 2L, ProgressStrategy.Continue)
        )
      probe.expectNoMessage()

      eventually {
        nbProcessed(view2, 1L) shouldEqual processedRev1
        nbProcessed(view2, 2L) should be > 0L
        indexingCleanupValues.toSet shouldEqual Set(viewIndex(view2, 1L, deprecated = false, view2Uuid).value)
      }
    }

    "stop view when it gets deprecated" in {
      (coordinator.run(view2, project, 3L) >> UIO.sleep(100.millis)).accepted // a deprecated version should be fetched
      val processedRev2 = nbProcessed(view2, 2L)

      probe.expectMessage(InitView(view2))
      probe.expectMessage(StreamStopped(view2, 2L))
      probe.expectNoMessage()

      eventually {
        nbProcessed(view2, 2L) shouldEqual processedRev2
        indexingCleanupValues.toSet shouldEqual Set(
          viewIndex(view2, 1L, deprecated = false, view2Uuid).value,
          viewIndex(view2, 2L, deprecated = false, view2Uuid).value
        )

      }
    }

    "do nothing with an already deprecated view" in {
      coordinator.run(deprecatedView, project, 1L).accepted

      probe.expectMessage(InitView(deprecatedView))
      probe.expectNoMessage()
    }

    "do nothing with an unknown view" in {
      coordinator.run(unknownView, project, 1L).accepted

      probe.expectMessage(InitView(unknownView))
      probe.expectNoMessage()
    }
  }

}

object IndexingStreamCoordinatorSpec {

  sealed trait ProbeCommand

  final case class InitView(id: Iri) extends ProbeCommand

  final case class BuildStream(id: Iri, rev: Long, strategy: IndexingStream.ProgressStrategy) extends ProbeCommand

  final case class StreamStopped(id: Iri, rev: Long) extends ProbeCommand

  final case class StreamRestarted(id: Iri, rev: Long) extends ProbeCommand

}
