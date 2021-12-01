package ch.epfl.bluebrain.nexus.delta.plugins.graph.analytics.indexing

import ch.epfl.bluebrain.nexus.delta.kernel.utils.ClasspathResourceUtils.ioJsonObjectContentOf
import ch.epfl.bluebrain.nexus.delta.sdk.syntax._
import com.typesafe.scalalogging.Logger
import io.circe.JsonObject
import monix.bio.UIO

/**
  * A graph analytics view information
  *
  * @param mapping
  *   the elasticsearch mapping to be used in order to create the graph analytics index
  */
final case class GraphAnalyticsView(mapping: JsonObject)

object GraphAnalyticsView {

  implicit private val classLoader: ClassLoader = getClass.getClassLoader

  implicit private val logger: Logger = Logger[GraphAnalyticsView]

  private val mappings = ioJsonObjectContentOf("elasticsearch/mappings.json")

  val default: UIO[GraphAnalyticsView] = mappings
    .map(GraphAnalyticsView(_))
    .logAndDiscardErrors("loading graph analytics mapping")
    .memoize
}
