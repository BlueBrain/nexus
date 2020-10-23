package ch.epfl.bluebrain.nexus.delta.sdk.utils

import java.time.Instant
import java.util.UUID
import java.util.concurrent.TimeUnit

import cats.effect.Clock
import monix.bio.UIO

trait IOUtils {

  /**
    * Creates an Instant deferring its evaluation to the to ''clock'' scheduler
    */
  def instant(implicit clock: Clock[UIO]): UIO[Instant] =
    clock.realTime(TimeUnit.MILLISECONDS).map(Instant.ofEpochMilli)
}

trait UUIDF {

  /**
    * Creates a UUID wrapped in an [[UIO]]
    */
  def apply(): UIO[UUID]
}

object UUIDF {

  /**
    * Creates a [[UUIDF]] that always return the passed ''uuid''.
    *
    * @param uuid the fixed [[UUID]] to return
    */
  final def fixed(uuid: UUID): UUIDF = () => UIO.pure(uuid)

  /**
    * Creates a [[UUIDF]] that returns a new [[UUID]] each time.
    */
  final def random: UUIDF = () => UIO.delay(UUID.randomUUID())
}

object IOUtils extends IOUtils
