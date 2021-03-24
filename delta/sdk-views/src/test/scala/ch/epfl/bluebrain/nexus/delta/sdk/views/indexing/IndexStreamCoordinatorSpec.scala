package ch.epfl.bluebrain.nexus.delta.sdk.views.indexing

import akka.actor.testkit.typed.scaladsl.{ScalaTestWithActorTestKit, TestProbe}
import akka.cluster.typed.{Cluster, Join}
import ch.epfl.bluebrain.nexus.delta.kernel.RetryStrategy
import ch.epfl.bluebrain.nexus.delta.kernel.utils.UUIDF
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.sdk.views.indexing.IndexStreamCoordinatorSpec._
import ch.epfl.bluebrain.nexus.delta.sdk.views.indexing.IndexingStreamBehaviour.{ClearIndex, IndexingStream, Restart, Stop}
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.ProjectRef
import ch.epfl.bluebrain.nexus.delta.sdk.views.model.ViewIndex
import ch.epfl.bluebrain.nexus.delta.sdk.syntax._
import ch.epfl.bluebrain.nexus.delta.sourcing.projections.ProjectionId.ViewProjectionId
import ch.epfl.bluebrain.nexus.delta.sourcing.projections.{Projection, ProjectionProgress}
import ch.epfl.bluebrain.nexus.testkit.IOValues
import com.typesafe.config.ConfigFactory
import fs2.Stream
import monix.bio.{Task, UIO}
import monix.execution.Scheduler
import org.scalatest.concurrent.Eventually
import org.scalatest.wordspec.AnyWordSpecLike
import org.scalatest.{CancelAfterFailure, Inspectors, OptionValues}

import scala.collection.concurrent
import scala.concurrent.duration._

class IndexStreamCoordinatorSpec
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

  private def viewIndex(id: Iri, rev: Long, deprecated: Boolean): Option[ViewIndex[Unit]] = Some(
    ViewIndex(
      project,
      id,
      projectionId(id, rev),
      s"${id}_$rev",
      rev,
      deprecated = deprecated,
      ()
    )
  )

  val probe: TestProbe[ProbeCommand]                               = createTestProbe[ProbeCommand]()
  val fetchView: (Iri, ProjectRef) => UIO[Option[ViewIndex[Unit]]] = (id: Iri, _: ProjectRef) =>
    UIO.pure {
      val newRevision = revisions.updateWith(id) {
        _.map(_ + 1).orElse(Some(1L))
      }
      (id, newRevision) match {
        case (`view1`, _)                     => viewIndex(id, 1L, deprecated = false)
        case (`view2`, Some(rev)) if rev < 3L => viewIndex(id, rev, deprecated = false)
        case (`view2`, Some(rev))             => viewIndex(id, rev, deprecated = true)
        case (`deprecatedView`, Some(rev))    => viewIndex(id, rev, deprecated = true)
        case (_, _)                           => None
      }
    } <* UIO.pure(probe.ref ! InitView(id))

  val buildStream: IndexingStream[Unit] = (viewIndex: ViewIndex[Unit], p: ProjectionProgress[Unit]) =>
    Task.when(p == ProjectionProgress.NoProgress) {
      // To detect restart events
      projection
        .progress(viewIndex.projectionId)
        .map { saved =>
          if (saved != ProjectionProgress.NoProgress) {
            probe.ref ! StreamRestarted(viewIndex.id, viewIndex.rev)
          }
        }
    } >>
      Task.delay {
        Stream
          .repeatEval {
            for {
              savedProgress <- projection.progress(viewIndex.projectionId)
              _             <- projection.recordProgress(
                viewIndex.projectionId,
                savedProgress.copy(processed = savedProgress.processed + 1L)
              )
            } yield ()
          }
          .metered(10.millis)
          .onFinalize(
            UIO(probe.ref ! StreamStopped(viewIndex.id, viewIndex.rev))
          )
      } <* UIO.pure(probe.ref ! BuildStream(viewIndex.id, viewIndex.rev))

  val index: ClearIndex = idx => UIO(probe.ref ! IndexCleared(idx))

  private val never      = RetryStrategy.alwaysGiveUp[Throwable]((_, _) => Task.unit)
  private val projection = Projection.inMemory(()).accepted

  val coordinator = new IndexingStreamCoordinator[Unit]("v", fetchView, buildStream, index, projection, never)

  "An IndexingStreamCoordinator" should {

    def nbProcessed(id: Iri, rev: Long) =
      projection.progress(ViewProjectionId(s"${id}_$rev")).accepted.processed

    "start a view" in {
      coordinator.run(view1, project, 1L).accepted

      probe.expectMessage(InitView(view1))
      probe.expectMessage(BuildStream(view1, 1L))
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
      coordinator.send(view1, project, Stop).accepted

      probe.expectMessage(StreamStopped(view1, 1L))
      probe.expectMessage(InitView(view1))
      probe.expectMessage(BuildStream(view1, 1L))
      probe.expectNoMessage()

      eventually {
        nbProcessed(view1, 1L) should be > current
      }
    }

    "restart the view from the beginning" in {
      coordinator.send(view1, project, Restart).accepted
      probe.receiveMessages(3) should contain theSameElementsAs Seq(
        StreamStopped(view1, 1L),
        StreamRestarted(view1, 1L),
        BuildStream(view1, 1L)
      )
      probe.expectNoMessage()
    }

    "start another view" in {
      coordinator.run(view2, project, 1L).accepted

      probe.expectMessage(InitView(view2))
      probe.expectMessage(BuildStream(view2, 1L))
      probe.expectNoMessage()
    }

    "start another revision of the same view" in {
      (coordinator.run(view2, project, 2L) >> UIO.sleep(100.millis)).accepted // a new revision should be fetched
      val processedRev1 = nbProcessed(view2, 1L)

      probe.expectMessage(InitView(view2))
      probe.receiveMessages(3) should contain theSameElementsAs Seq(
        IndexCleared(s"${view2}_1"),
        StreamStopped(view2, 1L),
        BuildStream(view2, 2L)
      )
      probe.expectNoMessage()

      eventually {
        nbProcessed(view2, 1L) shouldEqual processedRev1
        nbProcessed(view2, 2L) should be > 0L
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

object IndexStreamCoordinatorSpec {

  sealed trait ProbeCommand

  final case class InitView(id: Iri) extends ProbeCommand

  final case class BuildStream(id: Iri, rev: Long) extends ProbeCommand

  final case class StreamStopped(id: Iri, rev: Long) extends ProbeCommand

  final case class StreamRestarted(id: Iri, rev: Long) extends ProbeCommand

  final case class IndexCleared(name: String) extends ProbeCommand

}
