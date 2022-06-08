package ch.epfl.bluebrain.nexus.delta.sdk.stream

import akka.NotUsed
import akka.stream._
import akka.stream.scaladsl.{Sink => AkkaSink, Source => AkkaSource, _}
import cats.effect._
import fs2._
import monix.bio.Task
import monix.execution.Scheduler

/**
  * Converts a fs2 stream to an Akka source Original code from the streamz library from Martin Krasser (published under
  * Apache License 2.0):
  * https://github.com/krasserm/streamz/blob/master/streamz-converter/src/main/scala/streamz/converter/Converter.scala
  */
object StreamConverter {

  private def publisherStream[A](publisher: SourceQueueWithComplete[A], stream: Stream[Task, A]): Stream[Task, Unit] = {
    def publish(a: A): Task[Option[Unit]] = Task
      .fromFuture(publisher.offer(a))
      .flatMap {
        case QueueOfferResult.Enqueued       => Task.some(())
        case QueueOfferResult.Failure(cause) => Task.raiseError[Option[Unit]](cause)
        case QueueOfferResult.QueueClosed    => Task.none
        case QueueOfferResult.Dropped        =>
          Task.raiseError[Option[Unit]](
            new IllegalStateException("This should never happen because we use OverflowStrategy.backpressure")
          )
      }
      .onErrorRecover {
        // This handles a race condition between `interruptWhen` and `publish`.
        // There's no guarantee that, when the akka sink is terminated, we will observe the
        // `interruptWhen` termination before calling publish one last time.
        // Such a call fails with StreamDetachedException
        case _: StreamDetachedException => None
      }

    def watchCompletion: Task[Unit]    = Task.fromFuture(publisher.watchCompletion()).void
    def fail(e: Throwable): Task[Unit] = Task.delay(publisher.fail(e)) >> watchCompletion
    def complete: Task[Unit]           = Task.delay(publisher.complete()) >> watchCompletion

    stream
      .interruptWhen(watchCompletion.attempt)
      .evalMap(publish)
      .unNoneTerminate
      .onFinalizeCase {
        case ExitCase.Completed | ExitCase.Canceled => complete
        case ExitCase.Error(e)                      => fail(e)
      }
  }

  def apply[A](stream: Stream[Task, A])(implicit s: Scheduler): Graph[SourceShape[A], NotUsed] = {
    val source = AkkaSource.queue[A](0, OverflowStrategy.backpressure)
    // A sink that runs an FS2 publisherStream when consuming the publisher actor (= materialized value) of source
    val sink   = AkkaSink.foreach[SourceQueueWithComplete[A]] { p =>
      // Fire and forget Future so it runs in the background
      publisherStream[A](p, stream).compile.drain.runToFuture
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

}
