package ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch

import akka.http.scaladsl.server.Route
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.routes.ElasticSearchViewsRoutes
import ch.epfl.bluebrain.nexus.delta.sdk.plugin.Plugin
import monix.bio.Task

class ElasticSearchPlugin(routes: ElasticSearchViewsRoutes) extends Plugin {

  override def route: Option[Route] = Some(routes.routes)

  override def stop(): Task[Unit] = Task.unit
}
