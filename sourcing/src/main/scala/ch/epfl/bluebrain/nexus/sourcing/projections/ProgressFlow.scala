package ch.epfl.bluebrain.nexus.sourcing.projections

import akka.persistence.query.{NoOffset, Offset}
import akka.stream.FlowShape
import akka.stream.scaladsl._
import cats.effect.syntax.all._
import cats.effect.{Effect, Timer}
import cats.implicits._
import cats.{Id, Traverse}
import ch.epfl.bluebrain.nexus.sourcing.projections.syntax._
import ch.epfl.bluebrain.nexus.sourcing.projections.IndexingConfig.PersistProgressConfig
import ch.epfl.bluebrain.nexus.sourcing.projections.ProgressFlow.Eval._
import ch.epfl.bluebrain.nexus.sourcing.projections.ProgressFlow._
import ch.epfl.bluebrain.nexus.sourcing.projections.ProjectionProgress.NoProgress
import ch.epfl.bluebrain.nexus.sourcing.projections.ProjectionProgress.ProgressStatus.Failed
import com.typesafe.scalalogging.Logger

import scala.concurrent.duration.FiniteDuration
import scala.concurrent.{ExecutionContext, Future}
import scala.reflect.ClassTag

/**
  * A flow that manages progress (fails and discards). When a message is discarded in some stage of the stream,
  * it will keep propagating to the rest of the stream stages, but it won't execute. However, it will keep and modify
  * the progress when required.
  *
  * @param F the effect type
  * @tparam CIn  the flow input higher kinded type which implements [[Traverse]]. In the concrete implementation, List[_] or Id[_] are used
  * @tparam COut the flow output higher kinded type which implements [[Traverse]]. In the concrete implementation, List[_] or Id[_] are used
  * @tparam In   the flow input type, which is going to be: CIn[PairMsg[In]]
  * @tparam Out  the flow output type, which is going to be: COut[PairMsg[Out]]. Note that the API only offers operations with Out,
  *             e.g.: Out => F[B], hiding the rest of the types.
  **/
