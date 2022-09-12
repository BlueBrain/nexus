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

object StreamSource {

  def apply[A: Typeable](label: Label, streamFn: Offset => EnvelopeStream[Iri, A]): StreamSourceDef[A] =
    new StreamSourceDef[A](label, streamFn)

  class StreamSourceDef[A: Typeable](
      override val label: Label,
      streamFn: Offset => EnvelopeStream[Iri, A]
  ) extends SourceDef {
    override type SourceType = StreamSource[A]
    override type Config     = Unit
    override def configType: Typeable[Unit]                         = Typeable[Unit]
    override def configDecoder: JsonLdDecoder[Unit]                 = JsonLdDecoder[Unit]
    override def withConfig(config: Unit, id: Iri): StreamSource[A] = new StreamSource[A](id, label, streamFn)
  }

}
