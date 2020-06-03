package ch.epfl.bluebrain.nexus.iam.types

import akka.http.scaladsl.server.Rejection

/**
  * Parent type to all resource rejections for iam.
  */
trait ResourceRejection extends Rejection with Product with Serializable
