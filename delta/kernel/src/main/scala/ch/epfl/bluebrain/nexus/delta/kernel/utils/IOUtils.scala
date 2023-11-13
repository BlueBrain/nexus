package ch.epfl.bluebrain.nexus.delta.kernel.utils

import cats.effect.concurrent.Ref
import cats.effect.{Clock, IO}

import java.time.Instant
import java.util.UUID
import java.util.concurrent.TimeUnit
import scala.concurrent.duration.{DurationLong, FiniteDuration, MILLISECONDS}

trait IOUtils {

  implicit class IoOps[A](val io: IO[A]) {
    def timed(implicit clock: Clock[IO]): IO[(FiniteDuration, A)] = {
      for {
        start  <- clock.monotonic(MILLISECONDS)
        result <- io
        finish <- clock.monotonic(MILLISECONDS)
      } yield ((finish - start).milliseconds, result)
    }
  }
}

object IOUtils extends IOUtils

trait IOInstant {
  def now(implicit clock: Clock[IO]): IO[Instant] =
    clock.realTime(TimeUnit.MILLISECONDS).map(Instant.ofEpochMilli)
}

object IOInstant extends IOInstant

trait UUIDF {

  /**
    * Creates a UUID wrapped in an [[IO]]
    */
  def apply(): IO[UUID]
}

trait StatefulUUIDF extends UUIDF {

  /**
    * Modifies the current UUIDF to yield a different specific value during its next invocation.
    * @param uuid
    *   the new UUID to present
    */
  def fixed(uuid: UUID): IO[Unit]
}

object UUIDF {

  /**
    * Creates a [[UUIDF]] that always return the passed ''uuid''.
    *
    * @param uuid
    *   the fixed [[UUID]] to return
    */
  final def fixed(uuid: UUID): UUIDF = () => IO.pure(uuid)

  /**
    * Creates a [[UUIDF]] that returns a new [[UUID]] each time.
    */
  final def random: UUIDF = () => IO.delay(UUID.randomUUID())

  /**
    * Creates a [[UUIDF]] that returns a fixed [[UUID]] each time with the ability to modify the fixed value.
    *
    * @param initial
    *   the initial fixed [[UUID]] to return
    */
  final def stateful(initial: UUID): IO[StatefulUUIDF] =
    for {
      ref  <- Ref.of[IO, UUID](initial)
      uuidF = new StatefulUUIDF {
                private val uuidRef                      = ref
                override def fixed(uuid: UUID): IO[Unit] = uuidRef.set(uuid)
                override def apply(): IO[UUID]           = uuidRef.get
              }
    } yield uuidF

  /**
    * Creates a [[UUIDF]] sourcing [[UUID]] values from a mutable reference.
    *
    * @param ref
    *   the pre-initialised mutable reference used to store the [[UUID]]
    */
  final def fromRef(ref: Ref[IO, UUID]): UUIDF = () => ref.get
}
