package ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch

import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.client.IndexLabel

package object metrics {

  val eventMetricsIndex: String => IndexLabel = prefix => IndexLabel.unsafe(s"${prefix}_project_metrics")
}
