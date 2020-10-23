package ch.epfl.bluebrain.nexus.sourcing.projections

import akka.persistence.query.Offset
import cats.implicits._
import ch.epfl.bluebrain.nexus.sourcing.config.PersistProgressConfig
import ch.epfl.bluebrain.nexus.sourcing.projections.ProjectionProgress.NoProgress
import ch.epfl.bluebrain.nexus.sourcing.projections.syntax._
import com.typesafe.scalalogging.Logger
import fs2.{Chunk, Stream}
import monix.bio.Task
import monix.catnap.SchedulerEffect
import monix.execution.Scheduler

import scala.util.control.NonFatal

object ProjectionStream {

  private val log = Logger("ProjectionStream")

  trait StreamOps[A] {

    implicit def projectionId: ProjectionId

    //TODO: Properly handle errors
    protected def onError[B](s: SuccessMessage[A]): PartialFunction[Throwable, Task[Message[B]]] = {
      case NonFatal(err) =>
        val msg = s"Exception caught while running for message '${s.value}' for projection $projectionId"
        log.error(msg, err)
        // Mark the message as failed
        Task.pure(s.failed(err))
    }

    protected def toResource[B, R](
        fetchResource: A => Task[Option[R]],
        collect: R => Option[B]
    ): Message[A] => Task[Message[B]] = {
      case message @ (s: SuccessMessage[A]) =>
        fetchResource(s.value)
          .map {
            _.flatMap(collect) match {
              case Some(value) =>
                message.asInstanceOf[Message[A]].map(_ => value)
              case _           => s.discarded
            }
          }
          .recoverWith(onError(s))
      case e: SkippedMessage                => Task.pure(e)
    }
  }

  /**
    * Provides extensions methods for fs2.Stream[Message] to implement projections
    * @param stream the stream to run
    * @param projectionId the id of the given projection
    */
  implicit class SimpleStreamOps[A](val stream: Stream[Task, Message[A]])(implicit
      override val projectionId: ProjectionId,
      scheduler: Scheduler
  ) extends StreamOps[A] {

    import cats.effect._

    implicit val timer: Timer[Task] = SchedulerEffect.timer[Task](scheduler)

    def resource[B, R](fetchResource: A => Task[Option[R]], collect: R => Option[B]): Stream[Task, Message[B]] =
      stream.evalMap(toResource(fetchResource, collect))

    /**
      * Fetch a resource without transformation
      *
      * @param fetchResource how to get the resource
      */
    def resourceIdentity[R](fetchResource: A => Task[Option[R]]): Stream[Task, Message[R]] =
      resource(fetchResource, (r: R) => Option(r))

    /**
      * Fetch a resource and maps and filter thanks to a partial function
      *
      * @param fetchResource how to get the resource
      * @param collect       how to filter and map the resource
      */
    def resourceCollect[B, R](
        fetchResource: A => Task[Option[R]],
        collect: PartialFunction[R, B]
    ): Stream[Task, Message[B]] =
      resource(fetchResource, collect.lift)

    /**
      * On replay, skip all messages with a offset lower than the
      * starting offset
      *
      * @param offset the offset to discard from
      */
    def discardOnReplay(offset: Offset): Stream[Task, Message[A]] =
      stream.map {
        case s: SuccessMessage[A] if !s.offset.gt(offset) => s.discarded
        case other                                        => other
      }

    /**
      * Apply the given function for every success message
      *
      * If the function gives an error, the message will be marked as failed,
      * It will remain unmodified otherwise
      *
      * @param f the function to apply to each success message
      * @param predicate to apply f only to the messages matching this predicate
      *                  (for example, based on the offset during a replay)
      */
    def runAsync(f: A => Task[Unit], predicate: Message[A] => Boolean = Message.always): Stream[Task, Message[A]] =
      stream.evalMap {
        case s: SuccessMessage[A] if predicate(s) =>
          (f(s.value) >> Task.pure[Message[A]](s))
            .recoverWith(onError(s))
        case v                                    => Task.pure(v)
      }

    /**
      * Map over the stream of messages
      * @param initial where we started
      * @param persistErrors how we persist errors
      * @param persistProgress how we persist progress
      * @param config the config
      */
    def persistProgress(
        initial: ProjectionProgress = NoProgress,
        persistProgress: (ProjectionId, ProjectionProgress) => Task[Unit],
        persistErrors: (ProjectionId, ErrorMessage) => Task[Unit],
        config: PersistProgressConfig
    ): Stream[Task, Unit] =
      stream
        .evalMap {
          case message @ (e: ErrorMessage) => persistErrors(projectionId, e) >> Task.pure(message)
          case message @ (_: Message[A])   => Task.pure(message)
        }
        .scan(initial) { (acc, m) =>
          m match {
            case m if m.offset.gt(initial.offset) => acc + m
            case _                                => acc
          }
        }
        .groupWithin(config.maxBatchSize, config.maxTimeWindow)
        .filter(_.nonEmpty)
        .evalMap { p =>
          p.last.fold(Task.unit) {
            persistProgress(projectionId, _)
          }
        }
  }

