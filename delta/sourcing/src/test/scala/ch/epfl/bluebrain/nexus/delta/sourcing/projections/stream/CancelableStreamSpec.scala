package ch.epfl.bluebrain.nexus.delta.sourcing.projections.stream

import akka.actor.ActorSystem
import cats.implicits._
import akka.testkit.TestKit
import cats.effect.{IO, LiftIO, Timer}
import monix.bio.{Task, UIO}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike
import cats.effect.laws.util.TestContext
import ch.epfl.bluebrain.nexus.delta.kernel.RetryStrategy
import ch.epfl.bluebrain.nexus.delta.sourcing.syntax._
import ch.epfl.bluebrain.nexus.delta.kernel.utils.UUIDF
import ch.epfl.bluebrain.nexus.delta.sourcing.projections.stream.CancelableStreamSpec.{IdleTimeoutStopMessage, InitStopMessage, StopMessage}
import ch.epfl.bluebrain.nexus.testkit.IOValues
import fs2.Stream
import fs2.concurrent.SignallingRef
import monix.execution.Scheduler

import java.util.UUID
import scala.concurrent.duration._

class CancelableStreamSpec
    extends TestKit(ActorSystem("CancelableStreamSpec"))
    with AnyWordSpecLike
    with Matchers
    with IOValues {
  implicit private val ec: TestContext      = TestContext.apply()
  implicit private val liftIO               = new LiftIO[UIO] {
    override def liftIO[A](ioa: IO[A]): UIO[A] = ioa.to[Task].hideErrors
  }
  implicit private val uioTimer: Timer[UIO] = ec.timer[UIO]
  private val uuid                          = UUID.randomUUID()
  implicit private val uuidf                = UUIDF.fixed(uuid)
  implicit private val sc                   = Scheduler(ec)

  "A CancelableStream" should {
    "stop after an idleTimeout" in {
      val entries = SignallingRef[Task, Seq[Int]](Vector.empty).accepted
      var stopped = false

      val stream = (Stream.sleep[UIO](1.second).as(1) ++
        Stream.sleep[UIO](2.second).as(2) ++
        Stream.sleep[UIO](4.second).as(3))
        .evalTap(entry => entries.update(_ :+ entry).hideErrors)

      val cancelableStream = CancelableStream[Int, StopMessage]("name", stream, InitStopMessage).accepted
        .idleTimeout(3.seconds, IdleTimeoutStopMessage)

      def onStop: (UUID, StopMessage) => UIO[Unit] = {
        case (`uuid`, IdleTimeoutStopMessage) => UIO.delay { stopped = true }
        case _                                => UIO.unit
      }

      cancelableStream.run(RetryStrategy.alwaysGiveUp((_, _) => UIO.unit), onStop, onStop)
      ec.tick(7.seconds)
      entries.get.accepted shouldEqual Vector(1, 2)
      stopped shouldEqual true
    }
  }

}

object CancelableStreamSpec {
  sealed trait StopMessage                 extends Product with Serializable
  final case object InitStopMessage        extends StopMessage
  final case object IdleTimeoutStopMessage extends StopMessage
}