sealed abstract class ProgressFlow[F[_], CIn[_]: Traverse, COut[_]: Traverse, In, Out](
    implicit ec: ExecutionContext,
    F: Effect[F]
) {

  type R[Out0] <: ProgressFlow[F, CIn, COut, In, Out0]
  type I = CIn[PairMsg[In]]
  type O = COut[PairMsg[Out]]

  /**
    * @return the underlying flow
    */
  def flow: Flow[I, O, _]

  protected def apply[Out0](flow: Flow[I, COut[PairMsg[Out0]], _]): R[Out0]

  // TODO: Retry on server errors here
  protected def logAndFail[B](message: Message[Out], action: String): PartialFunction[Throwable, F[PairMsg[B]]] = {
    case err =>
      val msg =
        s"Exception caught while running '$action' for message '${message.value}' in progress '${message.currProgressId}'"
      log.error(msg, err)
      F.pure(Left(message.fail(s"$msg with message '${err.getMessage}'").unit))
  }

  /**
    * Transform the underlying stream by applying the given function to each of the Right side messages
    * as they pass through this processing step.
    *
    * If the passing message is a Left, the given function won't be applied.
    */
  def map[B](f: Out => B): R[B] =
    apply(flow.map(_.map {
      case Right(message) => Right(message.map(f))
      case Left(status)   => Left(status.discard())
    }))

  /**
    * Transform the underlying stream by applying the given partial function to each of the Right side messages
    * as they pass through this processing step. If the function is not defined for a certain passing message,
    * a Left will be emitted downstream.
    *
    * If the passing message is a Left, the given function won't be applied.
    */
  def collect[B](pf: PartialFunction[Out, B]): R[B] =
    collectOption(pf.lift)

  private def collectOption[B](f: Out => Option[B]): R[B] =
    apply(flow.map(_.map {
      case Right(message) => f(message.value).map(r => message.map(_ => r)).toRight(message.discard().unit)
      case Left(status)   => Left(status.discard())
    }))

  /**
    * If Out is an Option[B], transforms the passing message from the stream to a provided B.
    *
    * @see collect
    */
  def collectSome[B](implicit ev: Out =:= Option[B]): R[B] =
    collectOption[B](ev)

  /**
    * Transforms the passing message from the stream to a provided B if the casting is possible.
    * Discard otherwise and emit a Left
    *
    * @see collect
    */
  def collectCast[B](implicit B: ClassTag[B]): R[B] =
    collect[B] { case B(v) => v }

  /**
    * Transform the underlying stream by applying the given function to each of the Right side messages as they pass through this processing step.
    * The function returns a `Future` and the value of that future will be emitted downstream.
    *
    * If the function `f` throws an exception or if the `Future` is completed with failure, a Left will be emitted downstream.
    * If the passing message is a Left, the given function won't be applied.
    */
  def mapAsync[B](f: Out => F[B]): R[B] =
    apply(flow.mapAsync(1)(_.map {
      case Right(message) =>
        val resultF = f(message.value).map[PairMsg[B]](r => Right(message.map(_ => r)))
        resultF.recoverWith(logAndFail[B](message, "mapAsync")).toIO.unsafeToFuture()
      case Left(status) => Future.successful[PairMsg[B]](Left(status.discard()))
    }.sequence))

  /**
    * Applies the given function to each of the Right side messages as they pass through this processing step.
    * The evaluation of the function can be controlled by the passed ''eval'' field.
    * If eval = After, the function will only be applied when the passing message has an offset greater than the initial offset.
    * If eval = All, the function will always be applied.
    * The function returns a `Future` of unit and the original message value will be emitted downstream.
    *
    * If the function `f` throws an exception or if the `Future` is completed with failure, a Left will be emitted downstream.
    * If the passing message is a Left, the given function won't be applied.
    */
  def runAsync(f: Out => F[Unit])(eval: Eval = All): R[Out] =
    apply(flow.mapAsync(1)(_.map {
      case Right(message) if eval.isAfter && !message.offset.gt(eval.offset) =>
        Future.successful(Right(message))
      case Right(message) =>
        val resultF = f(message.value) >> F.pure[PairMsg[Out]](Right(message))
        resultF.recoverWith(logAndFail[Out](message, "runAsync")).toIO.unsafeToFuture()
      case Left(status) => Future.successful[PairMsg[Out]](Left(status.discard()))
    }.sequence))

  /**
    * Selects the given progressId to each of the messages as they pass through this processing step.
    */
  def select(progressId: String): R[Out] =
    apply(flow.map(_.map(_.bimap(_.nextProgress(progressId), _.nextProgress(progressId)))))

  /**
    * Converts the passing messages from the underlying string when their offset is not greater than the passed ''offset''.
    */
  def evaluateAfter(offset: Offset): R[Out] =
    apply(flow.map(_.map {
      case Right(message) if !message.offset.gt(offset) => Left(message.discard().unit)
      case other                                        => other
    }))

}

object ProgressFlow {

  type PairMsg[A] = Either[Message[Unit], Message[A]]

  private[projections] val log = Logger("ProgressFlow")

