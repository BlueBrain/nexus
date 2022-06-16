package ch.epfl.bluebrain.nexus.delta.sourcing.stream

import cats.data.NonEmptyChain
import cats.implicits._
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Label
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.Elem.SuccessElem
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.ProjectionErr.{SourceOutMatchErr, SourceOutPipeInMatchErr}
import fs2.Stream
import monix.bio.Task
import shapeless.Typeable

/**
  * Sources emit Stream elements of type [[Source#Out]] from a predefined
  * [[ch.epfl.bluebrain.nexus.delta.sourcing.offset.Offset]]. Its elements are wrapped in an [[Elem]] that augments the
  * value with contextual information, like for example whether an element was dropped, its offset etc. They are
  * uniquely identified with an id of type [[Iri]].
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
    * @return
    *   an [[fs2.Stream]] of elements of this Source's Out type
    */
  def apply(offset: ProjectionOffset): Stream[Task, Elem[Out]]

  private[stream] def through(pipe: Pipe): Either[SourceOutPipeInMatchErr, Source] =
    Either.cond(
      outType.describe == pipe.inType.describe,
      new Source {
        override type Out = pipe.Out
        override def id: Iri                     = self.id
        override def label: Label                = Label.unsafe("chained")
        override def name: String                = s"${self.name} -> ${pipe.name}"
        override def outType: Typeable[pipe.Out] = pipe.outType

        override def apply(offset: ProjectionOffset): Stream[Task, Elem[pipe.Out]] =
          self
            .apply(offset)
            .map {
              case e @ SuccessElem(_, _, _, _, _, _, value) =>
                pipe.inType.cast(value) match {
                  case Some(value) => e.success(value)
                  case None        => e.failed(SourceOutPipeInMatchErr(self, pipe).reason)
                }
              case e                                        => e.asInstanceOf[Elem[pipe.In]]
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

        override def apply(offset: ProjectionOffset): Stream[Task, Elem[Out]] =
          self
            .apply(offset)
            .merge(that.apply(offset).map {
              case e @ SuccessElem(_, _, _, _, _, _, value) =>
                self.outType.cast(value) match {
                  case Some(_) => e.asInstanceOf[SuccessElem[Out]]
                  case None    => e.failed(SourceOutMatchErr(self, that).reason)
                }
              case e                                        => e.asInstanceOf[Elem[Out]]
            })
      },
      SourceOutMatchErr(self, that)
    )

  private[stream] def broadcastThrough(
      pipes: NonEmptyChain[(Iri, Pipe.Aux[_, Unit])]
  ): Either[SourceOutPipeInMatchErr, Source.Aux[Unit]] =
    pipes
      .traverse { case (id, pipe) =>
        Either.cond(
          self.outType.describe == pipe.inType.describe,
          (id, pipe.asInstanceOf[Pipe.Aux[self.Out, Unit]]),
          SourceOutPipeInMatchErr(self, pipe)
        )
      }
      .map { verified =>
        new Source {
          override type Out = Unit
          override def id: Iri                 = self.id
          override def label: Label            = self.label
          override def outType: Typeable[Unit] = Typeable[Unit]

          override def apply(offset: ProjectionOffset): Stream[Task, Elem[Unit]] =
            self
              .apply(offset)
              .broadcastThrough(verified.toList.map { case (_, pipe) => pipe.asFs2 }: _*)
        }
      }

}

object Source {

  type Aux[O] = Source {
    type Out = O
  }
}
