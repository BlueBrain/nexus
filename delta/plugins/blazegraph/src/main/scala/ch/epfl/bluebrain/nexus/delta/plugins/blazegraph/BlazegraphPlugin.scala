package ch.epfl.bluebrain.nexus.delta.plugins.blazegraph

import akka.http.scaladsl.server.Route
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.routes.BlazegraphViewsRoutes
import ch.epfl.bluebrain.nexus.delta.sdk.plugin.Plugin
import monix.bio.Task

class BlazegraphPlugin(routes: BlazegraphViewsRoutes) extends Plugin {

  override def route: Option[Route] = Some(routes.routes)

  override def stop(): Task[Unit] = Task.unit
}