  /**
    * Provides extensions methods for fs2.Stream[Chunk] of messages to implement projections
    *
    * @param stream the stream to run
    * @param projectionId the id of the projection
    */
  implicit class ChunckStreamOps[A](val stream: Stream[Task, Chunk[Message[A]]])(implicit
      override val projectionId: ProjectionId
  ) extends StreamOps[A] {

    private def discardDuplicates(chunk: Chunk[Message[A]]): List[Message[A]] = {
      chunk.toList
        .foldRight((Set.empty[String], List.empty[Message[A]])) {
          // If we have seen the id before, we discard
          case (current: SuccessMessage[A], (seen, result)) if seen.contains(current.persistenceId) =>
            (seen, current.discarded :: result)
          // New persistence id, we add it to the seeen list and we keep it
          case (current: SuccessMessage[A], (seen, result))                                         =>
            (seen + current.persistenceId, current :: result)
          // Discarded or error message, we keep them that way
          case (current, (seen, result))                                                            =>
            (seen, current :: result)
        }
        ._2
    }

    /**
      * Detects duplicates with same persistenceId and discard them
      * Keeps the last occurence for a given persistenceId
      */
    def discardDuplicates(): Stream[Task, Chunk[Message[A]]] =
      stream.map { c =>
        Chunk.seq(discardDuplicates(c))
      }

    /**
      * Detects duplicates with same persistenceId, discard them and flatten chunks
      * Keeps the last occurence for a given persistenceId
      */
    def discardDuplicatesAndFlatten(): Stream[Task, Message[A]] =
      stream.flatMap { c =>
        Stream.emits(discardDuplicates(c))
      }

    /**
      * Fetch then filter and maps them
      * @param fetchResource how to fetch the resource
      * @param collect       how to filter and map it
      */
    def resource[B, R](fetchResource: A => Task[Option[R]], collect: R => Option[B]): Stream[Task, Chunk[Message[B]]] =
      stream.evalMap { chunk =>
        chunk.map(toResource(fetchResource, collect)).sequence
      }

    /**
      * Fetch a resource without transformation
      * @param fetchResource how to fetch the resource
      */
    def resourceIdentity[R](fetchResource: A => Task[Option[R]]): Stream[Task, Chunk[Message[R]]] =
      resource(fetchResource, (r: R) => Option(r))

    /**
      * Fetch a resource and maps and filter thanks to a partial function
      * @param fetchResource how to fetch the resource
      * @param collect       how to filter and map it
      */
    def resourceCollect[B, R](
        fetchResource: A => Task[Option[R]],
        collect: PartialFunction[R, B]
    ): Stream[Task, Chunk[Message[B]]] =
      resource(fetchResource, collect.lift)

    /**
      * Applies the function as a batch for every success message in a chunk
      *
      * If an error occurs for any of this messages, every success message in the
      * chunk will be marked as failed for the same reason
      *
      * @param f the function to apply to each success message of the chunk
      * @param predicate to apply f only to the messages matching this predicate  (for example, based on the offset during a replay)
      */
    def runAsync(
        f: List[A] => Task[Unit],
        predicate: Message[A] => Boolean = Message.always
    ): Stream[Task, Chunk[Message[A]]] =
      stream.evalMap { chunk =>
        val successMessages: List[SuccessMessage[A]] = chunk.toList.collect {
          case s: SuccessMessage[A] if predicate(s) => s
        }
        if (successMessages.isEmpty) {
          Task.pure(chunk)
        } else {
          (f(successMessages.map(_.value)) >> Task.pure(chunk))
            .recoverWith { case NonFatal(err) =>
              log.error(
                s"An exception occurred while running 'runAsync' on elements $successMessages for projection $projectionId",
                err
              )
              Task.pure(
                chunk.map {
                  case s: SuccessMessage[A] => s.failed(err)
                  case m                    => m
                }
              )
            }
        }
      }
  }

}
