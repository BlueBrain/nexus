package ch.epfl.bluebrain.nexus.delta.sdk.stream

import akka.NotUsed
import akka.stream._
import akka.stream.scaladsl.{Sink => AkkaSink, Source => AkkaSource, _}
import cats.effect._
import cats.effect.kernel.Resource.ExitCase
import cats.effect.unsafe.implicits._
import fs2._

/**
  * Converts a fs2 stream to an Akka source Original code from the streamz library from Martin Krasser (published under
  * Apache License 2.0):
  * https://github.com/krasserm/streamz/blob/master/streamz-converter/src/main/scala/streamz/converter/Converter.scala
  */
object StreamConverter {

  private def publisherStream[A](publisher: SourceQueueWithComplete[A], stream: Stream[IO, A]): Stream[IO, Unit] = {
    def publish(a: A): IO[Option[Unit]] = IO
      .fromFuture(IO.delay(publisher.offer(a)))
      .flatMap {
        case QueueOfferResult.Enqueued       => IO.pure(Some(()))
        case QueueOfferResult.Failure(cause) => IO.raiseError[Option[Unit]](cause)
        case QueueOfferResult.QueueClosed    => IO.none
        case QueueOfferResult.Dropped        =>
          IO.raiseError[Option[Unit]](
            new IllegalStateException("This should never happen because we use OverflowStrategy.backpressure")
          )
      }
      .recover {
        // This handles a race condition between `interruptWhen` and `publish`.
        // There's no guarantee that, when the akka sink is terminated, we will observe the
        // `interruptWhen` termination before calling publish one last time.
        // Such a call fails with StreamDetachedException
        case _: StreamDetachedException => None
      }

    def watchCompletion: IO[Unit]    = IO.fromFuture(IO.delay(publisher.watchCompletion())).void
    def fail(e: Throwable): IO[Unit] = IO.delay(publisher.fail(e)) >> watchCompletion
    def complete: IO[Unit]           = IO.delay(publisher.complete()) >> watchCompletion

    stream
      .interruptWhen(watchCompletion.attempt)
      .evalMap(publish)
      .unNoneTerminate
      .onFinalizeCase {
        case ExitCase.Succeeded | ExitCase.Canceled => complete
        case ExitCase.Errored(e)                    => fail(e)
      }
  }

  def apply[A](stream: Stream[IO, A]): Graph[SourceShape[A], NotUsed] = {
    val source = AkkaSource.queue[A](0, OverflowStrategy.backpressure)
    // A sink that runs an FS2 publisherStream when consuming the publisher actor (= materialized value) of source
    val sink   = AkkaSink.foreach[SourceQueueWithComplete[A]] { p =>
      // Fire and forget Future so it runs in the background
      publisherStream[A](p, stream).compile.drain.unsafeToFuture()
      ()
    }

    AkkaSource
      .fromGraph(GraphDSL.createGraph(source) { implicit builder => source =>
        import GraphDSL.Implicits._
        builder.materializedValue ~> sink
        SourceShape(source.out)
      })
      .mapMaterializedValue(_ => NotUsed)
  }

  def apply[A](
      source: Graph[SourceShape[A], NotUsed]
  )(implicit materializer: Materializer): Stream[IO, A] =
    Stream.force {
      IO.delay {
        val subscriber = AkkaSource.fromGraph(source).toMat(AkkaSink.queue[A]())(Keep.right).run()
        subscriberStream[A](subscriber)
      }
    }

  private def subscriberStream[A](
      subscriber: SinkQueueWithCancel[A]
  ): Stream[IO, A] = {
    val pull   = IO.fromFuture(IO.delay(subscriber.pull()))
    val cancel = IO.delay(subscriber.cancel())
    Stream.repeatEval(pull).unNoneTerminate.onFinalize(cancel)
  }

}
