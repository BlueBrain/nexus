package ch.epfl.bluebrain.nexus.delta.sourcing.projections.tracing

/**
  * Config to push stream progress metrics in Kamon
  * @param prefix
  *   the prefix for the different metrics
  * @param tags
  *   the tags
  */
final case class ProgressTracingConfig(prefix: String, tags: Map[String, Any])
