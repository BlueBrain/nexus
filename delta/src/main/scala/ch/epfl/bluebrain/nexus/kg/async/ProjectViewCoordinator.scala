package ch.epfl.bluebrain.nexus.kg.async

import akka.actor.{ActorRef, ActorSystem}
import akka.pattern.{ask, AskTimeoutException}
import akka.persistence.query.scaladsl.EventsByTagQuery
import akka.persistence.query.{NoOffset, Offset, PersistenceQuery}
import akka.stream.scaladsl.Source
import akka.util.Timeout
import cats.effect.{Async, ContextShift, IO}
import cats.implicits._
import ch.epfl.bluebrain.nexus.admin.projects.ProjectResource
import ch.epfl.bluebrain.nexus.iam.acls.{AccessControlList, AccessControlLists, AclEvent, Acls}
import ch.epfl.bluebrain.nexus.iam.types.Caller
import ch.epfl.bluebrain.nexus.kg.async.ProjectViewCoordinatorActor.Msg._
import ch.epfl.bluebrain.nexus.kg.cache.Caches
import ch.epfl.bluebrain.nexus.admin.index.ProjectCache
import ch.epfl.bluebrain.nexus.iam.io.TaggingAdapter
import ch.epfl.bluebrain.nexus.kg.indexing.Statistics
import ch.epfl.bluebrain.nexus.kg.indexing.Statistics.CompositeViewStatistics
import ch.epfl.bluebrain.nexus.kg.indexing.View.CompositeView.Source.CrossProjectEventStream
import ch.epfl.bluebrain.nexus.kg.indexing.View.{CompositeView, IndexedView, SingleView}
import ch.epfl.bluebrain.nexus.kg.resources.ProjectIdentifier.ProjectRef
import ch.epfl.bluebrain.nexus.kg.resources.syntax._
import ch.epfl.bluebrain.nexus.kg.resources.{CompositeViewOffset, OrganizationRef, Resources}
import ch.epfl.bluebrain.nexus.kg.routes.Clients
import ch.epfl.bluebrain.nexus.kg.{IdOffset, IdStats, KgError}
import ch.epfl.bluebrain.nexus.rdf.Iri.AbsoluteIri
import ch.epfl.bluebrain.nexus.delta.config.AppConfig
import ch.epfl.bluebrain.nexus.sourcing.akka.aggregate.AggregateConfig
import ch.epfl.bluebrain.nexus.sourcing.projections.ProgressFlow.{PairMsg, ProgressFlowElem}
import ch.epfl.bluebrain.nexus.sourcing.projections.{Message, Projections, StreamSupervisor}
import com.typesafe.scalalogging.Logger
import monix.eval.Task
import monix.execution.Scheduler.Implicits.global
import ch.epfl.bluebrain.nexus.kg.async.ProjectViewCoordinator.log

import scala.concurrent.ExecutionContext
import scala.reflect.ClassTag
import scala.util.control.NonFatal

/**
  * ProjectViewCoordinator backed by [[ProjectViewCoordinatorActor]] that sends messages to the underlying actor
  *
  * @param cache the cache
  * @param ref   the underlying actor reference
  * @tparam F the effect type
  */
