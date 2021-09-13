package ch.epfl.bluebrain.nexus.delta.kernel.utils

import cats.effect.Clock
import cats.effect.concurrent.Ref
import monix.bio.{Task, UIO}

import java.time.Instant
import java.util.UUID
import java.util.concurrent.TimeUnit

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

trait StatefulUUIDF extends UUIDF {

  /**
    * Modifies the current UUIDF to yield a different specific value during its next invocation.
    * @param uuid
    *   the new UUID to present
    */
  def fixed(uuid: UUID): UIO[Unit]
}

object UUIDF {

  /**
    * Creates a [[UUIDF]] that always return the passed ''uuid''.
    *
    * @param uuid
    *   the fixed [[UUID]] to return
    */
  final def fixed(uuid: UUID): UUIDF = () => UIO.pure(uuid)

  /**
    * Creates a [[UUIDF]] that returns a new [[UUID]] each time.
    */
  final def random: UUIDF = () => UIO.delay(UUID.randomUUID())

  /**
    * Creates a [[UUIDF]] that returns a fixed [[UUID]] each time with the ability to modify the fixed value.
    *
    * @param initial
    *   the initial fixed [[UUID]] to return
    */
  final def stateful(initial: UUID): UIO[StatefulUUIDF] =
    (for {
      ref  <- Ref.of[Task, UUID](initial)
      uuidF = new StatefulUUIDF {
                private val uuidRef                       = ref
                override def fixed(uuid: UUID): UIO[Unit] = uuidRef.set(uuid).hideErrors
                override def apply(): UIO[UUID]           = uuidRef.get.hideErrors
              }
    } yield uuidF).hideErrors
}

object IOUtils extends IOUtils
