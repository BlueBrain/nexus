package ch.epfl.bluebrain.nexus.kg.async

import java.time.Instant
import java.util.UUID
import java.util.concurrent.TimeUnit.MILLISECONDS

import akka.actor.{Actor, ActorLogging, ActorRef, ActorSystem, Props, Stash}
import akka.cluster.sharding.ShardRegion.{ExtractEntityId, ExtractShardId}
import akka.cluster.sharding.{ClusterSharding, ClusterShardingSettings, ShardRegion}
import akka.pattern.pipe
import akka.persistence.query.PersistenceQuery
import akka.persistence.query.scaladsl.EventsByTagQuery
import akka.stream.scaladsl.Source
import akka.util.Timeout
import cats.implicits._
import ch.epfl.bluebrain.nexus.admin.index.ProjectCache
import ch.epfl.bluebrain.nexus.admin.projects.ProjectResource
import ch.epfl.bluebrain.nexus.commons.cache.KeyValueStoreSubscriber.KeyValueStoreChange._
import ch.epfl.bluebrain.nexus.commons.cache.KeyValueStoreSubscriber.KeyValueStoreChanges
import ch.epfl.bluebrain.nexus.commons.cache.OnKeyValueStoreChange
import ch.epfl.bluebrain.nexus.delta.client.{DeltaClient, DeltaClientConfig}
import ch.epfl.bluebrain.nexus.delta.config.AppConfig
import ch.epfl.bluebrain.nexus.iam.acls.{AccessControlList, Acls}
import ch.epfl.bluebrain.nexus.iam.types.Caller
import ch.epfl.bluebrain.nexus.kg.IdStats
import ch.epfl.bluebrain.nexus.kg.async.ProjectViewCoordinatorActor.Msg._
import ch.epfl.bluebrain.nexus.kg.async.ProjectViewCoordinatorActor._
import ch.epfl.bluebrain.nexus.kg.cache.ViewCache
import ch.epfl.bluebrain.nexus.kg.indexing.Statistics.ViewStatistics
import ch.epfl.bluebrain.nexus.kg.indexing.View.CompositeView.Source.{CrossProjectEventStream, RemoteProjectEventStream}
import ch.epfl.bluebrain.nexus.kg.indexing.View.CompositeView.{Source => CompositeSource}
import ch.epfl.bluebrain.nexus.kg.indexing.View._
import ch.epfl.bluebrain.nexus.kg.indexing._
import ch.epfl.bluebrain.nexus.kg.resources.ProjectIdentifier.ProjectRef
import ch.epfl.bluebrain.nexus.kg.resources.syntax._
import ch.epfl.bluebrain.nexus.kg.resources.{Event, ProjectIdentifier, Resources}
import ch.epfl.bluebrain.nexus.kg.routes.Clients
import ch.epfl.bluebrain.nexus.rdf.Iri.AbsoluteIri
import ch.epfl.bluebrain.nexus.sourcing.projections.ProgressFlow.{PairMsg, ProgressFlowElem}
import ch.epfl.bluebrain.nexus.sourcing.projections.ProjectionProgress.{NoProgress, SingleProgress}
import ch.epfl.bluebrain.nexus.sourcing.projections._
import ch.epfl.bluebrain.nexus.sourcing.projections.instances._
import com.typesafe.scalalogging.Logger
import monix.eval.Task
import monix.execution.CancelableFuture
import monix.execution.Scheduler.Implicits.global
import shapeless.TypeCase

import scala.collection.immutable.Set
import scala.collection.mutable
import scala.concurrent.Future
import scala.reflect.ClassTag

/**
  * Coordinator backed by akka actor which runs the views' streams inside the provided project
  */
