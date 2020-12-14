package ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations

import akka.actor.ActorSystem
import akka.stream.scaladsl.Source
import akka.testkit.{TestDuration, TestKit}
import akka.util.ByteString
import org.scalatest.concurrent.ScalaFutures

import scala.concurrent.duration._

abstract class AkkaSourceHelpers extends TestKit(ActorSystem.apply("AkkaSourceHelpersSpec")) with ScalaFutures {

  implicit override def patienceConfig: PatienceConfig = PatienceConfig(6.seconds.dilated, 10.milliseconds)

  def consume(source: Source[ByteString, Any]): String =
    source.runFold("")(_ ++ _.utf8String).futureValue

}
