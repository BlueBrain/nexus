package ch.epfl.bluebrain.nexus.delta.kernel.kamon

/**
  * Config to push metrics in Kamon
  *
  * @param prefix
  *   the prefix for the different metrics
  * @param tags
  *   the tags
  */
final case class KamonMetricsConfig(prefix: String, tags: Map[String, Any])