//noinspection ActorMutableStateInspection
abstract private class ProjectViewCoordinatorActor(viewCache: ViewCache[Task])(implicit
    val config: AppConfig,
    as: ActorSystem,
    projections: Projections[Task, String]
) extends Actor
    with Stash
    with ActorLogging {

  implicit private val tm: Timeout = Timeout(config.defaultAskTimeout)
  private val children             = mutable.Map.empty[IndexedView, ViewCoordinator]
  protected val projectsStream     = mutable.Map.empty[ProjectIdentifier, ViewCoordinator]

  def receive: Receive = {
    case Start(_, project, views, projectsAcls) =>
      log.debug("Started coordinator for project '{}' with initial views '{}'", project.value.show, views)
      context.become(initialized(project))
      viewCache.subscribe(ProjectRef(project.uuid), onChange(ProjectRef(project.uuid)))
      val accessibleViews = views.foldLeft(Set.empty[IndexedView]) {
        case (acc, v: CompositeView) =>
          val accessibleSources = v.sources -- inaccessibleSources(v, projectsAcls)
          if (accessibleSources.isEmpty) acc else acc + v.copy(sources = accessibleSources)
        case (acc, v: SingleView)    => acc + v
      }
      children ++= accessibleViews.map(view => view -> startCoordinator(view, project, restart = false))
      startProjectStreamFromDB(project)
      unstashAll()
    case other                                  =>
      log.debug("Received non Start message '{}', stashing until the actor is initialized", other)
      stash()
  }

  private def progresses(
      viewId: AbsoluteIri
  ): Task[(Set[(IdentifiedProgress[SingleProgress], SingleProgress)], Option[Instant])] =
    children.findBy[IndexedView](viewId) match {
      case Some((v: CompositeView, coord)) =>
        progress(coord).flatMap { pp =>
          val progressesF = Task
            .sequence(v.sources.map { s =>
              val projectionsProgress = v.projections.map { p =>
                IdentifiedProgress(s.id, p.view.id, pp.progress(v.progressId(s.id, p.view.id)))
              }
              val sourceProgress      = IdentifiedProgress(s.id, pp.progress(s.id.asString))
              val projectStreamF      =
                v.projectSource(s.id).flatMap(projectsStream.get).map(progress).getOrElse(Task.pure(NoProgress))
              val combinedProgress    = projectionsProgress + sourceProgress
              projectStreamF.map(projectStream => combinedProgress.map(_ -> projectStream.minProgress))
            })
            .map(_.flatten)
          progressesF.map(progresses => (progresses, v.nextRestart(coord.prevRestart)))
        }
      case Some((v, coord))                =>
        progress(coord).flatMap { pp =>
          projectsStream.get(v.ref).map(progress).getOrElse(Task.pure(NoProgress)).map(_.minProgress).map {
            projectStream =>
              (Set((IdentifiedProgress(pp.progress(v.progressId)), projectStream)), None)
          }
        }
      case _                               => Task.pure((Set.empty, None))
    }

  protected def progress(coordinator: ViewCoordinator): Task[ProjectionProgress] =
    coordinator.value.state().map(_.getOrElse(NoProgress))

  private def statistics(viewId: AbsoluteIri): Task[Set[IdStats]] =
    progresses(viewId).map {
      case (set, nextRestart) =>
        set.map {
          case (viewIdentifiedProgress, pp) =>
            val ppDate = pp.offset.asInstant
            viewIdentifiedProgress.map { vp =>
              val lastEvDate = vp.offset.asInstant
              ViewStatistics(vp.processed, vp.discarded, vp.failed, pp.processed, lastEvDate, ppDate, nextRestart)
            }
        }
    }

  private def inaccessibleSources(
      view: CompositeView,
      projectsAcls: Map[ProjectResource, AccessControlList]
  ): Set[CompositeView.Source] =
    view.sourcesBy[CrossProjectEventStream].foldLeft(Set.empty[CompositeView.Source]) { (inaccessible, current) =>
      val acl       = current.project.findIn(projectsAcls.keySet).map(projectsAcls(_)).getOrElse(AccessControlList.empty)
      val readPerms = acl.value.exists { case (id, perms) => current.identities.contains(id) && perms.contains(read) }
      if (readPerms) inaccessible else inaccessible + current
    }

  private def startCrossProjectStreamFromDB(projectRef: ProjectRef): Unit = {
    val progressId                                   = projectStreamId()
    val sourceF: Task[Source[ProjectionProgress, _]] = projections.progress(progressId).map { initial =>
      PersistenceQuery(as)
        .readJournalFor[EventsByTagQuery](config.persistence.queryJournalPlugin)
        .eventsByTag(s"project=${projectRef.id}", initial.minProgress.offset)
        .map[PairMsg[Any]](e => Right(Message(e, progressId)))
        .via(projectStreamFlow(initial))
    }
    val coordinator                                  = ViewCoordinator(StreamSupervisor.start(sourceF, progressId, context.actorOf))
    projectsStream += projectRef -> coordinator
  }

  private def startProjectStreamFromDB(project: ProjectResource): Unit = {
    val progressId                                   = projectStreamId()
    val sourceF: Task[Source[ProjectionProgress, _]] = projections.progress(progressId).map { initial =>
      PersistenceQuery(as)
        .readJournalFor[EventsByTagQuery](config.persistence.queryJournalPlugin)
        .eventsByTag(s"project=${project.uuid}", initial.minProgress.offset)
        .map[PairMsg[Any]](e => Right(Message(e, progressId)))
        .via(projectStreamFlow(initial))
        .via(kamonProjectMetricsFlow(metricsPrefix, project.value.projectLabel))

    }
    val coordinator                                  = ViewCoordinator(StreamSupervisor.start(sourceF, progressId, context.actorOf))
    projectsStream += ProjectRef(project.uuid) -> coordinator
  }

  private def startProjectStreamSource(source: CompositeSource): Unit =
    source match {
      case CrossProjectEventStream(_, _, ref: ProjectRef, _) => startCrossProjectStreamFromDB(ref)
      case s: RemoteProjectEventStream                       => startProjectStreamFromSSE(s)
      case _                                                 => ()
    }

  private def startProjectStreamFromSSE(remoteSource: RemoteProjectEventStream): Unit = {
    val progressId                                   = projectStreamId()
    val clientCfg                                    = DeltaClientConfig(remoteSource.endpoint)
    val client                                       = DeltaClient[Task](clientCfg)
    val sourceF: Task[Source[ProjectionProgress, _]] = projections.progress(progressId).map { initial =>
      val source = client
        .events(remoteSource.project, initial.minProgress.offset)(remoteSource.token)
        .map[PairMsg[Any]](e => Right(Message(e, progressId)))
      source.via(projectStreamFlow(initial)).via(kamonProjectMetricsFlow(metricsPrefix, remoteSource.project))
    }
    val coordinator                                  = ViewCoordinator(StreamSupervisor.start(sourceF, progressId, context.actorOf))
    projectsStream += remoteSource.project -> coordinator
  }

  private def projectStreamFlow(initial: ProjectionProgress) = {
    implicit val indexing: IndexingConfig = config.elasticSearch.indexing
    ProgressFlowElem[Task, Any]
      .collectCast[Event]
      .groupedWithin(indexing.batch, indexing.batchTimeout)
      .distinct()
      .mergeEmit()
      .toProgress(initial)
  }

  private def projectStreamId(): String = s"project-event-${UUID.randomUUID()}"

  /**
    * Triggered in order to build an indexer actor for a provided view
    *
    * @param view        the view from where to create the indexer actor
    * @param project     the project of the current coordinator
    * @param restart     a flag to decide whether to restart from the beginning or to resume from the previous offset
    * @param prevRestart the previous optional restart time
    * @return the actor reference
    */
  def startCoordinator(
      view: IndexedView,
      project: ProjectResource,
      restart: Boolean,
      prevRestart: Option[Instant] = None
  ): ViewCoordinator

  /**
    * Triggered in order to build an indexer actor for a provided composite view with ability to reset the projection offset to NoOffset
    *
    * @param view            the view from where to create the indexer actor
    * @param project         the project of the current coordinator
    * @param restartProgress the set of progressId to be restarted
    * @param prevRestart     the previous optional restart time
    * @return the actor reference
    */
  def startCoordinator(
      view: CompositeView,
      project: ProjectResource,
      restartProgress: Set[String],
      prevRestart: Option[Instant]
  ): ViewCoordinator

  /**
    * Triggered once an indexer actor has been stopped to clean up the indices
    *
    * @param view    the view linked to the indexer actor
    * @param project the project of the current coordinator
    */
  def deleteViewIndices(view: IndexedView, project: ProjectResource): Task[Unit]

  /**
    * Triggered when a change to key value store occurs.
    *
    * @param ref the project unique identifier
    */
  def onChange(ref: ProjectRef): OnKeyValueStoreChange[Task, AbsoluteIri, View]

  def initialized(project: ProjectResource): Receive = {

    // format: off
    def logStop(view: View, reason: String): Unit =
      log.info("View '{}' is going to be stopped at revision '{}' for project '{}'. Reason: '{}'.", view.id, view.rev, project.value.show, reason)

    def logStart(view: View, extra: String): Unit =
      log.info("View '{}' is going to be started at revision '{}' for project '{}'. {}.", view.id, view.rev, project.value.show, extra)
    // format: on

    def stopView(
        v: IndexedView,
        coordinator: ViewCoordinator,
        deleteIndices: Boolean = true
    ): Future[Unit] = {
      (Task.delay(children -= v) >>
        coordinator.value.stop() >>
        Task.delay(coordinator.cancelable.cancel()) >>
        (if (deleteIndices) deleteViewIndices(v, project) else Task.unit)).runToFuture
    }

    def startView(view: IndexedView, restart: Boolean, prevRestart: Option[Instant]): Unit = {
      logStart(view, s"restart: '$restart'")
      children += view -> startCoordinator(view, project, restart, prevRestart)
      view match {
        case v: CompositeView => v.sources.foreach(startProjectStreamSource(_))
        case _                => ()
      }
    }

    def eligibleRestart(currentView: IndexedView, newView: IndexedView, ignoreRev: Boolean): Boolean =
      currentView.id == newView.id && (!ignoreRev && currentView.rev != newView.rev || ignoreRev)

    def startProjectionsView(
        view: CompositeView,
        restartProgress: Set[String],
        prevRestart: Option[Instant]
    ): Unit = {
      logStart(view, s"restart for projections progress: '$restartProgress'")
      children += view -> startCoordinator(view, project, restartProgress, prevRestart)
    }

    {
      case AclChanges(uuid, projectsAcls, views)                           =>
        val (overrideViews, updateViews, removeViews) =
          views.foldLeft((Set.empty[IndexedView], Set.empty[IndexedView], Set.empty[IndexedView])) {
            case ((overrideV, updateV, removeV), v) =>
              val newSources = v.sources -- inaccessibleSources(v, projectsAcls)
              val newView    = v.copy(sources = newSources)
              if (newSources.isEmpty)
                (overrideV, updateV, removeV + newView)
              else
                children.keySet.find(_.id == v.id) match {
                  case Some(c: CompositeView) if c.sources != newSources => (overrideV + newView, updateV, removeV)
                  case _                                                 => (overrideV, updateV + newView, removeV)
                }
          }
        if (overrideViews.nonEmpty) self ! ViewsAddedOrModified(uuid, restart = true, overrideViews, ignoreRev = true)
        if (updateViews.nonEmpty) self ! ViewsAddedOrModified(uuid, restart = true, updateViews, ignoreRev = false)
        if (removeViews.nonEmpty) self ! ViewsRemoved(uuid, removeViews.map(_.id))

      case ViewsAddedOrModified(_, restart, views, ignoreRev, prevRestart) =>
        val _ = views.map {
          case view if !children.keySet.exists(_.id == view.id) => startView(view, restart, prevRestart)
          case view                                             =>
            children
              .find { case (current, _) => eligibleRestart(current, view, ignoreRev) }
              .foreach {
                case (old, coordinator) =>
                  stopView(old, coordinator) >> Future(startView(view, restart, prevRestart))
                  logStop(old, s"a new rev of the same view is going to be started, restart '$restart'")
              }
        }

      case ViewsRemoved(_, views)                                          =>
        children.view.filterKeys(v => views.contains(v.id)).foreach {
          case (v, coordinator) =>
            logStop(v, "removed from the cache")
            stopView(v, coordinator)
        }

      case RestartView(uuid, viewId)                                       =>
        val _ = children.findBy[IndexedView](viewId) match {
          case Some((view, coordinator)) =>
            if (self != sender()) sender() ! Option(Ack(uuid))
            logStop(view, "restart triggered from client")
            val prevRestart = coordinator.prevRestart
            stopView(view, coordinator, deleteIndices = false)
              .map(_ => self ! ViewsAddedOrModified(project.uuid, restart = true, Set(view), prevRestart = prevRestart))
          case None                      => if (self != sender()) sender() ! None
        }

      case RestartProjection(uuid, viewId, projectionIdOpt)                =>
        val _ = children.findBy[CompositeView](viewId) match {
          case Some((v, coord)) if projectionIdOpt.forall(pId => v.projections.exists(_.view.id == pId)) =>
            if (self != sender()) sender() ! Option(Ack(uuid))
            logStop(v, "restart triggered from client")
            stopView(v, coord, deleteIndices = false)
              .map(_ => startProjectionsView(v, v.projectionsProgress(projectionIdOpt), coord.prevRestart))
          case _                                                                                         =>
            if (self != sender()) sender() ! None
        }

      case Stop(_)                                                         =>
        children.foreach {
          case (view, coordinator) =>
            logStop(view, "deprecated organization")
            stopView(view, coordinator, deleteIndices = false)
        }

      case FetchOffset(_, viewId)                                          =>
        val _ = progresses(viewId).map {
          case (set, _) => set.map { case (progress, _) => progress.map(_.offset) }
        }.runToFuture pipeTo sender()

      case FetchStatistics(_, viewId)                                      =>
        val _ = statistics(viewId).runToFuture pipeTo sender()

      case UpdateRestart(_, viewId, value)                                 =>
        children.findBy[CompositeView](viewId).foreach {
          case (v, coord) => children += (v -> coord.copy(prevRestart = value))
        }

      case _: Start                                                        => //ignore, it has already been started

      case other => log.error("Unexpected message received '{}'", other)

    }

  }

}

