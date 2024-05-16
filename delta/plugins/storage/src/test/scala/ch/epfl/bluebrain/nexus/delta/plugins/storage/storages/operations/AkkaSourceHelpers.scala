package ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations

import akka.actor.ActorSystem
import cats.effect.IO
import ch.epfl.bluebrain.nexus.delta.sdk.AkkaSource
import org.scalatest.concurrent.ScalaFutures

import scala.concurrent.duration._

trait AkkaSourceHelpers extends ScalaFutures {

  implicit override def patienceConfig: PatienceConfig = PatienceConfig(6.seconds, 10.milliseconds)

  def consumeIO(source: AkkaSource)(implicit as: ActorSystem): IO[String] =
    IO.fromFuture(IO.delay(source.runFold("")(_ ++ _.utf8String)))

  def consume(source: AkkaSource)(implicit as: ActorSystem): String =
    source.runFold("")(_ ++ _.utf8String).futureValue

}
