package ch.epfl.bluebrain.nexus.delta.sdk

import java.util.concurrent.atomic.AtomicLong

import akka.persistence.query.Sequence
import ch.epfl.bluebrain.nexus.delta.sdk.model.{Envelope, Event}
import ch.epfl.bluebrain.nexus.delta.sdk.utils.ClassUtils

package object testkit {

  def makeEnvelope[A <: Event](event: A, persistenceId: String, sequenceCount: AtomicLong): Envelope[A] =
    Envelope(
      event,
      ClassUtils.simpleName(event),
      Sequence(sequenceCount.incrementAndGet()),
      persistenceId,
      event.rev,
      event.instant.toEpochMilli
    )
}