object ProjectViewCoordinatorActor {

  val metricsPrefix = "kg_indexer"

  final case class ViewCoordinator(
      value: StreamSupervisor[Task, ProjectionProgress],
      prevRestart: Option[Instant] = None,
      cancelable: CancelableFuture[Unit] = CancelableFuture.unit
  )

  implicit private[async] class IndexedViewSyntax[B](private val map: mutable.Map[IndexedView, B]) extends AnyVal {
    def findBy[T <: IndexedView](id: AbsoluteIri)(implicit T: ClassTag[T]): Option[(T, B)] =
      map.collectFirst { case (T(view), value) if view.id == id => view -> value }
  }

  sealed private[async] trait Msg {

    /**
      * @return the project unique identifier
      */
    def uuid: UUID
  }
  object Msg {

    // format: off
    final case class Start(uuid: UUID, project: ProjectResource, views: Set[IndexedView], projectsAcls: Map[ProjectResource, AccessControlList])                                  extends Msg
    final case class Stop(uuid: UUID)                                                                                                                             extends Msg
    final case class ViewsAddedOrModified(uuid: UUID, restart: Boolean, views: Set[IndexedView], ignoreRev: Boolean = false, prevRestart: Option[Instant] = None) extends Msg
    final case class RestartView(uuid: UUID, viewId: AbsoluteIri)                                                                                                 extends Msg
    final case class RestartProjection(uuid: UUID, viewId: AbsoluteIri, projectionId: Option[AbsoluteIri] = None)                                                 extends Msg
    final case class UpdateRestart(uuid: UUID, viewId: AbsoluteIri, prevRestart: Option[Instant])                                                                 extends Msg
    final case class Ack(uuid: UUID)                                                                                                                              extends Msg
    final case class ViewsRemoved(uuid: UUID, views: Set[AbsoluteIri])                                                                                            extends Msg
    final case class FetchOffset(uuid: UUID, viewId: AbsoluteIri)                                                                                                 extends Msg
    final case class FetchStatistics(uuid: UUID, viewId: AbsoluteIri)                                                                                             extends Msg
    final case class AclChanges(uuid: UUID, projectsAcls: Map[ProjectResource, AccessControlList], views: Set[CompositeView])                                             extends Msg
    // format: on
  }

