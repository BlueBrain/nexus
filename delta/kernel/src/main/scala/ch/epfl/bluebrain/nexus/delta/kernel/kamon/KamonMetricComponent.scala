package ch.epfl.bluebrain.nexus.delta.kernel.kamon

/**
  * Allow to define a component to be later used in Kamon spans
  */
final case class KamonMetricComponent(value: String) extends AnyVal
