package ch.epfl.bluebrain.nexus.admin.types

import akka.http.scaladsl.server.Rejection

/**
  * Parent type to all resource rejections for admin.
  */
trait ResourceRejection extends Rejection with Product with Serializable
