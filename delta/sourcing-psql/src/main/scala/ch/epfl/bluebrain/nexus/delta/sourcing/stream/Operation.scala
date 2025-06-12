package ch.epfl.bluebrain.nexus.delta.sourcing.stream

import cats.data.NonEmptyChain
import cats.effect.IO
import cats.syntax.all.*
import ch.epfl.bluebrain.nexus.delta.kernel.Logger
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.Elem.SuccessElem
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.ProjectionErr.OperationInOutMatchErr
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.config.BatchConfig
import fs2.{Pull, Stream}
import shapeless.Typeable

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

  protected[stream] def asFs2: ElemPipe[In, Out]

  private[stream] def andThen(that: Operation): Either[OperationInOutMatchErr, Operation] =
    Either.cond(
      self.outType.describe == that.inType.describe,
      new Operation {
        override type In  = self.In
        override type Out = that.Out
        override def inType: Typeable[self.In]   = self.inType
        override def outType: Typeable[that.Out] = that.outType

        override protected[stream] def asFs2: ElemPipe[Operation.this.In, that.Out] = { stream =>
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

    override protected[stream] def asFs2: ElemPipe[Operation.this.In, this.Out] =
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

    override protected[stream] def asFs2: ElemPipe[Operation.this.In, this.Out] =
      _.through(self.asFs2).debug(formatter, logger)

  }
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

    override protected[stream] def asFs2: ElemPipe[In, Out] = elemPipe
  }

  def merge(first: Operation, others: Operation*): Either[ProjectionErr, Operation] =
    merge(NonEmptyChain(first, others*))

  def merge(operations: NonEmptyChain[Operation]): Either[ProjectionErr, Operation] =
    operations.tail.foldLeftM[Either[ProjectionErr, *], Operation](operations.head) { case (acc, e) =>
      acc.andThen(e)
    }

  private[stream] val logger = Logger[Operation]

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
    def apply(element: SuccessElem[In]): IO[Elem[Out]]

    override protected[stream] def asFs2: ElemPipe[In, Out] = {
      def go(s: ElemStream[In]): Pull[IO, Elem[Out], Unit] = {
        s.pull.uncons.flatMap {
          case Some((chunk, tail)) =>
            Pull
              .eval(
                chunk.traverse {
                  case s: SuccessElem[In] =>
                    apply(s).handleErrorWith { err =>
                      logger
                        .error(err)(s"Error while applying pipe $name on element ${s.id}")
                        .as(s.failed(err))
                    }
                  case other              => IO.pure(other.asInstanceOf[Elem[Out]])
                }
              )
              .flatMap(Pull.output) >> go(tail)
          case None                => Pull.done
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

      override def apply(element: SuccessElem[In]): IO[Elem[Out]] = IO.pure(element)
    }

  }

  trait Sink extends Operation {

    type Out = Unit
    def outType: Typeable[Out] = Typeable[Unit]

    def batchConfig: BatchConfig

    def apply(elements: ElemChunk[In]): IO[ElemChunk[Unit]]

    override protected[stream] def asFs2: ElemPipe[In, Unit] =
      _.groupWithin(batchConfig.maxElements, batchConfig.maxInterval)
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
