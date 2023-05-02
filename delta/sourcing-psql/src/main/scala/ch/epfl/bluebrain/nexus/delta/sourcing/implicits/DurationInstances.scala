package ch.epfl.bluebrain.nexus.delta.sourcing.implicits

import doobie.{Get, Put}

import scala.concurrent.duration.{FiniteDuration, _}

trait DurationInstances {

  implicit final val durationGet: Get[FiniteDuration] = Get[Long].temap(v => Right(v.milliseconds))
  implicit final val durationPut: Put[FiniteDuration] = Put[Long].contramap(_.toMillis)

}

object DurationInstances extends DurationInstances
