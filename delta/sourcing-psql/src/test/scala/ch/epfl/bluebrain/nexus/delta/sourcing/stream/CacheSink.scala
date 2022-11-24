package ch.epfl.bluebrain.nexus.delta.sourcing.stream

import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.Elem.{DroppedElem, FailedElem, SuccessElem}
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.Operation.Sink
import fs2.Chunk
import monix.bio.Task
import shapeless.Typeable

import collection.mutable.{Set => MutableSet}
import scala.collection.concurrent.TrieMap
import scala.collection.mutable
import scala.concurrent.duration._

final class CacheSink[A: Typeable] extends Sink {

  val successes: mutable.Map[Iri, A] = TrieMap.empty[Iri, A]
  val dropped: MutableSet[Iri]       = MutableSet.empty[Iri]
  val failed: MutableSet[Iri]        = MutableSet.empty[Iri]

  override type In = A

  override def inType: Typeable[A] = Typeable[A]

  override def apply(elements: Chunk[Elem[A]]): Task[Chunk[Elem[Unit]]] = Task.delay {
    elements.map {
      case s: SuccessElem[A] =>
        successes.put(s.id, s.value)
        s.void
      case d: DroppedElem    =>
        dropped.add(d.id)
        d
      case f: FailedElem     =>
        failed.add(f.id)
        f
    }
  }

  override def chunkSize: Int = 1

  override def maxWindow: FiniteDuration = 10.millis
}
