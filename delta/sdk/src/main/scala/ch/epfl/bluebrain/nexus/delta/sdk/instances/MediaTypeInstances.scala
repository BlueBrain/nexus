package ch.epfl.bluebrain.nexus.delta.sdk.instances

import akka.http.scaladsl.model.MediaType
import cats.Eq

trait MediaTypeInstances {
  implicit def mediaTypeEq[A <: MediaType]: Eq[A] = Eq.fromUniversalEquals
}

object MediaTypeInstances extends MediaTypeInstances