  /**
    * A flow that manages progress (fails and discards). This implementation has the COut[_] as [[cats.Id]],
    * meaning we are dealing with single messages.
    *
    * {@inheritDoc}
    */
  final class ProgressFlowElem[F[_]: Timer, CIn[_]: Traverse, In, Out](
      val flow: Flow[CIn[PairMsg[In]], Id[PairMsg[Out]], _]
  )(
      implicit ec: ExecutionContext,
      F: Effect[F]
  ) extends ProgressFlow[F, CIn, Id, In, Out] {

    override type R[Out0] = ProgressFlowElem[F, CIn, In, Out0]

    override def apply[Out0](flow: Flow[I, Id[PairMsg[Out0]], _]): ProgressFlowElem[F, CIn, In, Out0] =
      new ProgressFlowElem(flow)

    private def persistErrors()(implicit projections: Projections[F, String]): ProgressFlowElem[F, CIn, In, Out] =
      apply(flow.mapAsync[Id[PairMsg[Out]]](1) { value =>
        val msg = value.fold(identity, _.unit)
        val f = msg
          .failures()
          .map {
            case (failureId, Failed(error)) =>
              projections.recordFailure(failureId, msg.persistenceId, msg.sequenceNr, msg.offset, error)
          }
          .toList
          .sequence >> F.pure(value)
        f.toIO.unsafeToFuture()
      })

    /**
      * Transform the underlying stream by computing the projection progress for the messages as they pass through this processing step.
      * It stores into the projections the progress at an interval rate provided by configuration.
      * It stores the failures into the projections.
      *
      * @param id the projection id
      * @param initial the initial projection progress
      * @return a new [[Flow]] where the output is a [[ProjectionProgress]]
      */
    def toPersistedProgress(id: String, initial: ProjectionProgress = NoProgress)(
        implicit config: PersistProgressConfig,
        projections: Projections[F, String]
    ): Flow[I, ProjectionProgress, _] = {

      val parallelFlow = Flow.fromGraph(GraphDSL.create() { implicit b =>
        import GraphDSL.Implicits._
        val persistFlow = Flow[ProjectionProgress]
          .groupedWithin(config.persistAfterProcessed, config.maxTimeWindow)
          .filter(_.nonEmpty)
          .mapAsync(1)(p => (projections.recordProgress(id, p.last) >> F.pure(p)).toIO.unsafeToFuture())

        val broadcast = b.add(Broadcast[ProjectionProgress](2))

        broadcast ~> persistFlow ~> Sink.ignore
        FlowShape(broadcast.in, broadcast.out(1))
      })

      persistErrors().toProgress(initial).via(parallelFlow)

    }

    /**
      * Transform the underlying stream by computing the projection progress for the messages as they pass through this processing step.
      *
      * @param initial the initial projection progress
      * @return a new [[Flow]] where the output is a [[ProjectionProgress]]
      */
    def toProgress(
        initial: ProjectionProgress = NoProgress
    ): Flow[I, ProjectionProgress, _] =
      flow
        .scan[ProjectionProgress](initial) { (acc, c) =>
          val msg = c.fold(identity, _.unit)
          msg.progress.foldLeft(acc) {
            case (acc, (id, status)) if msg.offset.gt(initial.progress(id).offset) => acc + (id, msg.offset, status)
            case (acc, _)                                                          => acc
          }
        }

    /**
      * Chunk up this stream into groups of messages received within a time window,
      * or limited by the given number of messages, whatever happens first.
      *
      * @param n        the maximum number of messages the be grouped
      * @param duration the maximum time to wait for the grouping to happen
      */
    def groupedWithin(n: Int, duration: FiniteDuration): ProgressFlowList[F, CIn, In, Out] =
      new ProgressFlowList(flow.groupedWithin(n, duration).map(_.toList))
  }

  object ProgressFlowElem {

    /**
      * Creates a [[ProgressFlowElem]] with the desired effect type ''F[_]'' and input-output value ''A''
      */
    def apply[F[_]: Effect: Timer, A](implicit ec: ExecutionContext): ProgressFlowElem[F, Id, A, A] =
      new ProgressFlowElem[F, Id, A, A](Flow[Id[PairMsg[A]]])

  }

