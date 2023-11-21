package ch.epfl.bluebrain.nexus.delta.sdk.stream

/*
 * Source: https://github.com/krasserm/streamz/blob/cc78a3a956360756ccc24a29754742e1760a84b8/streamz-converter/src/main/scala/streamz/converter/Converter.scala
 *
 * Copyright 2014 - 2020 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import akka.Done
import akka.actor.ActorSystem
import akka.stream.scaladsl.{Flow => AkkaFlow, Keep, Sink => AkkaSink, Source => AkkaSource}
import akka.testkit._
import cats.effect.IO
import fs2._
import org.scalatest._
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

import scala.collection.immutable.Seq
import scala.concurrent._
import scala.concurrent.duration._
import scala.util._

object StreamConverterSpec {
  implicit class AwaitHelper[A](f: Future[A]) {
    def await: A = Await.result(f, 3.seconds)
  }

  val numbers: Seq[Int] = 1 to 10
  val error             = new Exception("test")
}

class StreamConverterSpec
    extends TestKit(ActorSystem("test"))
    with AnyWordSpecLike
    with Matchers
    with BeforeAndAfterAll {
  import StreamConverterSpec._

  private def expectError(run: => Any): Assertion =
    intercept[Exception](run).getMessage should be(error.getMessage)

  "An FS2 Stream to AS Source converter" must {
    //
    // FS2 Stream (intern) -> AS Source (extern)
    //
    "propagate elements and completion from stream to source" in {
      val probe  = TestProbe()
      val stream = Stream.emits(numbers).onFinalize[IO](IO(probe.ref ! Success(Done)))
      val source = AkkaSource.fromGraph(StreamConverter(stream))

      source.toMat(AkkaSink.seq)(Keep.right).run().await should be(numbers)
      probe.expectMsg(Success(Done))
    }

    "propagate errors from stream to source" in {
      val stream = Stream.raiseError[IO](error)
      val source = AkkaSource.fromGraph(StreamConverter(stream))

      expectError(source.toMat(AkkaSink.seq)(Keep.right).run().await)
    }

    "propagate cancellation from source to stream (on source completion)" in {
      val probe  = TestProbe()
      val stream = Stream.emits(numbers).onFinalize[IO](IO(probe.ref ! Success(Done)))
      val source = AkkaSource.fromGraph(StreamConverter(stream))

      source.via(AkkaFlow[Int].take(9)).toMat(AkkaSink.seq)(Keep.right).run().await should be(numbers.take(9))
      probe.expectMsg(Success(Done))
    }

    "propagate cancellation from source to stream (on source error)" in {
      val probe  = TestProbe()
      val stream = Stream.emits(numbers).onFinalize[IO](IO(probe.ref ! Success(Done)))
      val source = AkkaSource.fromGraph(StreamConverter(stream))

      expectError(source.toMat(AkkaSink.foreach(_ => throw error))(Keep.right).run().await)
      probe.expectMsg(Success(Done))
    }

  }

}