  private[async] def shardExtractor(shards: Int): ExtractShardId = {
    case msg: Msg                    => (math.abs(msg.uuid.hashCode) % shards).toString
    case ShardRegion.StartEntity(id) => (id.hashCode                 % shards).toString
  }

  private[async] val entityExtractor: ExtractEntityId = {
    case msg: Msg => (msg.uuid.toString, msg)
  }

  /**
    * Starts the ProjectViewCoordinator shard that coordinates the running views' streams inside the provided project
    *
    * @param resources        the resources operations
    * @param viewCache        the view Cache
    * @param acls        the acls surface API
    * @param shardingSettings the sharding settings
    * @param shards           the number of shards to use
    */
  @SuppressWarnings(Array("MaxParameters"))
  final def start(
      resources: Resources[Task],
      viewCache: ViewCache[Task],
      acls: Acls[Task],
      saCaller: Caller,
      shardingSettings: Option[ClusterShardingSettings],
      shards: Int
  )(implicit
      clients: Clients[Task],
      config: AppConfig,
      as: ActorSystem,
      projections: Projections[Task, String],
      projectCache: ProjectCache[Task]
  ): ActorRef = {

    val props = Props(
      new ProjectViewCoordinatorActor(viewCache) {

        private def scheduleRestart(view: CompositeView, prevRestart: Option[Instant]): Task[Unit] = {
          val restart = view.rebuildStrategy.traverse { interval =>
            // format: off
            for {
              _             <- Task.sleep(interval.value)
              progresses    <- projectsStream.view.filterKeys(view.projectsSource.contains).values.toList.traverse(progress)
              latestEvTime   = progresses.map(_.minProgress.offset).max.asInstant
            } yield latestEvTime.forall(_.isAfter(prevRestart.getOrElse(Instant.EPOCH)))
            // format: on
          }
          restart.flatMap {
            case Some(true)  => Task.delay(self ! RestartProjection(view.ref.id, view.id, None))
            case Some(false) =>
              for {
                current <- Task.timer.clock.realTime(MILLISECONDS).map(Instant.ofEpochMilli)
                _       <- Task.delay(self ! UpdateRestart(view.ref.id, view.id, Some(current)))
                next    <- scheduleRestart(view, Some(current))
              } yield next
            case None        => Task.unit
          }
        }

        implicit private val actorInitializer: (Props, String) => ActorRef = context.actorOf
        override def startCoordinator(
            view: IndexedView,
            project: ProjectResource,
            restart: Boolean,
            prevRestart: Option[Instant]
        ): ViewCoordinator                                                 =
          view match {
            case v: ElasticSearchView =>
              ViewCoordinator(ElasticSearchIndexer.start(v, resources, project, restart))
            case v: SparqlView        =>
              ViewCoordinator(SparqlIndexer.start(v, resources, project, restart))
            case v: CompositeView     =>
              val coordinator = CompositeIndexer.start(v, resources, project, restart)
              val restartTime = prevRestart.map(_ => Instant.now()) orElse Some(Instant.EPOCH)
              ViewCoordinator(coordinator, Some(Instant.now()), scheduleRestart(v, restartTime).runToFuture)
          }

        override def startCoordinator(
            view: CompositeView,
            project: ProjectResource,
            restartProgress: Set[String],
            prevRestart: Option[Instant]
        ): ViewCoordinator = {
          val coordinator = CompositeIndexer.start(view, resources, project, restartProgress)
          val restartTime = prevRestart.map(_ => Instant.now()) orElse Some(Instant.EPOCH)
          ViewCoordinator(coordinator, Some(Instant.now()), scheduleRestart(view, restartTime).runToFuture)
        }

        override def deleteViewIndices(view: IndexedView, project: ProjectResource): Task[Unit] = {
          def delete(v: SingleView): Task[Unit] = {
            log.info("Index '{}' is removed from project '{}'", v.index, project.value.show)
            v.deleteIndex >> Task.unit
          }

          view match {
            case v: SingleView    =>
              delete(v)
            case v: CompositeView =>
              delete(v.defaultSparqlView) >> v.projections.toList.traverse(p => delete(p.view)) >> Task.unit
          }
        }

        override def onChange(ref: ProjectRef): OnKeyValueStoreChange[Task, AbsoluteIri, View] =
          onViewChange(acls, saCaller, ref, self)

      }
    )

    start(props, shardingSettings, shards)
  }

