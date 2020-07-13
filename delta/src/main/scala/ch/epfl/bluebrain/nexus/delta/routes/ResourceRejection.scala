package ch.epfl.bluebrain.nexus.delta.routes

import akka.http.scaladsl.server.Rejection

/**
  * Parent type to all resource rejections.
  */
trait ResourceRejection extends Rejection with Product with Serializable
