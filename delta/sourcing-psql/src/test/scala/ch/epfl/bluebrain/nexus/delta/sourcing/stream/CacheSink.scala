package ch.epfl.bluebrain.nexus.delta.sourcing.stream

import cats.effect.IO
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.syntax.iriStringContextSyntax
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.Elem.{DroppedElem, FailedElem, SuccessElem}
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.Operation.Sink
import fs2.Chunk
import shapeless.Typeable

import scala.collection.concurrent.TrieMap
import scala.collection.mutable
import scala.collection.mutable.{Set => MutableSet}
import scala.concurrent.duration._

final class CacheSink[A: Typeable] private (documentId: Elem[A] => Iri) extends Sink {

  val successes: mutable.Map[Iri, A] = TrieMap.empty[Iri, A]
  val dropped: MutableSet[Iri]       = MutableSet.empty[Iri]
  val failed: MutableSet[Iri]        = MutableSet.empty[Iri]

  override type In = A

  override def inType: Typeable[A] = Typeable[A]

  override def apply(elements: Chunk[Elem[A]]): IO[Chunk[Elem[Unit]]] = IO.delay {
    elements.map {
      case s: SuccessElem[A] =>
        successes.put(documentId(s), s.value)
        s.void
      case d: DroppedElem    =>
        dropped.add(documentId(d))
        d
      case f: FailedElem     =>
        failed.add(documentId(f))
        f
    }
  }

  override def chunkSize: Int = 1

  override def maxWindow: FiniteDuration = 10.millis
}

object CacheSink {
  private val eventDocumentId: Elem[_] => Iri = elem =>
    elem.project match {
      case Some(project) => iri"$project/${elem.id}:${elem.rev}"
      case None          => iri"${elem.id}:${elem.rev}"
    }

  /** CacheSink for events */
  def events[A: Typeable]: CacheSink[A] = new CacheSink(eventDocumentId)

  /** CacheSink for states */
  def states[A: Typeable]: CacheSink[A] = new CacheSink(_.id)
}
