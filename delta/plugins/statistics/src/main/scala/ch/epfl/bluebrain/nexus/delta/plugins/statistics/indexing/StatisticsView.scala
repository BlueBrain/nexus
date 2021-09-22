package ch.epfl.bluebrain.nexus.delta.plugins.statistics.indexing

import ch.epfl.bluebrain.nexus.delta.kernel.utils.ClasspathResourceError
import ch.epfl.bluebrain.nexus.delta.kernel.utils.ClasspathResourceUtils.ioJsonObjectContentOf
import io.circe.JsonObject
import monix.bio.IO

/**
  * A statistics view information
  *
  * @param mapping
  *   the elasticsearch mapping to be used in order to create the statistics index
  */
final case class StatisticsView(mapping: JsonObject)

object StatisticsView {

  implicit private val classLoader: ClassLoader = getClass.getClassLoader

  private val mappings = ioJsonObjectContentOf("elasticsearch/mappings.json").memoize

  val default: IO[ClasspathResourceError, StatisticsView] = mappings.map(StatisticsView(_))
}
