package ch.epfl.bluebrain.nexus.delta.sourcing.stream.sources

import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.decoder.JsonLdDecoder
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{EnvelopeStream, Label}
import ch.epfl.bluebrain.nexus.delta.sourcing.offset.Offset
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.Elem.SuccessElem
import ch.epfl.bluebrain.nexus.delta.sourcing.stream._
import fs2.Stream
import monix.bio.Task
import shapeless.Typeable

/**
  * Generic stream fn source implementation.
  * @param id
  *   the source id
  * @param label
  *   the source label
  * @param streamFn
  *   the fn that generates a stream based on a starting offset
  * @tparam A
  *   the element type
  */
class StreamSource[A: Typeable](
    override val id: Iri,
    override val label: Label,
    streamFn: Offset => EnvelopeStream[Iri, A]
) extends Source {

  override type Out = A
  override def outType: Typeable[A] = Typeable[A]

  override def apply(offset: ProjectionOffset): Stream[Task, Elem[A]] =
    streamFn(offset.forSource(this)).map { envelope =>
      SuccessElem(
        ctx = ElemCtx.SourceId(id),
        tpe = envelope.tpe,
        id = envelope.id,
        rev = envelope.rev,
        instant = envelope.instant,
        offset = envelope.offset,
        value = envelope.value
      )
    }
}

/**
  * A generic source implementation from a fn that builds the required stream.
  */
object StreamSource {

  /**
    * Unconfigured generic source.
    *
    * @param label
    *   the source label
    * @param streamFn
    *   the fn that generates a stream based on a starting offset
    * @tparam A
    *   the element type
    */
  def apply[A: Typeable](label: Label, streamFn: Offset => EnvelopeStream[Iri, A]): StreamSourceDef[A, Unit] =
    configured[A, Unit](label, _ => streamFn)

  /**
    * Configured generic source.
    * @param label
    *   the source label
    * @param streamFn
    *   the fn that generates a stream based on a provided config and a starting offset
    * @tparam A
    *   the element type
    * @tparam C
    *   the configuration type
    */
  def configured[A: Typeable, C: Typeable: JsonLdDecoder](
      label: Label,
      streamFn: C => Offset => EnvelopeStream[Iri, A]
  ): StreamSourceDef[A, C] =
    new StreamSourceDef[A, C](label, streamFn)

  class StreamSourceDef[A: Typeable, C: Typeable: JsonLdDecoder](
      override val label: Label,
      streamFn: C => Offset => EnvelopeStream[Iri, A]
  ) extends SourceDef {
    override type SourceType = StreamSource[A]
    override type Config     = C
    override def configType: Typeable[C]                         = Typeable[C]
    override def configDecoder: JsonLdDecoder[C]                 = JsonLdDecoder[C]
    override def withConfig(config: C, id: Iri): StreamSource[A] = new StreamSource[A](id, label, streamFn(config))
  }

}