@SuppressWarnings(Array("ListSize"))
class ProjectViewCoordinator[F[_]](cache: Caches[F], acls: Acls[F], saCaller: Caller, ref: ActorRef)(implicit
    config: AppConfig,
    F: Async[F],
    ec: ExecutionContext
) {

  implicit private val timeout: Timeout               = config.aggregate.askTimeout
  implicit private val contextShift: ContextShift[IO] = IO.contextShift(ec)
  implicit private val projectCache: ProjectCache[F]  = cache.project

  /**
    * Fetches view statistics.
    *
    * @param viewId the view unique identifier on a project
    */
  def statistics(viewId: AbsoluteIri)(implicit project: ProjectResource): F[Option[Statistics]] =
    fetchAllStatistics(viewId).map(_.filter(_.projectionId.isEmpty).foldLeft[Option[Statistics]](None) {
      case (None, c) if c.sourceId.isEmpty                                => Some(c.value)
      case (None, c)                                                      => Some(CompositeViewStatistics(c))
      case (Some(acc: CompositeViewStatistics), c) if c.sourceId.nonEmpty => Some(acc + c)
      case (acc, _)                                                       => acc
    })

  /**
    * Fetches view statistics for a given projection.
    *
    * @param viewId       the view unique identifier on a project
    * @param projectionId the projection unique identifier on the target view
    */
  def projectionStats(viewId: AbsoluteIri, projectionId: AbsoluteIri)(implicit
      project: ProjectResource
  ): F[Option[Statistics]] =
    fetchAllStatistics(viewId).map(_.filter(_.projectionId.contains(projectionId)).foldLeft[Option[Statistics]](None) {
      case (None, c)                               => Some(CompositeViewStatistics(c))
      case (Some(acc: CompositeViewStatistics), c) => Some(acc + c)
      case (acc, _)                                => acc
    })

  /**
    * Fetches view statistics for all projections.
    *
    * @param viewId the view unique identifier on a project
    */
  def projectionStats(viewId: AbsoluteIri)(implicit project: ProjectResource): F[Option[Set[IdStats]]] =
    fetchAllStatistics(viewId).map(_.filter(_.projectionId.nonEmpty)).map(emptyToNone)

  /**
    * Fetches view statistics for a given source.
    *
    * @param viewId   the view unique identifier on a project
    * @param sourceId the source unique identifier on the target view
    */
  def sourceStat(viewId: AbsoluteIri, sourceId: AbsoluteIri)(implicit project: ProjectResource): F[Option[Statistics]] =
    fetchAllStatistics(viewId).map(_.collectFirst {
      case id if id.sourceId.contains(sourceId) && id.projectionId.isEmpty => id.value
    })

  /**
    * Fetches view statistics for all sources.
    *
    * @param viewId the view unique identifier on a project
    */
  def sourceStats(viewId: AbsoluteIri)(implicit project: ProjectResource): F[Option[Set[IdStats]]] =
    fetchAllStatistics(viewId).map(_.filter(id => id.sourceId.nonEmpty && id.projectionId.isEmpty)).map(emptyToNone)

  /**
    * Fetches view offset.
    *
    * @param viewId the view unique identifier on a project
    */
  def offset(viewId: AbsoluteIri)(implicit project: ProjectResource): F[Option[Offset]] =
    fetchAllOffsets(viewId).map(_.filter(_.projectionId.isEmpty).foldLeft[Option[Offset]](None) {
      case (None, c) if c.sourceId.isEmpty                               => Some(c.value)
      case (None, c)                                                     => Some(CompositeViewOffset(Set(c)))
      case (Some(CompositeViewOffset(values)), c) if c.sourceId.nonEmpty => Some(CompositeViewOffset(values + c))
      case (acc, _)                                                      => acc
    })

  /**
    * Fetches view offset for a given projection.
    *
    * @param viewId       the view unique identifier on a project
    * @param projectionId the projection unique identifier on the target view
    */
  def projectionOffset(viewId: AbsoluteIri, projectionId: AbsoluteIri)(implicit
      project: ProjectResource
  ): F[Option[Offset]] =
    fetchAllOffsets(viewId).map(_.filter(_.projectionId.contains(projectionId)).foldLeft[Option[Offset]](None) {
      case (None, c)                              => Some(CompositeViewOffset(Set(c)))
      case (Some(CompositeViewOffset(values)), c) => Some(CompositeViewOffset(values + c))
      case (acc, _)                               => acc
    })

  /**
    * Fetches view offsets for all projections.
    *
    * @param viewId the view unique identifier on a project
    */
  def projectionOffsets(viewId: AbsoluteIri)(implicit project: ProjectResource): F[Option[Set[IdOffset]]] =
    fetchAllOffsets(viewId).map(_.filter(_.projectionId.nonEmpty)).map(emptyToNone)

  /**
    * Fetches view offset for a given source.
    *
    * @param viewId   the view unique identifier on a project
    * @param sourceId the source unique identifier on the target view
    */
  def sourceOffset(viewId: AbsoluteIri, sourceId: AbsoluteIri)(implicit project: ProjectResource): F[Option[Offset]] =
    fetchAllOffsets(viewId).map(_.collectFirst {
      case id if id.sourceId.contains(sourceId) && id.projectionId.isEmpty => id.value
    })

  /**
    * Fetches view offsets for all sources.
    *
    * @param viewId the view unique identifier on a project
    */
  def sourceOffsets(viewId: AbsoluteIri)(implicit project: ProjectResource): F[Option[Set[IdOffset]]] =
    fetchAllOffsets(viewId).map(_.filter(id => id.sourceId.nonEmpty && id.projectionId.isEmpty)).map(emptyToNone)

  /**
    * Starts the project view coordinator for the provided project sending a Start message to the
    * underlying coordinator actor.
    * The coordinator actor will attempt to fetch the views linked to the current project and start them
    * while start listening to messages coming from the view cache and the coordinator itself
    *
    * @param project the project for which the view coordinator is triggered
    */
  def start(project: ProjectResource): F[Unit] =
    cache.view.getBy[IndexedView](ProjectRef(project.uuid)).flatMap {
      case views if containsCrossProject(views) =>
        acls
          .list(anyProject, ancestors = true, self = false)(saCaller)
          .flatMap(resolveProjects(_))
          .map(projAcls => ref ! Start(project.uuid, project, views, projAcls))
      case views                                => F.pure(ref ! Start(project.uuid, project, views, Map.empty[ProjectResource, AccessControlList]))
    }

  private def containsCrossProject(views: Set[IndexedView]): Boolean =
    views.exists {
      case _: SingleView       => false
      case view: CompositeView => view.sourcesBy[CrossProjectEventStream].nonEmpty
    }

  /**
    * Stops the coordinator children views actors and indices related to all the projects
    * that belong to the provided organization.
    *
    * @param orgRef the organization unique identifier
    */
  def stop(orgRef: OrganizationRef): F[Unit] =
    cache.project.listUnsafe(orgRef.id).flatMap(_.map(projRes => stop(ProjectRef(projRes.uuid))).sequence) >> F.unit

  /**
    * Stops the coordinator children view actors and indices that belong to the provided organization.
    *
    * @param projectRef the project unique identifier
    */
  def stop(projectRef: ProjectRef): F[Unit] =
    F.delay(ref ! Stop(projectRef.id))

  /**
    * Triggers restart of a view from the initial progress.
    *
    * @param viewId the view unique identifier on a project
    */
  def restart(viewId: AbsoluteIri)(implicit project: ProjectResource): F[Option[Unit]] = {
    val msgF = IO.fromFuture(IO(ref ? RestartView(project.uuid, viewId))).to[F]
    parseOpt[Ack](msgF).recoverWith(logAndRaiseError(project.value.show, "restart")).map(_.map(_ => ()))
  }

  /**
    * Triggers restart of a composite view projections. All projections will start from the initial progress.
    *
    * @param viewId the view unique identifier on a project
    * @return Some(())) if view exists, None otherwise wrapped in [[F]]
    */
  def restartProjections(viewId: AbsoluteIri)(implicit project: ProjectResource): F[Option[Unit]] = {
    val msgF = IO.fromFuture(IO(ref ? RestartProjection(project.uuid, viewId))).to[F]
    parseOpt[Ack](msgF).recoverWith(logAndRaiseError(project.value.show, "restart")).map(_.map(_ => ()))
  }

  /**
    * Triggers restart of a composite view projections. Projections will start from the initial progress.
    *
    * @param viewId       the view unique identifier on a project
    * @param projectionId the view projection unique identifier on a project
    * @return Some(())) if projection view exists, None otherwise wrapped in [[F]]
    */
  def restartProjection(viewId: AbsoluteIri, projectionId: AbsoluteIri)(implicit
      project: ProjectResource
  ): F[Option[Unit]] = {
    val msgF = IO.fromFuture(IO(ref ? RestartProjection(project.uuid, viewId, projectionId = Some(projectionId)))).to[F]
    parseOpt[Ack](msgF).recoverWith(logAndRaiseError(project.value.show, "restart")).map(_.map(_ => ()))
  }

  /**
    * Notifies the underlying coordinator actor about an ACL change on the passed ''project''
    *
    * @param acls    the current ACLs
    * @param project the project affected by the ACLs change
    */
  def changeAcls(acls: AccessControlLists, project: ProjectResource): F[Unit] =
    for {
      views        <- cache.view.getBy[CompositeView](ProjectRef(project.uuid))
      projectsAcls <- resolveProjects(acls)
    } yield ref ! AclChanges(project.uuid, projectsAcls, views)

  private def parseOpt[A](msgF: F[Any])(implicit A: ClassTag[A], project: ProjectResource): F[Option[A]] =
    msgF.flatMap[Option[A]] {
      case Some(A(value)) => F.pure(Some(value))
      case None           => F.pure(None)
      case other          =>
        val msg =
          s"Received unexpected reply from the project view coordinator actor: '$other' for project '${project.value.show}'."
        F.raiseError(KgError.InternalError(msg))
    }

  private def parseSet[A](msgF: F[Any])(implicit SetA: ClassTag[Set[A]], project: ProjectResource): F[Set[A]] =
    msgF.flatMap[Set[A]] {
      case SetA(value) => F.pure(value)
      case other       =>
        val msg =
          s"Received unexpected reply from the project view coordinator actor: '$other' for project '${project.value.show}'."
        F.raiseError(KgError.InternalError(msg))
    }

  private def fetchAllStatistics(viewId: AbsoluteIri)(implicit project: ProjectResource): F[Set[IdStats]] = {
    val msgF = IO.fromFuture(IO(ref ? FetchStatistics(project.uuid, viewId))).to[F]
    parseSet[IdStats](msgF).recoverWith(logAndRaiseError(project.value.show, "statistics"))
  }

  private def fetchAllOffsets(
      viewId: AbsoluteIri
  )(implicit project: ProjectResource): F[Set[IdOffset]] = {
    val msgF = IO.fromFuture(IO(ref ? FetchOffset(project.uuid, viewId))).to[F]
    parseSet[IdOffset](msgF).recoverWith(logAndRaiseError(project.value.show, "progress"))
  }

  private def emptyToNone[A](set: Set[A]): Option[Set[A]] =
    if (set.isEmpty) None
    else Some(set)

  private def logAndRaiseError[A](label: String, action: String): PartialFunction[Throwable, F[A]] = {
    case _: AskTimeoutException =>
      F.raiseError(
        KgError
          .OperationTimedOut(s"Timeout when asking for $action to project view coordinator for project '$label'")
      )
    case NonFatal(th)           =>
      val msg = s"Exception caught while exchanging messages with the project view coordinator for project '$label'"
      log.error(msg, th)
      F.raiseError(KgError.InternalError(msg))
  }
}

