package ch.epfl.bluebrain.nexus.sourcingnew.projections

import akka.persistence.query.Offset
import cats.effect.{ConcurrentEffect, Timer}
import cats.implicits._
import ch.epfl.bluebrain.nexus.sourcingnew.projections.ProjectionProgress.NoProgress
import ch.epfl.bluebrain.nexus.sourcingnew.projections.config.PersistProgressConfig
import ch.epfl.bluebrain.nexus.sourcingnew.projections.syntax._
import com.typesafe.scalalogging.Logger
import fs2.{Chunk, Stream}
import io.circe.Encoder

import scala.concurrent.duration.FiniteDuration
import scala.util.control.NonFatal

final case class IndexingConfig(batch: Int, batchTimeout: FiniteDuration)

object ProjectionStream {

  private val log = Logger("ProjectionStream")

  trait StreamOps[F[_], A] {

    implicit def F: ConcurrentEffect[F]

    implicit def projectionId: ProjectionId

    //TODO: Properly handle errors
    protected def onError[B](s: SuccessMessage[A]): PartialFunction[Throwable, F[Message[B]]] = {
      case NonFatal(err) =>
        val msg = s"Exception caught while running for message '${s.value}' for projection $projectionId"
        log.error(msg, err)
        // Mark the message as failed
        F.pure(s.failed(err))
    }

    protected def toResource[B, R](fetchResource: A => F[Option[R]], collect: R => Option[B]): Message[A] => F[Message[B]] =
      (message: Message[A]) =>
        message match {
          case s: SuccessMessage[A] =>
            fetchResource(s.value)
              .map {
                _.flatMap(collect) match {
                  case Some(value) =>
                    message.map(_ => value)
                  case _              => s.discarded
                }
              }
              .recoverWith(onError(s))
          case e: SkippedMessage      => F.pure(e)
        }
  }

