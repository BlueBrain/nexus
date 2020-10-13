package ch.epfl.bluebrain.nexus.delta.wiring

import akka.persistence.query.{EventEnvelope, Offset}
import ch.epfl.bluebrain.nexus.delta.sdk.model.{Envelope, Event}
import izumi.distage.model.definition.ModuleDef
import monix.bio.UIO

import scala.reflect.ClassTag

abstract class AbstractModule extends ModuleDef {

  /**
    * Attempts to convert a generic event envelope to a type one.
    * @param envelope the generic event envelope
    */
  def toEnvelope[E <: Event](envelope: EventEnvelope)(implicit Event: ClassTag[E]): UIO[Option[Envelope[E]]] =
    envelope match {
      case ee @ EventEnvelope(offset: Offset, persistenceId, sequenceNr, Event(value)) =>
        UIO.pure(Some(Envelope(value, value.getClass.getSimpleName, offset, persistenceId, sequenceNr, ee.timestamp)))
      case _                                                                           =>
        UIO(
          println(
            s"Failed to match envelope value '${envelope.event}' to class '${Event.runtimeClass.getCanonicalName}'"
          )
        ) >> UIO.pure(None)
    }

}
