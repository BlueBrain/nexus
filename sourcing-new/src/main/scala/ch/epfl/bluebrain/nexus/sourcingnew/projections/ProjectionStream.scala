package ch.epfl.bluebrain.nexus.sourcingnew.projections

import cats.effect.{ConcurrentEffect, Timer}
import cats.implicits._
import ch.epfl.bluebrain.nexus.sourcingnew.projections.ProjectionProgress.NoProgress
import ch.epfl.bluebrain.nexus.sourcingnew.projections.config.PersistProgressConfig
import ch.epfl.bluebrain.nexus.sourcingnew.projections.syntax._
import fs2.{Chunk, Stream}
import io.circe.Encoder

import scala.concurrent.duration.FiniteDuration
import scala.util.control.NonFatal

final case class IndexingConfig(batch: Int, batchTimeout: FiniteDuration)

object ProjectionStream {

  trait StreamOps[F[_], A] {

    implicit def F: ConcurrentEffect[F]

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
              .recoverWith {
                //TODO Handle errors properly
                case NonFatal(err) => F.pure(s.failed(err))
              }
          case e: SkippedMessage      => F.pure(e)
        }
  }

  implicit class SimpleStreamOps[F[_]: Timer, A: Encoder]
    (val stream: Stream[F, Message[A]])(implicit override val F: ConcurrentEffect[F]) extends StreamOps[F, A] {

    type O[_] = Message[_]

    def resource[B, R](fetchResource: A => F[Option[R]], collect: R => Option[B]): Stream[F, Message[B]] =
      stream.mapAsync(1)(toResource(fetchResource, collect))

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
      * Apply the given function for every success message
      *
      * If the function gives an error, the message will be marked as failed,
      * It will remain unmodified otherwise
      *
      * @param f
      * @return
      */
    def runAsync(f: A => F[Unit]): Stream[F, Message[A]] =
      stream.mapAsync(1) { message: Message[A] =>
        message match {
          case s: SuccessMessage[A] =>
            (f(s.value) >> F.pure[Message[A]](s))
              .recoverWith {
                err => F.pure(s.failed(err))
              }
          case v => F.pure(v)
        }
      }

    /**
      * Map over the stream of messages
      * @param projectionId
      * @param initial
      * @param persistErrors
      * @param persistProgress
      * @param config
      * @return
      */
    def persistProgress(projectionId: ProjectionId,
                        initial: ProjectionProgress = NoProgress,
                        persistProgress: (ProjectionId, ProjectionProgress) => F[Unit],
                        persistErrors: (ProjectionId, ErrorMessage) => F[Unit],
                        config: PersistProgressConfig): Stream[F, Unit] =
      stream.mapAsync(1) { message =>
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
        .mapAsync(1) { p =>
          p.last.fold(F.unit) {
            persistProgress(projectionId, _)
          }
        }
  }

  implicit class ChunckStreamOps[F[_]: Timer, A: Encoder]
    (val stream: Stream[F, Chunk[Message[A]]])(implicit override val F: ConcurrentEffect[F]) extends StreamOps[F, A] {

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
      stream.mapAsync(1) { chunk =>
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
      * @param f
      * @return
      */
    def runAsync(f: List[A] => F[Unit]): Stream[F, Chunk[Message[A]]] = stream.mapAsync(1) {
      chunk =>
        val successMessages: List[SuccessMessage[A]] = chunk.toList.collect {
          case s: SuccessMessage[A] => s
        }
        if(successMessages.isEmpty) {
          F.pure(chunk)
        } else {
          (f(successMessages.map(_.value)) >> F.pure(chunk))
            .recoverWith { case err =>
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
