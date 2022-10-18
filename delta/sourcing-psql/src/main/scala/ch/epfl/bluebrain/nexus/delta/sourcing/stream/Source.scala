package ch.epfl.bluebrain.nexus.delta.sourcing.stream

import cats.data.NonEmptyChain
import cats.implicits._
import ch.epfl.bluebrain.nexus.delta.sourcing.model.ElemStream
import ch.epfl.bluebrain.nexus.delta.sourcing.offset.Offset
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.Elem.SuccessElem
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.ProjectionErr.{SourceOutMatchErr, SourceOutPipeInMatchErr}
import fs2.Stream
import monix.bio.Task
import shapeless.Typeable

/**
  * Sources emit Stream elements of type [[Source#Out]] from a predefined
  * [[ch.epfl.bluebrain.nexus.delta.sourcing.offset.Offset]]. Its elements are wrapped in an [[Elem]] that augments the
  * value with contextual information, like for example whether an element was dropped, its offset etc.
  *
  * A [[Projection]] may make use of multiple [[Source]] s (at least one) that will be further chained with [[Operation]] s
  * and ultimately merged together.
  */
trait Source { self =>

  /**
    * The underlying element type emitted by the Source.
    */
  type Out

  /**
    * @return
    *   the Typeable instance for the emitted element type
    */
  def outType: Typeable[Out]

  /**
    * Name of the source
    * @return
    */
  def name: String = self.getClass.getSimpleName

  /**
    * Seals this Source for further compositions producing an [[fs2.Stream]]. This fn will be applied when compiling a
    * [[Projection]].
    *
    * @param offset
    *   the offset from which the source should emit elements
    * @return
    *   an [[fs2.Stream]] of elements of this Source's Out type
    */
  def apply(offset: Offset): Stream[Task, Elem[Out]]

  private[stream] def through(operation: Operation): Either[SourceOutPipeInMatchErr, Source] =
    Either.cond(
      outType.describe == operation.inType.describe,
      new Source {
        override type Out = operation.Out
        override def outType: Typeable[operation.Out] = operation.outType

        override def apply(offset: Offset): Stream[Task, Elem[operation.Out]] =
          self
            .apply(offset)
            .map {
              case e @ SuccessElem(_, _, _, _, value) =>
                operation.inType.cast(value) match {
                  case Some(value) => e.success(value)
                  case None        => e.failed(SourceOutPipeInMatchErr(self, operation))
                }
              case e                                        => e.asInstanceOf[Elem[operation.In]]
            }
            .through(operation.asFs2)
      },
      SourceOutPipeInMatchErr(self, operation)
    )

  private[stream] def merge(that: Source): Either[SourceOutMatchErr, Source] =
    Either.cond(
      self.outType.describe == that.outType.describe,
      new Source {
        override type Out = self.Out
        override def outType: Typeable[self.Out] = self.outType

        override def apply(offset: Offset): Stream[Task, Elem[Out]] =
          self
            .apply(offset)
            .merge(that.apply(offset).map {
              case e @ SuccessElem(_, _, _, _, value) =>
                self.outType.cast(value) match {
                  case Some(_) => e.asInstanceOf[SuccessElem[Out]]
                  case None    => e.failed(SourceOutMatchErr(self, that))
                }
              case e                                        => e.asInstanceOf[Elem[Out]]
            })
      },
      SourceOutMatchErr(self, that)
    )

  private[stream] def broadcastThrough(operations: NonEmptyChain[Operation]): Either[SourceOutPipeInMatchErr, Source.Aux[Unit]] =
    operations
      .traverse { operation =>
        Either.cond(
          self.outType.describe == operation.inType.describe,
          operation.asInstanceOf[Operation.Aux[self.Out, Unit]],
          SourceOutPipeInMatchErr(self, operation)
        )
      }
      .map { verified =>
        new Source {
          override type Out = Unit
          override def outType: Typeable[Unit] = Typeable[Unit]

          override def apply(offset: Offset): Stream[Task, Elem[Unit]] =
            self
              .apply(offset)
              .broadcastThrough(verified.toList.map { _.asFs2 }: _*)
        }
      }

}

object Source {

  type Aux[O] = Source {
    type Out = O
  }

  def apply[A: Typeable](stream: Offset => ElemStream[A]): Source =
    new Source {
      override type Out = A

      override def outType: Typeable[A] = Typeable[A]

      override def apply(offset: Offset): Stream[Task, Elem[A]] = stream(offset)
    }
}
