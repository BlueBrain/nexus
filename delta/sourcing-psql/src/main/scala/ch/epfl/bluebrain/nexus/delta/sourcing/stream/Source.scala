package ch.epfl.bluebrain.nexus.delta.sourcing.stream

import cats.data.NonEmptyChain
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{Envelope, Label}
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.Elem.{FailedElem, SuccessElem}
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.ProjectionErr.{SourceOutMatchErr, SourceOutPipeInMatchErr}
import fs2.Stream
import monix.bio.Task
import shapeless.Typeable
import cats.implicits._

/**
  * Sources emit Stream elements of type [[Source#Out]] from a predefined
  * [[ch.epfl.bluebrain.nexus.delta.sourcing.offset.Offset]]. Its elements are wrapped in an [[Elem]] and [[Envelope]]
  * that augments the value with contextual information, like for example whether an element was skipped, dropped, its
  * offset etc. They are uniquely identified with an id of type [[Iri]].
  *
  * A [[Projection]] may make use of multiple [[Source]] s (at least one) that will be further chained with [[Pipe]] s
  * and ultimately merged together.
  */
trait Source { self =>

  /**
    * The underlying element type emitted by the Source.
    */
  type Out

  /**
    * @return
    *   an unique identifier for the source instance
    */
  def id: Iri

  /**
    * @return
    *   the label that represents the specific source type
    */
  def label: Label

  /**
    * @return
    *   the name of the source, coinciding with its label before merging and a tuple of the underlying source names
    *   after merging
    */
  def name: String = label.value

  /**
    * @return
    *   the Typeable instance for the emitted element type
    */
  def outType: Typeable[Out]

  /**
    * Seals this Source for further compositions producing an [[fs2.Stream]]. This fn will be applied when compiling a
    * [[Projection]].
    *
    * @param offset
    *   the offset from which the source should emit elements
    * @param skipUntilOffset
    *   When true, the Source must emit elements using [[ch.epfl.bluebrain.nexus.delta.sourcing.offset.Offset.Start]]
    *   but discard the underlying element value and emit only the contextual information with a [[Elem.SkippedElem]];
    *   when the last element has the provided [[ch.epfl.bluebrain.nexus.delta.sourcing.offset.Offset]] the Source
    *   should stop discarding element values and emit elements as [[Elem.SuccessElem]]. When false, the Source must
    *   emit elements with their value as [[Elem.SuccessElem]]. Note: the value of this flag would have no effect when
    *   the function would be applied with ([[ch.epfl.bluebrain.nexus.delta.sourcing.offset.Offset.Start]], _).
    *
    * @return
    *   an [[fs2.Stream]] of elements of this Source's Out type
    */
  def apply(offset: ProjectionOffset, skipUntilOffset: Boolean): Stream[Task, Envelope[Iri, Elem[Out]]]

  private[stream] def through(pipe: Pipe): Either[SourceOutPipeInMatchErr, Source] =
    Either.cond(
      outType.describe == pipe.inType.describe,
      new Source {
        override type Out = pipe.Out
        override def id: Iri                     = self.id
        override def label: Label                = Label.unsafe("chained")
        override def name: String                = s"${self.name} -> ${pipe.name}"
        override def outType: Typeable[pipe.Out] = pipe.outType

        override def apply(offset: ProjectionOffset, skip: Boolean): Stream[Task, Envelope[Iri, Elem[pipe.Out]]] =
          self
            .apply(offset, skip)
            .map { element =>
              element.value match {
                case SuccessElem(ctx, e) =>
                  pipe.inType.cast(e) match {
                    case Some(value) => element.copy(value = SuccessElem(ctx, value))
                    case None        => element.copy(value = FailedElem(ctx, SourceOutPipeInMatchErr(self, pipe).reason))
                  }
                case _                   => element.asInstanceOf[Envelope[Iri, Elem[pipe.In]]]
              }
            }
            .through(pipe.asFs2)
      },
      SourceOutPipeInMatchErr(self, pipe)
    )

  private[stream] def merge(that: Source): Either[SourceOutMatchErr, Source] =
    Either.cond(
      self.outType.describe == that.outType.describe,
      new Source {
        override type Out = self.Out
        override def id: Iri                     = self.id
        override def label: Label                = Label.unsafe("merged")
        override def name: String                = s"(${self.name}, ${that.name})"
        override def outType: Typeable[self.Out] = self.outType

        override def apply(offset: ProjectionOffset, skipUntilOffset: Boolean): Stream[Task, Envelope[Iri, Elem[Out]]] =
          self
            .apply(offset, skipUntilOffset)
            .merge(that.apply(offset, skipUntilOffset).map { e =>
              e.value match {
                case Elem.SuccessElem(ctx, value) =>
                  self.outType.cast(value) match {
                    case Some(_) => e.asInstanceOf[Envelope[Iri, Elem[Out]]]
                    case None    => e.copy(value = Elem.FailedElem(ctx, SourceOutMatchErr(self, that).reason))
                  }
                case _                            => e.asInstanceOf[Envelope[Iri, Elem[Out]]]
              }
            })
      },
      SourceOutMatchErr(self, that)
    )

  private[stream] def broadcastThrough(
      pipes: NonEmptyChain[Pipe.Aux[_, Unit]]
  ): Either[SourceOutPipeInMatchErr, Source.Aux[Unit]] =
    pipes
      .traverse { pipe =>
        Either.cond(
          self.outType.describe == pipe.inType.describe,
          pipe.asInstanceOf[Pipe.Aux[self.Out, Unit]],
          SourceOutPipeInMatchErr(self, pipe)
        )
      }
      .map { verified =>
        new Source {
          override type Out = Unit
          override def id: Iri                 = self.id
          override def label: Label            = self.label
          override def outType: Typeable[Unit] = Typeable[Unit]

          override def apply(
              offset: ProjectionOffset,
              skipUntilOffset: Boolean
          ): Stream[Task, Envelope[Iri, Elem[Unit]]] =
            self.apply(offset, skipUntilOffset).broadcastThrough(verified.toList.map(_.asFs2): _*)
        }
      }

}

object Source {

  type Aux[O] = Source {
    type Out = O
  }
}
