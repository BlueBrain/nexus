package ch.epfl.bluebrain.nexus.delta.sdk.indexing

import akka.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import akka.cluster.typed.{Cluster, Join}
import cats.syntax.functor._
import cats.effect.concurrent.Ref
import ch.epfl.bluebrain.nexus.delta.kernel.RetryStrategy
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.nxv
import ch.epfl.bluebrain.nexus.delta.sdk.indexing.IndexingStreamCoordinator.{BuildStream, ClearIndex}
import ch.epfl.bluebrain.nexus.delta.sdk.indexing.IndexingStreamCoordinatorSpec.{SimpleView, ViewData}
import ch.epfl.bluebrain.nexus.delta.sdk.syntax._
import ch.epfl.bluebrain.nexus.delta.sourcing.processor.EventSourceProcessorConfig
import ch.epfl.bluebrain.nexus.delta.sourcing.projections.Projection
import ch.epfl.bluebrain.nexus.delta.sourcing.projections.ProjectionId.ViewProjectionId
import ch.epfl.bluebrain.nexus.testkit.IOValues
import com.typesafe.config.ConfigFactory
import fs2.Stream
import monix.bio.Task
import monix.execution.Scheduler
import org.scalatest.concurrent.Eventually
import org.scalatest.wordspec.AnyWordSpecLike
import org.scalatest.{CancelAfterFailure, Inspectors}

import java.util.UUID
import scala.collection.mutable.{Map => MutableMap}
import scala.concurrent.duration._

class IndexingStreamCoordinatorSpec
    extends ScalaTestWithActorTestKit(
      ConfigFactory.parseResources("akka-cluster-test.conf").withFallback(ConfigFactory.load()).resolve()
    )
    with AnyWordSpecLike
    with Eventually
    with IOValues
    with CancelAfterFailure
    with Inspectors {

  implicit override def patienceConfig: PatienceConfig = PatienceConfig(6.seconds, 3.milliseconds)

  implicit private val sc: Scheduler = Scheduler.global
  private val cluster                = Cluster(system)
  cluster.manager ! Join(cluster.selfMember.address)

  private val map                                           = MutableMap.empty[ViewProjectionId, ViewData]
  private def createViewData(v: SimpleView): Task[ViewData] =
    Ref.of[Task, Long](0L).map { ref =>
      val infiniteStream = Stream[Task, Unit](()).repeat
        .metered(10.millis)
        .scan(0L)((acc, _) => acc + 1L)
        .evalTap(ref.set)
        .void
        .onFinalize(Task.delay(map -= v.projectionId).as(()))
      val viewData       = ViewData(ref, infiniteStream)
      Thread.sleep(500)
      map += (v.projectionId -> viewData)
      viewData
    }

  "An IndexingStreamCoordinator" should {
    val stoppedIndex: Ref[Task, Set[String]] = Ref.of[Task, Set[String]](Set.empty[String]).accepted
    val projection                           = Projection.inMemory(()).accepted
    val config                               = EventSourceProcessorConfig(3.second, 3.second, 10)
    val buildStream: BuildStream[SimpleView] = (v, _) => createViewData(v).map(_.stream)
    val index: ClearIndex                    = idx => stoppedIndex.update(_ + idx)
    val never                                = RetryStrategy.alwaysGiveUp[Throwable]((_, _) => Task.unit)
    val coordinator                          =
      IndexingStreamCoordinator[SimpleView]("v", buildStream, index, projection, config, never).accepted
    val uuid                                 = UUID.randomUUID()
    val view1Rev1                            = SimpleView(nxv + "myview", 1, uuid)
    val view1Rev2                            = SimpleView(nxv + "myview", 2, uuid)
    val view2                                = SimpleView(nxv + "myview2", 1, UUID.randomUUID())

    "start a view" in {
      coordinator.start(view1Rev1).accepted
      eventually(map.contains(view1Rev1.projectionId) shouldEqual true)
      stoppedIndex.get.accepted should be(empty)
    }

    "restart the view from the beginning" in {
      Thread.sleep(400)
      val currentCount = map(view1Rev1.projectionId).ref.get.accepted
      coordinator.restart(view1Rev1).accepted
      eventually(map(view1Rev1.projectionId).ref.get.accepted should be < currentCount)
      stoppedIndex.get.accepted should be(empty)
    }

    "start another view" in {
      coordinator.start(view2).accepted
      eventually(map.contains(view2.projectionId) shouldEqual true)
      eventually(map.contains(view1Rev1.projectionId) shouldEqual true)
      stoppedIndex.get.accepted should be(empty)
    }

    "start another revision of the same view" in {
      coordinator.start(view1Rev2).accepted
      eventually(map.contains(view1Rev1.projectionId) shouldEqual false)
      eventually(map.contains(view1Rev2.projectionId) shouldEqual true)
      map.contains(view2.projectionId) shouldEqual true
      stoppedIndex.get.accepted shouldEqual Set(view1Rev1.index)
    }

    "stop views" in {
      forAll(List(view1Rev2, view2)) { view =>
        coordinator.stop(view).accepted
        eventually(map.contains(view.projectionId) shouldEqual false)
        stoppedIndex.get.accepted shouldEqual Set(view1Rev1.index)
      }
    }
  }

}

object IndexingStreamCoordinatorSpec {
  final case class ViewData(ref: Ref[Task, Long], stream: Stream[Task, Unit])

  final case class SimpleView(id: Iri, rev: Long, uuid: UUID) {
    def projectionId: ViewProjectionId = ViewProjectionId(s"${id}_$rev")
  }

  object SimpleView {
    implicit val viewLens: ViewLens[SimpleView] = new ViewLens[SimpleView] {
      override def rev(view: SimpleView): Long                      = view.rev
      override def projectionId(view: SimpleView): ViewProjectionId = view.projectionId
      override def index(view: SimpleView): String                  = s"${uuid(view)}_${rev(view)}"
      override def uuid(view: SimpleView): UUID                     = view.uuid
    }
  }
}
