package ch.epfl.bluebrain.nexus.delta.sdk.model

import cats.effect.IO

object Fetch {

  type Fetch[R]  = IO[Option[R]]
  type FetchF[R] = Fetch[ResourceF[R]]

}