object ProjectViewCoordinator {

  implicit val log: Logger = Logger[ProjectViewCoordinator.type]

  def apply(resources: Resources[Task], cache: Caches[Task], acls: Acls[Task], saCaller: Caller)(implicit
      config: AppConfig,
      as: ActorSystem,
      clients: Clients[Task],
      P: Projections[Task, String]
  ): Task[ProjectViewCoordinator[Task]] = {
    implicit val projectCache: ProjectCache[Task] = cache.project
    val ref                                       = ProjectViewCoordinatorActor.start(resources, cache.view, acls, saCaller, None, config.cluster.shards)
    apply(cache, acls, saCaller, ref)
  }

  private[async] def apply(
      cache: Caches[Task],
      acls: Acls[Task],
      saCaller: Caller,
      ref: ActorRef
  )(implicit
      config: AppConfig,
      as: ActorSystem,
      projectCache: ProjectCache[Task]
  ): Task[ProjectViewCoordinator[Task]] = {
    val coordinator = new ProjectViewCoordinator[Task](cache, acls, saCaller, ref)
    startAclsStream(coordinator, acls, saCaller) >>
      cache.project.subscribe(onDeprecated = project => coordinator.stop(ProjectRef(project.uuid))) >>
      cache.org.subscribe(onDeprecated = org => coordinator.stop(OrganizationRef(org.uuid))) >>
      Task.pure(coordinator)
  }

