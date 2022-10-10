package ch.epfl.bluebrain.nexus.delta.sourcing.stream

import ch.epfl.bluebrain.nexus.delta.sourcing.model.Label
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.Elem.{DroppedElem, FailedElem, SuccessElem}
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.ProjectionErr.OperationInOutMatchErr
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
            .map { element =>
              partitionSuccess[self.Out, that.In](element) match {
                case Right(e)    =>
                  that.inType.cast(e.value) match {
                    case Some(value) => e.success(value)
                    case None        => e.failed(OperationInOutMatchErr(self, that))
                  }
                case Left(value) => value
              }
            }
            .through(that.asFs2)
        }
      },
      OperationInOutMatchErr(self, that)
    )

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

}

object Operation {

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
      *   the label that represents the specific operation type
      */
    def label: Label

    override def name: String = label.value

    /**
      * The pipe behaviour left abstract to be implemented by each specific pipe. It takes an element of type In that up
      * to this pipe has been processed successfully and returns another element (possibly failed, dropped) of type Out.
      * @param element
      *   the element to process
      * @return
      *   a new element (possibly failed, dropped) of type Out
      */
    def apply(element: SuccessElem[In]): Task[Elem[Out]]

    protected[stream] def asFs2: fs2.Pipe[Task, Elem[In], Elem[Out]] = {
      def go(s: fs2.Stream[Task, Elem[In]]): Pull[Task, Elem[Out], Unit] = {
        s.pull.uncons1.flatMap {
          case Some((head, tail)) =>
            partitionSuccess(head) match {
              case Right(value) => Pull.eval(apply(value)).flatMap(Pull.output1) >> go(tail)
              case Left(other)  => Pull.output1(other) >> go(tail)
            }
          case None               => Pull.done
        }
      }
      in => go(in).stream
    }
  }

  trait Sink  extends Operation {

    type Out = Unit
    def outType: Typeable[Out] = Typeable[Unit]

    def chunkSize: Int

    def maxWindow: FiniteDuration

    def apply(elements: Chunk[Elem[In]]): Task[Chunk[Elem[Unit]]]

    protected[stream] def asFs2: fs2.Pipe[Task, Elem[In], Elem[Unit]] = {
      in =>
        in.groupWithin(chunkSize, maxWindow).evalMapChunk { chunk =>
          apply(chunk)
        }.flatMap(Stream.chunk)
    }
  }

  type Aux[I, O] = Operation {
    type In  = I
    type Out = O
  }
}