  /**
    * Provides extensions methods for fs2.Stream[Message[A]] to implement projections
    * @param stream
    * @param projectionId
    * @tparam F
    * @tparam A
    */
  implicit class SimpleStreamOps[F[_]: Timer, A: Encoder]
    (val stream: Stream[F, Message[A]])(implicit override val projectionId: ProjectionId,
                                        override val F: ConcurrentEffect[F]) extends StreamOps[F, A] {

    type O[_] = Message[_]

    def resource[B, R](fetchResource: A => F[Option[R]], collect: R => Option[B]): Stream[F, Message[B]] =
      stream.evalMap(toResource(fetchResource, collect))

    /**
      * Fetch a resource without transformation
      *
      * @param fetchResource
      * @tparam R
      * @return
      */
    def resourceIdentity[R](fetchResource: A => F[Option[R]]): Stream[F, Message[R]] =
      resource(fetchResource, (r: R) => Option(r))

    /**
      * Fetch a resource and maps and filter thanks to a partial function
      *
      * @param fetchResource
      * @param collect
      * @tparam B
      * @tparam R
      * @return
      */
    def resourceCollect[B, R](fetchResource: A => F[Option[R]], collect: PartialFunction[R, B]): Stream[F, O[B]] =
      resource(fetchResource, collect.lift)

    /**
      * On replay, skip all messages with a offset lower than the
      * starting offset
      *
      * @param offset
      * @return
      */
    def discardOnReplay(offset: Offset): Stream[F, Message[A]] = stream.map {
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
      * @param predicate to apply f only to the messages matching this predicate  (for example, based on the offset during a replay)
      * @return
      */
    def runAsync(f: A => F[Unit], predicate: Message[A] => Boolean = Message.always): Stream[F, Message[A]] =
      stream.evalMap { message: Message[A] =>
        message match {
          case s: SuccessMessage[A] if predicate(s) =>
            (f(s.value) >> F.pure[Message[A]](s))
              .recoverWith(onError(s))
          case v => F.pure(v)
        }
      }

    /**
      * Map over the stream of messages
      * @param initial
      * @param persistErrors
      * @param persistProgress
      * @param config
      * @return
      */
    def persistProgress(initial: ProjectionProgress = NoProgress,
                        persistProgress: (ProjectionId, ProjectionProgress) => F[Unit],
                        persistErrors: (ProjectionId, ErrorMessage) => F[Unit],
                        config: PersistProgressConfig): Stream[F, Unit] =
      stream.evalMap { message =>
        message match {
          case e: ErrorMessage => persistErrors(projectionId, e) >> F.pure(message)
          case _: Message[A] => F.pure(message)
        }
      }.scan(initial) { (acc, m) =>
        m match {
          case m if m.offset.gt(initial.offset) => acc + m
          case _ => acc
        }
      }.groupWithin(config.maxBatchSize, config.maxTimeWindow)
        .filter(_.nonEmpty)
        .evalMap { p =>
          p.last.fold(F.unit) {
            persistProgress(projectionId, _)
          }
        }
  }

  /**
    * Provides extensions methods for fs2.Stream[Chunk[Message[A]]] to implement projections
    *
    * @param stream
    * @param projectionId
    * @tparam F
    * @tparam A
    */
  implicit class ChunckStreamOps[F[_]: Timer, A: Encoder]
    (val stream: Stream[F, Chunk[Message[A]]])(implicit override val projectionId: ProjectionId,
                                               override val F: ConcurrentEffect[F]) extends StreamOps[F, A] {

    type O[_] = Chunk[Message[_]]

    private def discardDuplicates(chunk: Chunk[Message[A]]): List[Message[A]] = {
      chunk.toList.foldRight((Set.empty[String], List.empty[Message[A]])) {
        // If we have seen the id before, we discard
        case (current: SuccessMessage[A], (seen, result)) if seen.contains(current.persistenceId) =>
          (seen, current.discarded :: result)
        // New persistence id, we add it to the seeen list and we keep it
        case (current: SuccessMessage[A], (seen, result)) =>
          (seen + current.persistenceId, current :: result)
          // Discarded or error message, we keep them that way
        case (current, (seen, result)) =>
          (seen, current :: result)
      }._2
    }

    /**
      * Detects duplicates with same persistenceId and discard them
      * Keeps the last occurence for a given persistenceId
      *
      * @return
      */
    def discardDuplicates(): Stream[F, Chunk[Message[A]]] = stream.map { c =>
      Chunk.seq(discardDuplicates(c))
    }

    /**
      * Detects duplicates with same persistenceId, discard them and flatten chunks
      * Keeps the last occurence for a given persistenceId
      *
      * @return
      */
    def discardDuplicatesAndFlatten(): Stream[F, Message[A]] = stream.flatMap { c =>
      Stream.emits(discardDuplicates(c))
    }

    /**
      * Fetch then filter and maps them
      * @param fetchResource
      * @param filterMap
      * @tparam B
      * @tparam R
      * @return
      */
    def resource[B, R](fetchResource: A => F[Option[R]], filterMap: R => Option[B]): Stream[F, Chunk[Message[B]]] =
      stream.evalMap { chunk =>
        chunk.map(toResource(fetchResource, filterMap)).sequence
      }

    /**
      * Fetch a resource without transformation
      * @param fetchResource
      * @tparam R
      * @return
      */
    def resourceIdentity[R](fetchResource: A => F[Option[R]]): Stream[F, Chunk[Message[R]]] =
      resource(fetchResource, (r:R) => Option(r))

    /**
      * Fetch a resource and maps and filter thanks to a partial function
      * @param fetchResource
      * @param collect
      * @tparam B
      * @tparam R
      * @return
      */
    def resourceCollect[B, R](fetchResource: A => F[Option[R]], collect: PartialFunction[R, B]): Stream[F, Chunk[Message[B]]] =
      resource(fetchResource, collect.lift)

    /**
      * Applies the function as a batch for every success message in a chunk
      *
      * If an error occurs for any of this messages, every success message in the
      * chunk will be marked as failed for the same reason
      *
      * @param f the function to apply to each success message of the chunk
      * @param predicate to apply f only to the messages matching this predicate  (for example, based on the offset during a replay)
      * @return
      */
    def runAsync(f: List[A] => F[Unit], predicate: Message[A] => Boolean = Message.always): Stream[F, Chunk[Message[A]]] = stream.evalMap {
      chunk =>
        val successMessages: List[SuccessMessage[A]] = chunk.toList.collect {
          case s: SuccessMessage[A] if predicate(s) => s
        }
        if(successMessages.isEmpty) {
          F.pure(chunk)
        } else {
          (f(successMessages.map(_.value)) >> F.pure(chunk))
            .recoverWith { case NonFatal(err) =>
              log.error(s"An exception occurred while running 'runAsync' on elements $successMessages for projection $projectionId", err)
              F.pure(
                chunk.map {
                  case s: SuccessMessage[A] => s.failed(err)
                  case m => m
                }
              )
            }
        }
    }
  }

}
