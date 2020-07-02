package ch.epfl.bluebrain.nexus.kg

import akka.NotUsed
import akka.stream.scaladsl.Flow
import cats.implicits._
import ch.epfl.bluebrain.nexus.admin.projects.ProjectResource
import ch.epfl.bluebrain.nexus.kg.indexing.View.CompositeView.{Projection, Source => CompositeViewSource}
import ch.epfl.bluebrain.nexus.kg.indexing.View.{CompositeView, SingleView}
import ch.epfl.bluebrain.nexus.kg.resources.ProjectIdentifier.ProjectLabel
import ch.epfl.bluebrain.nexus.kg.resources.ResourceV
import ch.epfl.bluebrain.nexus.service.config.ServiceConfig
import ch.epfl.bluebrain.nexus.sourcing.projections.ProjectionProgress
import ch.epfl.bluebrain.nexus.sourcing.projections.ProjectionProgress.OffsetsProgress
import kamon.Kamon
import kamon.metric.{Counter, Gauge}
import kamon.tag.TagSet
import monix.execution.atomic.AtomicLong

package object indexing {
  implicit class ListResourcesSyntax[A](private val events: List[(ResourceV, A)]) extends AnyVal {

    /**
      * Remove events with duplicated ''id''. In case of duplication found, the last element is kept and the previous removed.
      *
      * @return a new list without duplicated ids
      */
    def removeDupIds: List[A] =
      events.groupBy { case (res, _) => res.id }.values.flatMap(_.lastOption.map { case (_, elem) => elem }).toList

  }

  // $COVERAGE-OFF$
  /**
    * Create a flow that will add Kamon metrics for a project
    * @param project  the project to create the metrics for
    * @return         the flow which will create the Kamon metrics
    */
  def kamonProjectMetricsFlow(project: ProjectLabel): Flow[ProjectionProgress, ProjectionProgress, NotUsed] = {
    val g     = Kamon
      .gauge("kg_indexer_gauge")
      .withTag("type", "eventCount")
      .withTag("project", project.value.show)
      .withTag("organization", project.organization)
    val c     = Kamon
      .counter("kg_indexer_counter")
      .withTag("type", "eventCount")
      .withTag("project", project.value.show)
      .withTag("organization", project.organization)
    val count = AtomicLong(0L)
    Flow[ProjectionProgress]
      .map { p =>
        val previousCount = count.get()
        g.update(p.minProgress.processed.toDouble)
        c.increment(p.minProgress.processed - previousCount)
        count.set(p.minProgress.processed)
        p
      }
  }

  /**
    * Create a flow that will add Kamon metrics for the progress of the view indexing.
    *
    * @param view     the view which is indexed
    * @param project  the project to which the view belongs
    * @return         the flow which will create the Kamon metrics
    */
  def kamonViewMetricsFlow(
      view: View,
      project: ProjectResource
  )(implicit config: ServiceConfig): Flow[ProjectionProgress, ProjectionProgress, NotUsed] =
    view match {
      case cv: CompositeView => compositeViewKamonFlow(cv, project)
      case sv: SingleView    => singleViewKamonFlow(sv, project)
      case _                 => Flow[ProjectionProgress]
    }

  private def singleViewKamonFlow(
      view: SingleView,
      project: ProjectResource
  )(implicit config: ServiceConfig): Flow[ProjectionProgress, ProjectionProgress, NotUsed] = {

    val processedEventsGauge             = Kamon
      .gauge("kg_indexer_gauge_processed_events")
      .withTag("type", view.getClass.getSimpleName)
      .withTag("project", project.value.show)
      .withTag("organization", project.value.organizationLabel)
      .withTag("viewId", view.id.show)
    val processedEventsCounter           = Kamon
      .counter("kg_indexer_counter_processed_events")
      .withTag("type", view.getClass.getSimpleName)
      .withTag("project", project.value.show)
      .withTag("organization", project.value.organizationLabel)
      .withTag("viewId", view.id.show)
    val processedEventsCount: AtomicLong = AtomicLong(0)

    val failedEventsGauge             = Kamon
      .gauge("kg_indexer_gauge_failed_events")
      .withTag("type", view.getClass.getSimpleName)
      .withTag("project", project.value.show)
      .withTag("organization", project.value.organizationLabel)
      .withTag("viewId", view.id.show)
    val failedEventsCounter           = Kamon
      .counter("kg_indexer_counter_failed_events")
      .withTag("type", view.getClass.getSimpleName)
      .withTag("project", project.value.show)
      .withTag("organization", project.value.organizationLabel)
      .withTag("viewId", view.id.show)
    val failedEventsCount: AtomicLong = AtomicLong(0L)

    Flow[ProjectionProgress]
      .map { p =>
        val singleProgress = p.progress(view.progressId)

        val previousProcessedCount = processedEventsCount.get()
        processedEventsGauge.update(singleProgress.processed.toDouble)
        processedEventsCounter.increment(singleProgress.processed - previousProcessedCount)
        processedEventsCount.set(singleProgress.processed)

        val previousFailedCount = failedEventsCount.get()
        failedEventsGauge.update(singleProgress.failed.toDouble)
        failedEventsCounter.increment(singleProgress.failed - previousFailedCount)
        failedEventsCount.set(singleProgress.failed)
        p
      }
  }

  private def compositeViewKamonFlow(
      view: CompositeView,
      project: ProjectResource
  ): Flow[ProjectionProgress, ProjectionProgress, NotUsed] = {

    def sourceTags(view: CompositeView, project: ProjectResource, source: CompositeViewSource): TagSet =
      TagSet
        .builder()
        .add("type", s"${view.getClass.getSimpleName}Source")
        .add("project", project.value.show)
        .add("organization", project.value.organizationLabel)
        .add("viewId", view.id.show)
        .add("sourceId", source.id.show)
        .build()

    def projectionTags(
        view: CompositeView,
        project: ProjectResource,
        source: CompositeViewSource,
        projection: Projection
    ): TagSet =
      TagSet
        .builder()
        .add("type", s"${view.getClass.getSimpleName}Projection")
        .add("project", project.value.show)
        .add("organization", project.value.organizationLabel)
        .add("viewId", view.id.show)
        .add("sourceId", source.id.show)
        .add("projectionId", projection.view.id.show)
        .build()

    def gaugesFor(metricName: String, view: CompositeView, project: ProjectResource): Map[String, Gauge] =
      (view.sources.map { source =>
        source.id.asString -> Kamon
          .gauge(metricName)
          .withTags(sourceTags(view, project, source))
      } ++ (for {
        projection <- view.projections
        source     <- view.sources
      } yield view.progressId(source.id, projection.view.id) ->
        Kamon
          .gauge(metricName)
          .withTags(projectionTags(view, project, source, projection)))).toMap

    def countersFor(metricName: String, view: CompositeView, project: ProjectResource): Map[String, Counter] =
      (view.sources.map { source =>
        source.id.asString -> Kamon
          .counter(metricName)
          .withTags(sourceTags(view, project, source))
      } ++ (for {
        projection <- view.projections
        source     <- view.sources
      } yield view.progressId(source.id, projection.view.id) ->
        Kamon
          .counter(metricName)
          .withTags(projectionTags(view, project, source, projection)))).toMap

    def countsFor(view: CompositeView): Map[String, AtomicLong] =
      (view.sources.map { source =>
        source.id.asString -> AtomicLong(0L)

      } ++ (for {
        projection <- view.projections
        source     <- view.sources
      } yield view.progressId(source.id, projection.view.id) -> AtomicLong(0L))).toMap

    val processedEventsGauges: Map[String, Gauge]               =
      gaugesFor("kg_indexer_gauge_processed_events", view, project)

    val processedEventsCounters: Map[String, Counter] =
      countersFor("kg_indexer_counter_processed_events", view, project)

    val processedEventsCounts: Map[String, AtomicLong] = countsFor(view)

    val failedEventsGauges: Map[String, Gauge] =
      gaugesFor("kg_indexer_gauge_failed_events", view, project)

    val failedEventsCounters: Map[String, Counter] =
      countersFor("kg_indexer_counter_failed_events", view, project)

    val failedEventsCounts: Map[String, AtomicLong] = countsFor(view)

    Flow[ProjectionProgress]
      .map {
        case op: OffsetsProgress =>
          op.progress.foreach {
            case (progressId, progress) =>
              val previousProcessedCount = processedEventsCounts.getOrElse(progressId, AtomicLong(0L))
              processedEventsGauges.get(progressId).foreach(_.update(progress.processed.toDouble))
              processedEventsCounters
                .get(progressId)
                .foreach(_.increment(progress.processed - previousProcessedCount.get()))
              previousProcessedCount.set(progress.processed)

              val previousFailedCount = failedEventsCounts.getOrElse(progressId, AtomicLong(0L))
              failedEventsGauges.get(progressId).foreach(_.update(progress.failed.toDouble))
              failedEventsCounters.get(progressId).foreach(_.increment(progress.failed - previousFailedCount.get()))
              previousFailedCount.set(progress.failed)
          }
          op
        case other               => other
      }
  }
  // $COVERAGE-ON$
}
