package ch.epfl.bluebrain.nexus.delta.sdk.eventlog

import io.circe.syntax._
import io.circe.{Encoder, Json}

final case class EventFetchRejection private (rejection: Json) extends Throwable(rejection.noSpaces)

object EventFetchRejection {

  def apply[R: Encoder](rejection: R): EventFetchRejection = EventFetchRejection(rejection.asJson)

}