  private def startAclsStream(coordinator: ProjectViewCoordinator[Task], acls: Acls[Task], saCaller: Caller)(implicit
      config: AppConfig,
      as: ActorSystem,
      projectCache: ProjectCache[Task]
  ): Task[StreamSupervisor[Task, Unit]] = {
    implicit val aggc: AggregateConfig = config.aggregate
    implicit val timeout: Timeout      = aggc.askTimeout

    def handle(event: AclEvent): Task[Unit] =
      Task.pure(log.debug(s"Handling ACL event: '$event'")) >>
        (for {
          acls     <- acls.list(anyProject, ancestors = true, self = false)(saCaller)
          projects <- acls.value.keySet.toList.traverse(_.resolveProjects).map(_.flatten.distinct)
          _        <- projects.traverse(coordinator.changeAcls(acls, _))
        } yield ())
    val projectionId: String                = "acl-view-change"

    val source: Source[PairMsg[Any], _] = PersistenceQuery(as)
      .readJournalFor[EventsByTagQuery](aggc.queryJournalPlugin)
      .eventsByTag(TaggingAdapter.AclEventTag, NoOffset)
      .map[PairMsg[Any]](e => Right(Message(e, projectionId)))

    val flow = ProgressFlowElem[Task, Any]
      .collectCast[AclEvent]
      .mapAsync(handle)
      .flow
      .map(_ => ())

    Task.delay(StreamSupervisor.startSingleton[Task, Unit](Task.delay(source.via(flow)), projectionId))
  }

}
