package ch.epfl.bluebrain.nexus.delta.sdk.indexing

import akka.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import akka.cluster.typed.{Cluster, Join}
import ch.epfl.bluebrain.nexus.delta.kernel.RetryStrategy
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.nxv
import ch.epfl.bluebrain.nexus.delta.sdk.indexing.IndexingStreamCoordinator.BuildStream
import ch.epfl.bluebrain.nexus.delta.sdk.indexing.IndexingStreamCoordinatorSpec.{SimpleView, ViewData}
import ch.epfl.bluebrain.nexus.sourcing.processor.EventSourceProcessorConfig
import ch.epfl.bluebrain.nexus.sourcing.projections.Projection
import ch.epfl.bluebrain.nexus.sourcing.projections.ProjectionId.ViewProjectionId
import ch.epfl.bluebrain.nexus.testkit.IOValues
import com.typesafe.config.ConfigFactory
import fs2.Stream
import monix.bio.Task
import monix.execution.Scheduler
import org.scalatest.{CancelAfterFailure, Inspectors}
import org.scalatest.concurrent.Eventually
import org.scalatest.wordspec.AnyWordSpecLike

import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
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

  implicit private val sc: Scheduler = Scheduler.global
  private val cluster                = Cluster(system)
  cluster.manager ! Join(cluster.selfMember.address)

  private val map = MutableMap.empty[ViewProjectionId, ViewData]
  private def createViewData(v: SimpleView): ViewData = {
    val counter        = new AtomicLong(0)
    val infiniteStream = Stream[Task, Unit](()).repeat
      .metered(50.millis)
      .as(counter.incrementAndGet())
      .as(())
      .onFinalize(Task.delay(map -= v.projectionId).as(()))
    val viewData       = ViewData(counter, infiniteStream)
    map += (v.projectionId -> viewData)
    viewData
  }

  "An IndexingStreamCoordinator" should {
    val projection                           = Projection.inMemory(()).accepted
    val config                               = EventSourceProcessorConfig(3.second, 3.second, system.classicSystem.dispatcher, 10)
    val buildStream: BuildStream[SimpleView] = (v, _) => Task.delay(createViewData(v).stream)
    val never                                = RetryStrategy.alwaysGiveUp[Throwable]
    val coordinator                          =
      IndexingStreamCoordinator[SimpleView]("v", buildStream, projection, config, never).accepted
    val uuid                                 = UUID.randomUUID()
    val view1Rev1                            = SimpleView(nxv + "myview", 1, uuid)
    val view1Rev2                            = SimpleView(nxv + "myview", 2, uuid)
    val view2                                = SimpleView(nxv + "myview2", 1, UUID.randomUUID())

    "start a view" in {
      coordinator.start(view1Rev1).accepted
      eventually(map.contains(view1Rev1.projectionId) shouldEqual true)
    }

    "start another view" in {
      coordinator.start(view2).accepted
      eventually(map.contains(view2.projectionId) shouldEqual true)
      map.contains(view1Rev1.projectionId) shouldEqual true
    }

    "start another revision of the same view" in {
      coordinator.start(view1Rev2).accepted
      eventually(map.contains(view1Rev1.projectionId) shouldEqual false)
      map.contains(view1Rev2.projectionId) shouldEqual true
      map.contains(view2.projectionId) shouldEqual true
    }

    "stop views" in {
      forAll(List(view1Rev2, view2)) { view =>
        coordinator.stop(view).accepted
        eventually(map.contains(view.projectionId) shouldEqual false)
      }
    }
  }

}

object IndexingStreamCoordinatorSpec {
  final case class ViewData(count: AtomicLong, stream: Stream[Task, Unit])

  final case class SimpleView(id: Iri, rev: Long, uuid: UUID) {
    def projectionId: ViewProjectionId = ViewProjectionId(s"${id}_$rev")
  }

  object SimpleView {
    implicit val viewLens: ViewLens[SimpleView] = new ViewLens[SimpleView] {
      override def rev(view: SimpleView): Long                      = view.rev
      override def projectionId(view: SimpleView): ViewProjectionId = view.projectionId

      override def uuid(view: SimpleView): UUID = view.uuid
    }
  }
}
