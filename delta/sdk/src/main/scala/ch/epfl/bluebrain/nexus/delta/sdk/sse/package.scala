package ch.epfl.bluebrain.nexus.delta.sdk

import akka.http.scaladsl.model.sse.ServerSentEvent
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Label
import fs2.Stream
import monix.bio.Task

package object sse {

  type ServerSentEventStream = Stream[Task, ServerSentEvent]

  val resourcesSelector = Label.unsafe("resources")

}
