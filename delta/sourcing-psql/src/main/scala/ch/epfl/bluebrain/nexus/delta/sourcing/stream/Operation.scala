package ch.epfl.bluebrain.nexus.delta.sourcing.stream

import cats.data.NonEmptyChain
import cats.syntax.all._
import ch.epfl.bluebrain.nexus.delta.sourcing.model.ElemPipe
import ch.epfl.bluebrain.nexus.delta.sourcing.offset.Offset
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.Elem.{DroppedElem, FailedElem, SuccessElem}
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.ProjectionErr.{LeapingNotAllowedErr, OperationInOutMatchErr}
import com.typesafe.scalalogging.Logger
import fs2.{Chunk, Pipe, Pull, Stream}
import monix.bio.Task
import shapeless.Typeable

import scala.concurrent.duration.FiniteDuration

/**
  * Operations represent individual steps in a [[Projection]] where [[Elem]] values are processed.
  *
  * They are ultimately chained and attached to sources to complete the underlying projection [[Stream]] that is run.
  */
sealed trait Operation { self =>

  /**
    * The underlying element type accepted by the Operation.
    */
  type In

  /**
    * The underlying element type emitted by the Operation.
    */
  type Out

  /**
    * @return
    *   the name of the operation, coinciding with its label before joining with other operation
    */
  def name: String = self.getClass.getSimpleName

  /**
    * @return
    *   the Typeable instance for the accepted element type
    */
  def inType: Typeable[In]

  /**
    * @return
    *   the Typeable instance for the emitted element type
    */
  def outType: Typeable[Out]

  protected[stream] def asFs2: fs2.Pipe[Task, Elem[In], Elem[Out]]

  private[stream] def andThen(that: Operation): Either[OperationInOutMatchErr, Operation] =
    Either.cond(
      self.outType.describe == that.inType.describe,
      new Operation {
        override type In  = self.In
        override type Out = that.Out
        override def inType: Typeable[self.In]   = self.inType
        override def outType: Typeable[that.Out] = that.outType

        override protected[stream] def asFs2: Pipe[Task, Elem[Operation.this.In], Elem[that.Out]] = { stream =>
          stream
            .through(self.asFs2)
            .map {
              _.attempt { value => that.inType.cast(value).toRight(OperationInOutMatchErr(self, that)) }
            }
            .through(that.asFs2)
        }
      },
      OperationInOutMatchErr(self, that)
    )

  /**
    * Send the values through the operation while returning original values
    */
  def tap: Operation = new Operation {
    override type In  = self.In
    override type Out = self.In

    override def inType: Typeable[In] = self.inType

    override def outType: Typeable[Out] = self.inType

    override protected[stream] def asFs2: Pipe[Task, Elem[Operation.this.In], Elem[this.Out]] =
      _.chunks
        .evalTap { chunk =>
          Stream.chunk(chunk).through(self.asFs2).compile.drain
        }
        .flatMap(Stream.chunk)
  }

  /**
    * Logs the elements of this stream as they are pulled.
    *
    * Logging is not done in `F` because this operation is intended for debugging, including pure streams.
    */
  def debug(
      formatter: Elem[Out] => String = (elem: Elem[Out]) => elem.toString,
      logger: String => Unit = println(_)
  ): Operation = new Operation {
    override type In  = self.In
    override type Out = self.Out

    override def inType: Typeable[In] = self.inType

    override def outType: Typeable[Out] = self.outType

    override protected[stream] def asFs2: Pipe[Task, Elem[Operation.this.In], Elem[this.Out]] =
      _.through(self.asFs2).debug(formatter, logger)

  }

  /**
    * Do not apply the operation until the given offset is reached
    * @param offset
    *   the offset to reach before applying the operation
    * @param mapSkip
    *   the function to apply when skipping an element
    */
  def leap[A](offset: Offset, mapSkip: self.In => A)(implicit ta: Typeable[A]): Either[ProjectionErr, Operation] = {
    val pipe = new Operation {
      override type In  = self.In
      override type Out = self.Out

      override def name: String = "Leap"

      override def inType: Typeable[In] = self.inType

      override def outType: Typeable[Out] = self.outType

      override protected[stream] def asFs2: Pipe[Task, Elem[Operation.this.In], Elem[this.Out]] = {
        def go(s: fs2.Stream[Task, Elem[In]]): Pull[Task, Elem[this.Out], Unit] = {
          s.pull.peek.flatMap {
            case Some((chunk, stream)) =>
              val (before, after) = chunk.partitionEither { e =>
                Either.cond(Offset.offsetOrder.gt(e.offset, offset), e, e)
              }
              for {
                evaluated <- Pull.eval(Stream.chunk(after).through(self.asFs2).compile.to(Chunk))
                all        = Chunk.concat(Seq(before.map(_.map(mapSkip)), evaluated)).map {
                               _.attempt { value => this.outType.cast(value).toRight(LeapingNotAllowedErr(self, ta)) }
                             }
                _         <- Pull.output(all)
                next      <- go(stream.tail)
              } yield next
            case None                  => Pull.done
          }
        }
        in => go(in).stream
      }
    }

    Either.cond(
      ta.describe == self.outType.describe,
      pipe,
      LeapingNotAllowedErr(self, ta)
    )
  }

