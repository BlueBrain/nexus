package ch.epfl.bluebrain.nexus.kg

import akka.NotUsed
import akka.actor.ActorSystem
import akka.persistence.query.scaladsl.EventsByTagQuery
import akka.persistence.query.{NoOffset, Offset, PersistenceQuery}
import akka.stream.scaladsl.{Flow, Source}
import cats.effect.Effect
import cats.implicits._
import ch.epfl.bluebrain.nexus.admin.client.AdminClient
import ch.epfl.bluebrain.nexus.admin.client.types.Project
import ch.epfl.bluebrain.nexus.iam.client.types.AuthToken
import ch.epfl.bluebrain.nexus.iam.client.types.Identity.Subject
import ch.epfl.bluebrain.nexus.kg.cache.ProjectCache
import ch.epfl.bluebrain.nexus.kg.config.AppConfig
import ch.epfl.bluebrain.nexus.kg.config.AppConfig.PersistenceConfig
import ch.epfl.bluebrain.nexus.kg.indexing.View.CompositeView.{Projection, Source => CompositeViewSource}
import ch.epfl.bluebrain.nexus.kg.indexing.View.{CompositeView, SingleView}
import ch.epfl.bluebrain.nexus.kg.resources.ProjectIdentifier.{ProjectLabel, ProjectRef}
import ch.epfl.bluebrain.nexus.kg.resources.{OrganizationRef, ProjectInitializer, ResourceV}
import ch.epfl.bluebrain.nexus.sourcing.projections.ProgressFlow.PairMsg
import ch.epfl.bluebrain.nexus.sourcing.projections.ProjectionProgress.OffsetsProgress
import ch.epfl.bluebrain.nexus.sourcing.projections.{Message, ProjectionProgress}
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
      .withTag("project", project.show)
      .withTag("organization", project.organization)
    val c     = Kamon
      .counter("kg_indexer_counter")
      .withTag("type", "eventCount")
      .withTag("project", project.show)
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
      project: Project
  )(implicit config: AppConfig): Flow[ProjectionProgress, ProjectionProgress, NotUsed] =
    view match {
      case cv: CompositeView => compositeViewKamonFlow(cv, project)
      case sv: SingleView    => singleViewKamonFlow(sv, project)
      case _                 => Flow[ProjectionProgress]
    }

  private def singleViewKamonFlow(
      view: SingleView,
      project: Project
  )(implicit config: AppConfig): Flow[ProjectionProgress, ProjectionProgress, NotUsed] = {

    val processedEventsGauge             = Kamon
      .gauge("kg_indexer_gauge_processed_events")
      .withTag("type", view.getClass.getSimpleName)
      .withTag("project", project.show)
      .withTag("organization", project.organizationLabel)
      .withTag("viewId", view.id.show)
    val processedEventsCounter           = Kamon
      .counter("kg_indexer_counter_processed_events")
      .withTag("type", view.getClass.getSimpleName)
      .withTag("project", project.show)
      .withTag("organization", project.organizationLabel)
      .withTag("viewId", view.id.show)
    val processedEventsCount: AtomicLong = AtomicLong(0)

    val failedEventsGauge             = Kamon
      .gauge("kg_indexer_gauge_failed_events")
      .withTag("type", view.getClass.getSimpleName)
      .withTag("project", project.show)
      .withTag("organization", project.organizationLabel)
      .withTag("viewId", view.id.show)
    val failedEventsCounter           = Kamon
      .counter("kg_indexer_counter_failed_events")
      .withTag("type", view.getClass.getSimpleName)
      .withTag("project", project.show)
      .withTag("organization", project.organizationLabel)
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
      project: Project
  ): Flow[ProjectionProgress, ProjectionProgress, NotUsed] = {

    def sourceTags(view: CompositeView, project: Project, source: CompositeViewSource): TagSet =
      TagSet
        .builder()
        .add("type", s"${view.getClass.getSimpleName}Source")
        .add("project", project.show)
        .add("organization", project.organizationLabel)
        .add("viewId", view.id.show)
        .add("sourceId", source.id.show)
        .build()

    def projectionTags(
        view: CompositeView,
        project: Project,
        source: CompositeViewSource,
        projection: Projection
    ): TagSet =
      TagSet
        .builder()
        .add("type", s"${view.getClass.getSimpleName}Projection")
        .add("project", project.show)
        .add("organization", project.organizationLabel)
        .add("viewId", view.id.show)
        .add("sourceId", source.id.show)
        .add("projectionId", projection.view.id.show)
        .build()

    def gaugesFor(metricName: String, view: CompositeView, project: Project): Map[String, Gauge] =
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

    def countersFor(metricName: String, view: CompositeView, project: Project): Map[String, Counter] =
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

  def cassandraSource(tag: String, projectionId: String, offset: Offset = NoOffset)(implicit
      config: PersistenceConfig,
      as: ActorSystem
  ): Source[PairMsg[Any], NotUsed] =
    PersistenceQuery(as)
      .readJournalFor[EventsByTagQuery](config.queryJournalPlugin)
      .eventsByTag(tag, offset)
      .map[PairMsg[Any]](e => Right(Message(e, projectionId)))

  /**
    * Attempts to fetch the project from the cache and retries until it is found.
    *
    * @param organizationRef the organization unique reference
    * @param projectRef      the project unique reference
    * @param subject         the subject of the event
    * @tparam F the effect type
    * @return the project wrapped on the effect type
    */
  def fetchProject[F[_]](
      organizationRef: OrganizationRef,
      projectRef: ProjectRef,
      subject: Subject
  )(implicit
      projectCache: ProjectCache[F],
      adminClient: AdminClient[F],
      cred: Option[AuthToken],
      initializer: ProjectInitializer[F],
      F: Effect[F]
  ): F[Project] = {

    def initializeOrError(projectOpt: Option[Project]): F[Project] =
      projectOpt match {
        case Some(project) => initializer(project, subject) >> F.pure(project)
        case _             => F.raiseError(KgError.NotFound(Some(projectRef.show)): KgError)
      }

    projectCache
      .get(projectRef)
      .flatMap {
        case Some(project) => F.pure(project)
        case _             => adminClient.fetchProject(organizationRef.id, projectRef.id).flatMap(initializeOrError)
      }
  }
}