  final private[async] def start(props: Props, shardingSettings: Option[ClusterShardingSettings], shards: Int)(implicit
      as: ActorSystem
  ): ActorRef = {

    val settings = shardingSettings.getOrElse(ClusterShardingSettings(as)).withRememberEntities(true)
    ClusterSharding(as).start("project-view-coordinator", props, settings, entityExtractor, shardExtractor(shards))
  }

  private[async] def onViewChange(acls: Acls[Task], saCaller: Caller, ref: ProjectRef, actorRef: ActorRef)(implicit
      projectCache: ProjectCache[Task]
  ): OnKeyValueStoreChange[Task, AbsoluteIri, View] =
    new OnKeyValueStoreChange[Task, AbsoluteIri, View] {

      private val `Composite`          = TypeCase[CompositeView]
      private val `Indexed`            = TypeCase[IndexedView]
      implicit private val log: Logger = Logger[this.type]

      private def containsCrossSources(view: CompositeView): Boolean =
        view.sourcesBy[CrossProjectEventStream].nonEmpty

      override def apply(onChange: KeyValueStoreChanges[AbsoluteIri, View]): Task[Unit] = {
        val (toWriteNow, toCheckAcls, toRemove) =
          onChange.values.foldLeft((Set.empty[IndexedView], Set.empty[CompositeView], Set.empty[IndexedView])) {
            case ((write, checkAcl, removed), ValueAdded(_, `Composite`(view))) if containsCrossSources(view)    =>
              (write, checkAcl + view, removed)
            case ((write, checkAcl, removed), ValueModified(_, `Composite`(view))) if containsCrossSources(view) =>
              (write, checkAcl + view, removed)
            case ((write, checkAcl, removed), ValueAdded(_, `Indexed`(view)))                                    => (write + view, checkAcl, removed)
            case ((write, checkAcl, removed), ValueModified(_, `Indexed`(view)))                                 => (write + view, checkAcl, removed)
            case ((write, checkAcl, removed), ValueRemoved(_, `Indexed`(view)))                                  => (write, checkAcl, removed + view)
            case ((write, checkAcl, removed), _)                                                                 => (write, checkAcl, removed)
          }
        Task.delay {
          if (toWriteNow.nonEmpty) actorRef ! ViewsAddedOrModified(ref.id, restart = false, toWriteNow)
          if (toCheckAcls.nonEmpty) {
            val task = for {
              acls         <- acls.list(anyProject, ancestors = true, self = false)(saCaller)
              projectsAcls <- resolveProjects(acls)
            } yield actorRef ! AclChanges(ref.id, projectsAcls, toCheckAcls)
            task.runToFuture
          }
          if (toRemove.nonEmpty) actorRef ! ViewsRemoved(ref.id, toRemove.map(_.id))
        }
      }
    }
}
