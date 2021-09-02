package ch.epfl.bluebrain.nexus.delta.sdk.views.indexing

import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.sdk.views.indexing.ProjectionProgressStreamOps.metricsPrefix
import ch.epfl.bluebrain.nexus.delta.sdk.views.model.ViewIndex
import ch.epfl.bluebrain.nexus.delta.sourcing.projections.ProjectionProgress
import ch.epfl.bluebrain.nexus.delta.sourcing.projections.ProjectionStream.StreamOps
import fs2.Stream
import kamon.Kamon
import kamon.tag.TagSet
import monix.bio.Task
import monix.execution.atomic.AtomicLong

/**
  * Provides extensions methods for fs2.Stream[Message] to implement view statistics
  */
class ProjectionProgressStreamOps[A](val stream: Stream[Task, ProjectionProgress[A]]) extends StreamOps[A] {

  /**
    * Record view metrics in Kamon
    * @param view
    *   the view
    * @param viewType
    *   the view type
    * @param additionalTags
    *   additional Kamon tags
    */
  def viewMetrics(
      view: ViewIndex[_],
      viewType: Iri,
      additionalTags: Map[String, Any] = Map.empty
  ): Stream[Task, ProjectionProgress[A]] = {
    val defaultTags                      = Map(
      "project"      -> view.projectRef.toString,
      "organization" -> view.projectRef.organization.toString,
      "viewId"       -> view.id.toString,
      "type"         -> viewType.toString
    )
    val tags                             = defaultTags ++ additionalTags
    val processedEventsGauge             = Kamon
      .gauge(s"${metricsPrefix}_gauge_processed_events")
      .withTags(TagSet.from(tags))
    val processedEventsCounter           = Kamon
      .counter(s"${metricsPrefix}_counter_processed_events")
      .withTags(TagSet.from(tags))
    val processedEventsCount: AtomicLong = AtomicLong(0)
    val failedEventsGauge                = Kamon
      .gauge(s"${metricsPrefix}_gauge_failed_events")
      .withTags(TagSet.from(tags))
    val failedEventsCounter              = Kamon
      .counter(s"${metricsPrefix}_counter_failed_events")
      .withTags(TagSet.from(tags))
    val failedEventsCount: AtomicLong    = AtomicLong(0L)

    stream.map { p =>
      val previousProcessedCount = processedEventsCount.get()
      processedEventsGauge.update(p.processed.toDouble)
      processedEventsCounter.increment(p.processed - previousProcessedCount)
      processedEventsCount.set(p.processed)

      val previousFailedCount = failedEventsCount.get()
      failedEventsGauge.update(p.failed.toDouble)
      failedEventsCounter.increment(p.failed - previousFailedCount)
      failedEventsCount.set(p.failed)
      p
    }
  }
}

object ProjectionProgressStreamOps {
  val metricsPrefix: String = "delta_indexer"
}