  /**
    * A flow that manages progress (fails and discards). This implementation has the COut[_] as [[List]],
    * meaning we are dealing with list of messages.
    *
    * {@inheritDoc}
    */
  final class ProgressFlowList[F[_]: Timer, CIn[_]: Traverse, In, Out](
      val flow: Flow[CIn[PairMsg[In]], List[PairMsg[Out]], _]
  )(
      implicit F: Effect[F],
      ec: ExecutionContext
  ) extends ProgressFlow[F, CIn, List, In, Out] {

    type R[Out0] = ProgressFlowList[F, CIn, In, Out0]

    def apply[Out0](flow: Flow[I, List[PairMsg[Out0]], _]): ProgressFlowList[F, CIn, In, Out0] =
      new ProgressFlowList(flow)

    /**
      * Apply a distinct function to the messages as they pass through this processing step.
      * This function will look at each processing message (in this case a List) and will
      * emit a Right with the List of unique messages and a Left with the list of repeated messages.
      */
    def distinct(): ProgressFlowList[F, CIn, In, Out] =
      new ProgressFlowList(flow.flatMapConcat { list =>
        val (discarded, unique) = list.reverse.foldLeft((List.empty[Message[Out]], List.empty[PairMsg[Out]])) {
          case ((discarded, unique), err @ Left(_))                             => (discarded, err :: unique)
          case ((discarded, unique), Right(message)) if exists(unique, message) => (message :: discarded, unique)
          case ((discarded, unique), Right(message))                            => (discarded, Right(message) :: unique)
        }
        Source(List(unique, discarded.map(v => Left(v.discard().unit))))
      })

    /**
      * Transforms the underlying stream by emitting each of its passing messages list as an individual message.
      * It converts the current [[ProgressFlowList]] into a [[ProgressFlowElem]]
      */
    def mergeEmit(): ProgressFlowElem[F, CIn, In, Out] =
      new ProgressFlowElem(flow.flatMapConcat(Source(_)))

    /**
      * Transforms the underlying stream by combining each of its passing message list together.
      * The value of the message is lost, but the processed will be kept.
      * It converts the current [[ProgressFlowList]] into a [[ProgressFlowElem]] emitting a Right(Unit)
      */
    def mergeCombine(): ProgressFlowElem[F, CIn, In, Unit] =
      new ProgressFlowElem(
        flow.map(_.foldLeft(Message.empty)(_ addProgressOf _.fold(identity, _.unit))).map[PairMsg[Unit]](Right.apply)
      )

    /**
      * Applies the given function to each of the Right side messages from the list as they pass through this processing step.
      * The evaluation of the function can be controlled by the passed ''eval'' field.
      * If eval = After, the function will only be applied when the passing message has an offset greater than the initial offset.
      * If eval = All, the function will always be applied.
      * The function returns a `Future` of unit and the original message value will be emitted downstream.
      *
      * If the function `f` throws an exception or if the `Future` is completed with failure, a Left will be emitted downstream.
      * For the Left passing messages on the list, the function won't be applied.
      */
    def runAsyncBatch(f: List[Out] => F[Unit])(eval: Eval = Eval.All): ProgressFlowList[F, CIn, In, Out] = {
      apply(flow.mapAsync(1) { list =>
        val toEval = list.collect { case Right(msg) if eval.isAfter && msg.offset.gt(eval.offset) || eval.isAll => msg }
        if (toEval.isEmpty) Future.successful(list)
        else {
          val resultF = f(toEval.map(_.value)) >> F.pure(list)
          resultF
            .recoverWith {
              case err =>
                F.pure(list.map {
                  case Right(msg) if toEval.exists(_.sameIdentifier(msg)) =>
                    val errMsg =
                      s"Exception caught while running 'runAsyncAll' for message '${msg.value}' in progress '${msg.currProgressId}'"
                    log.error(errMsg, err)
                    Left(msg.fail(s"$errMsg with message '${err.getMessage}'").unit)
                  case other => other
                })
            }
            .toIO
            .unsafeToFuture()
        }
      })
    }

    private def exists(list: List[PairMsg[Out]], message: Message[Out]): Boolean =
      list.exists {
        case Left(_)         => false
        case Right(message2) => message2.persistenceId == message.persistenceId
      }
  }

  object ProgressFlowList {

    /**
      * Creates a [[ProgressFlowList]] with the desired effect type ''F[_]'' and input-output value ''A''
      */
    def apply[F[_]: Effect: Timer, A](implicit ec: ExecutionContext): ProgressFlowList[F, List, A, A] =
      new ProgressFlowList[F, List, A, A](Flow[List[PairMsg[A]]])

  }

  /**
    * Enumeration of evaluation types for stream items
    */
  sealed trait Eval extends Product with Serializable {
    def isAll: Boolean
    def isAfter: Boolean = !isAll
    def offset: Offset   = NoOffset
  }

  object Eval {

    /**
      * Evaluates all the emitted items on a Stream
      */
    final case object All extends Eval {
      def isAll: Boolean = true
    }

    /**
      * Do not evaluate emitted items on the stream if they have offset smaller or equal to the passed offset
      */
    final case class After(override val offset: Offset) extends Eval {
      override def isAll: Boolean = false
    }
  }
}
