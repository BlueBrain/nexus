package ch.epfl.bluebrain.nexus.service.routes

import akka.http.scaladsl.server.Rejection

trait ServiceRejection extends Rejection with Product with Serializable
