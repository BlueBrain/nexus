package ch.epfl.bluebrain.nexus.sourcing.projections

import akka.persistence.query.Offset
import cats.implicits._
import ch.epfl.bluebrain.nexus.sourcing.config.PersistProgressConfig
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

    protected def transform[R](f: A => Task[Option[R]]): Message[A] => Task[Message[R]] = {
      case message @ (s: SuccessMessage[A]) =>
        f(s.value)
          .map {
            case Some(value) => message.asInstanceOf[Message[A]].map(_ => value)
            case _           => s.discarded
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

    /**
      * Transforms the value inside the message using the passed async function.
      * If the result is a None on the regular channel, the message is a discarded message.
      * If the result is on the failure channel, the message is an error message.
      * If the result is a Some(r) on the regular channel, the message is a success message.
      */
    def evalMapFilterValue[R](f: A => Task[Option[R]]): Stream[Task, Message[R]] =
      stream.evalMap(transform(f))

    /**
      * Maps on the value inside the message using the passed function.
      */
    def mapValue[R](f: A => R): Stream[Task, Message[R]] =
      stream.map(_.map(f))

    /**
      * Maps on the value inside the message using the passed function.
      * If the result is a None, the message is a discarded message.
      * If the result is a Some(r), the message is a success message.
      */
    def collectSomeValue[R](f: A => Option[R]): Stream[Task, Message[R]] =
      stream.map(_.map(f)).map {
        case s @ SuccessMessage(_, _, _, None, _)    => s.discarded
        case s @ SuccessMessage(_, _, _, Some(v), _) => s.as(v)
        case s: SkippedMessage                       => s
      }

    /**
      * Filters the value inside the message using the passed function.
      * If the result is a false, the message is a discarded message.
      * If the result is true, the message is a success message.
      */
    def filterValue(f: A => Boolean): Stream[Task, Message[A]] =
      stream.map(_.filter(f))

    /**
      * Flatmaps on the value inside the message using the passed function.
      */
    def flatMapValue[R](f: A => Message[R]): Stream[Task, Message[R]] =
      stream.map(_.flatMap(f))

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
      * Apply the given function that either fails or succeed for every success message
      *
      * @see [[runAsync]]
      */
    def runAsyncUnit(f: A => Task[Unit], predicate: Message[A] => Boolean = Message.always): Stream[Task, Message[A]] =
      runAsync(f.andThenF { _ => Task.pure(RunResult.Success) }, predicate)

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
    def runAsync(f: A => Task[RunResult], predicate: Message[A] => Boolean = Message.always): Stream[Task, Message[A]] =
      stream.evalMap {
        case s: SuccessMessage[A] if predicate(s) =>
          f(s.value)
            .flatMap {
              case RunResult.Success    => Task.pure[Message[A]](s)
              case w: RunResult.Warning => Task.pure[Message[A]](s.addWarning(w))
            }
            .recoverWith(onError(s))
        case v                                    => Task.pure(v)
      }

    def accumulateProgress(initial: ProjectionProgress[A]): Stream[Task, (ProjectionProgress[A], Message[A])] = {
      stream
        .mapAccumulate(initial) {
          case (acc, msg) if msg.offset.gt(initial.offset) => (acc + msg, msg)
          case (acc, msg)                                  => (acc, msg)
        }
    }

    private def persistToProjection(
        chunk: Chunk[(ProjectionProgress[A], Message[A])],
        persistProgress: (ProjectionId, ProjectionProgress[A]) => Task[Unit],
        persistErrors: (ProjectionId, Vector[Message[A]]) => Task[Unit]
    ): Task[Option[A]] = {
      val init: (Option[ProjectionProgress[A]], Vector[Message[A]], Option[A]) = (None, Vector.empty, None)
      val (progress, errors, firstValue)                                       = chunk.foldLeft(init) { case ((_, messages, value), (progress, message)) =>
        val (error, newValue) = message match {
          case SuccessMessage(_, _, _, value, warnings) => Option.when(warnings.nonEmpty)(message) -> Some(value)
          case m                                        => Some(m)                                 -> None
        }
        (Some(progress), messages ++ error, value.orElse(newValue))
      }
      persistErrors(projectionId, errors) >>
        progress.fold(Task.unit)(persistProgress(projectionId, _)) >>
        Task.pure(firstValue)
    }

    /**
      * Map over the stream of messages and persist the progress and errors as well as cache progress
      *
      * @param initial          where we started
      * @param persistErrors    how we persist errors
      * @param persistProgress  how we persist progress
      * @param cacheProgress    how we cache progress
      * @param projectionConfig the config
      * @param cacheConfig      the cache update config
      */
    def persistProgressWithCache(
        initial: ProjectionProgress[A],
        persistProgress: (ProjectionId, ProjectionProgress[A]) => Task[Unit],
        persistErrors: (ProjectionId, Vector[Message[A]]) => Task[Unit],
        cacheProgress: (ProjectionId, ProjectionProgress[A]) => Task[Unit],
        projectionConfig: PersistProgressConfig,
        cacheConfig: PersistProgressConfig
    ): Stream[Task, A] =
      stream
        .accumulateProgress(initial)
        .groupWithin(cacheConfig.maxBatchSize, cacheConfig.maxTimeWindow)
        .evalMap { chunk =>
          chunk.last match {
            case Some((progress, _)) => cacheProgress(projectionId, progress).as(chunk)
            case None                => Task.delay(chunk)
          }
        }
        .flatMap(Stream.chunk)
        .groupWithin(projectionConfig.maxBatchSize, projectionConfig.maxTimeWindow)
        .evalMapFilter(persistToProjection(_, persistProgress, persistErrors))

    /**
      * Map over the stream of messages and persist the progress and errors
      *
      * @param initial         where we started
      * @param persistErrors   how we persist errors
      * @param persistProgress how we persist progress
      * @param config          the config
      */
    def persistProgress(
        initial: ProjectionProgress[A],
        persistProgress: (ProjectionId, ProjectionProgress[A]) => Task[Unit],
        persistErrors: (ProjectionId, Vector[Message[A]]) => Task[Unit],
        config: PersistProgressConfig
    ): Stream[Task, A] =
      stream
        .accumulateProgress(initial)
        .groupWithin(config.maxBatchSize, config.maxTimeWindow)
        .evalMapFilter(persistToProjection(_, persistProgress, persistErrors))

    /**
      * Map over the stream of messages and persist the progress and errors using the given projection
      * @param initial    where we started
      * @param projection the projection to rely on
      * @param config     the config
      */
    def persistProgress(
        initial: ProjectionProgress[A],
        projection: Projection[A],
        config: PersistProgressConfig
    ): Stream[Task, A] =
      persistProgress(
        initial,
        projection.recordProgress,
        projection.recordErrors,
        config
      )

    /**
      * Map over the stream of messages and persist the progress and errors using the given projection with caching the progress.
      * @param initial          where we started
      * @param projection       the projection to rely on
      * @param cacheProgress    how we cache progress
      * @param projectionConfig the config
      * @param cacheConfig      the cache update config
      */
    def persistProgressWithCache(
        initial: ProjectionProgress[A],
        projection: Projection[A],
        cacheProgress: (ProjectionId, ProjectionProgress[A]) => Task[Unit],
        projectionConfig: PersistProgressConfig,
        cacheConfig: PersistProgressConfig
    ): Stream[Task, A] =
      persistProgressWithCache(
        initial,
        projection.recordProgress,
        projection.recordErrors,
        cacheProgress,
        projectionConfig,
        cacheConfig
      )
  }

  /**
    * Provides extensions methods for fs2.Stream[Chunk] of messages to implement projections
    *
    * @param stream the stream to run
    * @param projectionId the id of the projection
    */
  implicit class ChunkStreamOps[A](val stream: Stream[Task, Chunk[Message[A]]])(implicit
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
      * Transforms the value inside each message on the chunk using the passed async function.
      * If the result is a None on the regular channel, the message is a discarded message.
      * If the result is on the failure channel, the message is an error message.
      * If the result is a Some(r) on the regular channel, the message is a success message.
      */
    def evalMapFilterValue[R](f: A => Task[Option[R]]): Stream[Task, Chunk[Message[R]]] =
      stream.evalMap { chunk =>
        chunk.traverse(transform(f))
      }

    /**
      * Maps on the values inside the messages' chunks using the passed function.
      */
    def mapValue[R](f: A => R): Stream[Task, Chunk[Message[R]]] =
      stream.map { chunk =>
        chunk.map(_.map(f))
      }

    /**
      * Maps on the values inside the messages' chunks using the passed function.
      * If the result is a None, the message is a discarded message.
      * If the result is a Some(r), the message is a success message.
      */
    def collectSomeValue[R](f: A => Option[R]): Stream[Task, Chunk[Message[R]]] =
      stream.map { chunk =>
        chunk.map(_.map(f)).map {
          case s @ SuccessMessage(_, _, _, None, _)    => s.discarded
          case s @ SuccessMessage(_, _, _, Some(v), _) => s.as(v)
          case s: SkippedMessage                       => s
        }
      }

    /**
      * Filters the values inside the messages chunks using the passed function.
      * If the result is a false, the message is a discarded message.
      * If the result is true, the message is a success message.
      */
    def filterValue(f: A => Boolean): Stream[Task, Chunk[Message[A]]] =
      stream.map { chunk =>
        chunk.map(_.filter(f))
      }

    /**
      * Flatmaps on the values inside the messages' chunks using the passed function.
      */
    def flatMapValue[R](f: A => Message[R]): Stream[Task, Chunk[Message[R]]] =
      stream.map { chunk =>
        chunk.map(_.flatMap(f))
      }

    /**
      * Apply the given function that either fails or succeed for every success message in a chunk
      *
      * @see [[runAsync]]
      */
    def runAsyncUnit(
        f: List[A] => Task[Unit],
        predicate: Message[A] => Boolean = Message.always
    ): Stream[Task, Chunk[Message[A]]] =
      runAsync(f.andThenF { _ => Task.pure(RunResult.Success) }, predicate)

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
        f: List[A] => Task[RunResult],
        predicate: Message[A] => Boolean = Message.always
    ): Stream[Task, Chunk[Message[A]]] =
      stream.evalMap { chunk =>
        val successMessages: List[SuccessMessage[A]] = chunk.toList.collect {
          case s: SuccessMessage[A] if predicate(s) => s
        }
        if (successMessages.isEmpty) {
          Task.pure(chunk)
        } else {
          f(successMessages.map(_.value))
            .flatMap {
              case RunResult.Success    => Task.pure(chunk)
              case w: RunResult.Warning =>
                Task.pure(
                  chunk.map {
                    case s: SuccessMessage[A] => s.addWarning(w)
                    case m                    => m
                  }
                )
            }
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
