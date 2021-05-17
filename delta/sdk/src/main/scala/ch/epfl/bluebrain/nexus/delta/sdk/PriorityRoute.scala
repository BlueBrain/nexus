package ch.epfl.bluebrain.nexus.delta.sdk

import akka.http.scaladsl.server.Route

/**
  * A [[Route]] that has a ''priority''.
  *
  * @param priority the priority of this route
  * @param route    the route
  */
final case class PriorityRoute(priority: Int, route: Route)

object PriorityRoute {
  implicit val priorityRouteOrdering: Ordering[PriorityRoute] = Ordering.by(_.priority)
}
