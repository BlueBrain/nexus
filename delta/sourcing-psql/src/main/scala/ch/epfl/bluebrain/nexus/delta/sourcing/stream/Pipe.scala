package ch.epfl.bluebrain.nexus.delta.sourcing.stream

import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Label
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.Elem.{DroppedElem, FailedElem, SuccessElem}
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.ElemCtx.SourceIdPipeChainId
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.ProjectionErr.PipeInOutMatchErr
import fs2.Pull
import monix.bio.Task
import shapeless.Typeable

/**
  * Pipes represent individual steps in a [[Projection]] where [[SuccessElem]] values are processed to produce other
  * [[Elem]] values. They apply [[SuccessElem]] of type [[Pipe#In]] and produce [[Elem]] of type [[Pipe#Out]].
  *
  * Pipes can perform all sorts of objectives like filtering and transformations of any kind.
  *
  * They are ultimately chained and attached to sources to complete the underlying projection [[Stream]] that is run.
  */
trait Pipe { self =>

  /**
    * The underlying element type accepted by the Pipe.
    */
  type In

  /**
    * The underlying element type emitted by the Pipe.
    */
  type Out

  /**
    * @return
    *   the label that represents the specific pipe type
    */
  def label: Label

  /**
    * @return
    *   the name of the pipe, coinciding with its label before joining with other sources and pipes
    */
  def name: String = label.value

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

  /**
    * The pipe behaviour left abstract to be implemented by each specific pipe. It takes an element of type In that up
    * to this pipe has been processed successfully and returns another element (possibly failed, dropped) of type Out.
    * @param element
    *   the element to process
    * @return
    *   a new element (possibly failed, dropped) of type Out
    */
  def apply(element: SuccessElem[In]): Task[Elem[Out]]

  private[stream] def asFs2: fs2.Pipe[Task, Elem[In], Elem[Out]] = {
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

  private[stream] def andThen(that: Pipe): Either[PipeInOutMatchErr, Pipe] =
    Either.cond(
      self.outType.describe == that.inType.describe,
      new Pipe {
        override type In  = self.In
        override type Out = that.Out
        override def label: Label                = Label.unsafe("joined")
        override def name: String                = s"${self.name} -> ${that.name}"
        override def inType: Typeable[self.In]   = self.inType
        override def outType: Typeable[that.Out] = that.outType

        override def apply(element: SuccessElem[self.In]): Task[Elem[that.Out]] =
          self.apply(element).flatMap { element =>
            partitionSuccess[self.Out, that.Out](element) match {
              case Right(e)    =>
                that.inType.cast(e.value) match {
                  case Some(value) => that.apply(e.success(value))
                  case None        => Task.pure(e.failed(PipeInOutMatchErr(self, that).reason))
                }
              case Left(value) => Task.pure(value)
            }
          }

        override private[stream] def asFs2 = { stream =>
          stream
            .through(self.asFs2)
            .map { element =>
              partitionSuccess[self.Out, that.In](element) match {
                case Right(e)    =>
                  that.inType.cast(e.value) match {
                    case Some(value) => e.success(value)
                    case None        => e.failed(PipeInOutMatchErr(self, that).reason)
                  }
                case Left(value) => value
              }
            }
            .through(that.asFs2)
        }
      },
      PipeInOutMatchErr(self, that)
    )

  private[stream] def prependPipeChainId(id: Iri): Pipe =
    new Pipe {
      override type In  = self.In
      override type Out = self.Out
      override def label: Label           = self.label
      override def inType: Typeable[In]   = self.inType
      override def outType: Typeable[Out] = self.outType

      override def apply(element: SuccessElem[self.In]): Task[Elem[self.Out]] =
        self.apply(
          element.withCtx(SourceIdPipeChainId(element.ctx.source, id)).asInstanceOf[SuccessElem[self.In]]
        )

      override private[stream] def asFs2 = { stream =>
        stream
          .map(e => e.withCtx(SourceIdPipeChainId(e.ctx.source, id)))
          .through(self.asFs2)
      }
    }

  /**
    * Checks if the provided envelope has a successful element value of type `I`. If true, it will return it in Right.
    * Otherwise it will return it in Left with the type `O`. This is safe because [[Elem]] is covariant.
    *
    * @param element
    *   an envelope with an Elem to be tested
    */
  private def partitionSuccess[I, O](element: Elem[I]): Either[Elem[O], SuccessElem[I]] =
    element match {
      case _: SuccessElem[_]              => Right(element.asInstanceOf[SuccessElem[I]])
      case _: FailedElem | _: DroppedElem => Left(element.asInstanceOf[Elem[O]])
    }
}

object Pipe {

  type Aux[I, O] = Pipe {
    type In  = I
    type Out = O
  }
}
