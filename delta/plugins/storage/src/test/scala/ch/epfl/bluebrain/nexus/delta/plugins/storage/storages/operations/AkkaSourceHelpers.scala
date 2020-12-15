package ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations

import akka.actor.ActorSystem
import akka.stream.scaladsl.Source
import akka.util.ByteString
import org.scalatest.concurrent.ScalaFutures

import scala.concurrent.duration._

trait AkkaSourceHelpers extends ScalaFutures {

  implicit override def patienceConfig: PatienceConfig = PatienceConfig(6.seconds, 10.milliseconds)

  def consume(source: Source[ByteString, Any])(implicit as: ActorSystem): String =
    source.runFold("")(_ ++ _.utf8String).futureValue

}
