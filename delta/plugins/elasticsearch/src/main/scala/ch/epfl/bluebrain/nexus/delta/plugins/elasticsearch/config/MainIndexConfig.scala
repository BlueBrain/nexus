package ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.config

import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.client.IndexLabel
import pureconfig.ConfigReader
import pureconfig.generic.semiauto.deriveReader

/**
  * Configuration for listing resources and the underlying Elasticsearch index
  *
  * @param prefix
  *   the prefix of the index
  * @param name
  *   the name of the index
  * @param shards
  *   the number of shards of this index
  * @param bucketSize
  *   the maximum number of terms returned by a term aggregation for listing aggregations
  */
final case class MainIndexConfig(prefix: String, name: String, shards: Int, bucketSize: Int) {
  val index: IndexLabel = IndexLabel.unsafe(s"${prefix}_$name")
}

object MainIndexConfig {
  implicit final val defaultIndexConfigReader: ConfigReader[MainIndexConfig] = deriveReader[MainIndexConfig]
}
