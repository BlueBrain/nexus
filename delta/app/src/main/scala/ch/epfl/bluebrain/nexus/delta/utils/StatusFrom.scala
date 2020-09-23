package ch.epfl.bluebrain.nexus.delta.utils

import akka.http.scaladsl.model.{StatusCode, StatusCodes}
import ch.epfl.bluebrain.nexus.delta.sdk.model.permissions.PermissionsRejection
import ch.epfl.bluebrain.nexus.delta.sdk.model.permissions.PermissionsRejection.IncorrectRev

trait StatusFrom[A] {

  def statusOf(value: A): StatusCode

}

object StatusFrom {

  def apply[A](fn: A => StatusCode): StatusFrom[A] = (value: A) => fn(value)

  implicit val statusFromPermissions: StatusFrom[PermissionsRejection] = StatusFrom {
    case _: IncorrectRev => StatusCodes.Conflict
    case _               => StatusCodes.BadRequest
  }

}
