package ch.epfl.bluebrain.nexus.delta.plugins.graph.analytics.indexing

import cats.effect.IO
import ch.epfl.bluebrain.nexus.delta.kernel.utils.CatsEffectsClasspathResourceUtils.ioJsonObjectContentOf
import ch.epfl.bluebrain.nexus.delta.sdk.syntax._
import com.typesafe.scalalogging.Logger
import io.circe.JsonObject

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

  // TODODODODODO Sort out memoization
  val default: IO[GraphAnalyticsView] = mappings
    .map(GraphAnalyticsView(_))
    .logAndDiscardErrors("loading graph analytics mapping")
}
