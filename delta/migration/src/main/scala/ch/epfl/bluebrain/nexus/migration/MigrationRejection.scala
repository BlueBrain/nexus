package ch.epfl.bluebrain.nexus.migration

import io.circe.{Encoder, Json}
import io.circe.syntax._

final case class MigrationRejection private (rejection: Json) extends Throwable(rejection.noSpaces)

object MigrationRejection {

  def apply[R: Encoder](rejection: R): MigrationRejection = MigrationRejection(rejection.asJson)

}
