package ch.epfl.bluebrain.nexus.delta.sourcing.stream

import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.BNode
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.ExpandedJsonLd
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.Elem.SuccessElem
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.FailEveryN.FailEveryNConfig
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.TimesN.TimesNConfig
import fs2.concurrent.Queue
import io.circe.JsonObject
import monix.bio.Task

import scala.concurrent.duration.FiniteDuration

final case class ProjectionTestContext[A](registry: ReferenceRegistry, queue: Queue[Task, SuccessElem[A]]) {

  val emptyConfig: ExpandedJsonLd = ExpandedJsonLd.unsafe(BNode.random, JsonObject.empty)

  val evensPipe: (PipeRef, ExpandedJsonLd)              = (Evens.reference, emptyConfig)
  val oddsPipe: (PipeRef, ExpandedJsonLd)               = (Odds.reference, emptyConfig)
  val intToStringPipe: (PipeRef, ExpandedJsonLd)        = (IntToString.reference, emptyConfig)
  val logPipe: (PipeRef, ExpandedJsonLd)                = (Log.reference, emptyConfig)
  def timesNPipe(times: Int): (PipeRef, ExpandedJsonLd) = (TimesN.reference, TimesNConfig(times).toJsonLd)
  def failNPipe(every: Int): (PipeRef, ExpandedJsonLd)  = (FailEveryN.reference, FailEveryNConfig(every).toJsonLd)

  def waitForNElements(count: Int, duration: FiniteDuration): Task[List[SuccessElem[A]]] =
    queue.dequeue.take(count.toLong).compile.toList.timeout(duration).map(_.getOrElse(Nil))

  def currentElements: Task[List[SuccessElem[A]]] =
    queue.tryDequeueChunk1(Int.MaxValue).map {
      case Some(value) => value.toList
      case None        => Nil
    }
}