  /**
    * Leap applying the identity function to skipped elements
    * @param offset
    *   the offset to reach before applying the operation
    */
  def identityLeap(offset: Offset): Either[ProjectionErr, Operation] = leap(offset, identity[self.In])(inType)
}

object Operation {

  /**
    * Creates an operation from an fs2 Pipe
    * @param elemPipe
    *   fs2 pipe
    */
  def fromFs2Pipe[I: Typeable](elemPipe: ElemPipe[I, Unit]): Operation = new Operation {
    override type In  = I
    override type Out = Unit
    override def inType: Typeable[In]   = Typeable[In]
    override def outType: Typeable[Out] = Typeable[Out]

    override protected[stream] def asFs2: fs2.Pipe[Task, Elem[In], Elem[Out]] = elemPipe
  }

  def merge(first: Operation, others: Operation*): Either[ProjectionErr, Operation] =
    merge(NonEmptyChain(first, others: _*))

  def merge(operations: NonEmptyChain[Operation]): Either[ProjectionErr, Operation] =
    operations.tail.foldLeftM[Either[ProjectionErr, *], Operation](operations.head) { case (acc, e) =>
      acc.andThen(e)
    }

  private[stream] val logger: Logger = Logger[Operation]

  /**
    * Pipes represent individual steps in a [[Projection]] where [[SuccessElem]] values are processed to produce other
    * [[Elem]] values. They apply [[SuccessElem]] of type [[Pipe#In]] and produce [[Elem]] of type [[Pipe#Out]].
    *
    * Pipes can perform all sorts of objectives like filtering and transformations of any kind.
    *
    * They are ultimately chained and attached to sources to complete the underlying projection [[Stream]] that is run.
    */
  trait Pipe extends Operation {

    /**
      * @return
      *   the reference that represents the specific operation type
      */
    def ref: PipeRef

    override def name: String = ref.label.value

    /**
      * The pipe behaviour left abstract to be implemented by each specific pipe. It takes an element of type In that up
      * to this pipe has been processed successfully and returns another element (possibly failed, dropped) of type Out.
      * @param element
      *   the element to process
      * @return
      *   a new element (possibly failed, dropped) of type Out
      */
    def apply(element: SuccessElem[In]): Task[Elem[Out]]

    /**
      * Checks if the provided envelope has a successful element value of type `I`. If true, it will return it in Right.
      * Otherwise it will return it in Left with the type `O`. This is safe because [[Elem]] is covariant.
      *
      * @param element
      *   an envelope with an Elem to be tested
      */
    protected def partitionSuccess[I, O](element: Elem[I]): Either[Elem[O], SuccessElem[I]] =
      element match {
        case _: SuccessElem[_]              => Right(element.asInstanceOf[SuccessElem[I]])
        case _: FailedElem | _: DroppedElem => Left(element.asInstanceOf[Elem[O]])
      }

    protected[stream] def asFs2: fs2.Pipe[Task, Elem[In], Elem[Out]] = {
      def go(s: fs2.Stream[Task, Elem[In]]): Pull[Task, Elem[Out], Unit] = {
        s.pull.uncons1.flatMap {
          case Some((head, tail)) =>
            partitionSuccess(head) match {
              case Right(value) =>
                Pull
                  .eval(
                    apply(value)
                      .onErrorHandleWith { err =>
                        Task
                          .delay(
                            logger.error(s"Error while applying pipe $name on element ${value.id}", err)
                          )
                          .as(value.failed(err))
                      }
                  )
                  .flatMap(Pull.output1) >> go(tail)
              case Left(other)  =>
                Pull.output1(other) >> go(tail)
            }
          case None               => Pull.done
        }
      }
      in => go(in).stream
    }
  }

  object Pipe {

    /**
      * Create an identity pipe that just pass along the elem
      */
    def identity[A: Typeable]: Pipe = new Pipe {
      override def ref: PipeRef = PipeRef.unsafe("identity")

      override type In  = A
      override type Out = A

      override def inType: Typeable[In]   = Typeable[In]
      override def outType: Typeable[Out] = Typeable[Out]

      override def apply(element: SuccessElem[In]): Task[Elem[Out]] = Task.pure(element)
    }

  }

  trait Sink extends Operation {

    type Out = Unit
    def outType: Typeable[Out] = Typeable[Unit]

    def chunkSize: Int

    def maxWindow: FiniteDuration

    def apply(elements: Chunk[Elem[In]]): Task[Chunk[Elem[Unit]]]

    protected[stream] def asFs2: fs2.Pipe[Task, Elem[In], Elem[Unit]] =
      _.groupWithin(chunkSize, maxWindow)
        .evalMap { chunk =>
          apply(chunk)
        }
        .flatMap(Stream.chunk)
  }

  type Aux[I, O] = Operation {
    type In  = I
    type Out = O
  }
}
