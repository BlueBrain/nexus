package ch.epfl.bluebrain.nexus.delta.plugins.graph.analytics.indexing

import ch.epfl.bluebrain.nexus.delta.kernel.utils.ClasspathResourceError
import ch.epfl.bluebrain.nexus.delta.kernel.utils.ClasspathResourceUtils.ioJsonObjectContentOf
import io.circe.JsonObject
import monix.bio.IO

/**
  * A graph analytics view information
  *
  * @param mapping
  *   the elasticsearch mapping to be used in order to create the graph analytics index
  */
final case class GraphAnalyticsView(mapping: JsonObject)

object GraphAnalyticsView {

  implicit private val classLoader: ClassLoader = getClass.getClassLoader

  private val mappings = ioJsonObjectContentOf("elasticsearch/mappings.json")

  val default: IO[ClasspathResourceError, GraphAnalyticsView] = mappings.map(GraphAnalyticsView(_)).memoize
}
