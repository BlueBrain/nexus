package ch.epfl.bluebrain.nexus.delta.sdk

import akka.http.scaladsl.model.sse.ServerSentEvent
import cats.effect.IO
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Label
import fs2.Stream

package object sse {

  type ServerSentEventStream = Stream[IO, ServerSentEvent]

  val resourcesSelector = Label.unsafe("resources")

}
