package ch.epfl.bluebrain.nexus.migration

import io.circe.syntax._
import io.circe.{Encoder, Json}

final case class MigrationRejection private (rejection: Json) extends Throwable(rejection.noSpaces)

object MigrationRejection {

  def apply[R: Encoder](rejection: R): MigrationRejection = MigrationRejection(rejection.asJson)

}
