package ch.epfl.bluebrain.nexus.delta.plugins.blazegraph

import akka.actor.typed.ActorSystem
import akka.persistence.query.Offset
import cats.effect.Clock
import cats.effect.concurrent.Deferred
import cats.syntax.all._
import ch.epfl.bluebrain.nexus.delta.kernel.RetryStrategy
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.BlazegraphViews._
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.model.BlazegraphViewCommand._
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.model.BlazegraphViewEvent._
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.model.BlazegraphViewRejection._
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.model.BlazegraphViewState._
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.model.BlazegraphViewValue._
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.model._
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.RemoteContextResolution
import ch.epfl.bluebrain.nexus.delta.sdk.cache.{CompositeKeyValueStore, KeyValueStoreConfig}
import ch.epfl.bluebrain.nexus.delta.sdk.eventlog.EventLogUtils
import ch.epfl.bluebrain.nexus.delta.sdk.jsonld.ExpandIri
import ch.epfl.bluebrain.nexus.delta.sdk.jsonld.JsonLdSourceProcessor.JsonLdSourceDecoder
import ch.epfl.bluebrain.nexus.delta.sdk.model.IdSegment.IriSegment
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Identity.Subject
import ch.epfl.bluebrain.nexus.delta.sdk.model.permissions.Permission
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.{Project, ProjectRef}
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.Pagination.FromPagination
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.ResultEntry.UnscoredResultEntry
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.SearchResults.UnscoredSearchResults
import ch.epfl.bluebrain.nexus.delta.sdk.model.{Envelope, IdSegment, Label, TagLabel}
import ch.epfl.bluebrain.nexus.delta.sdk.syntax._
import ch.epfl.bluebrain.nexus.delta.sdk.utils.{IOUtils, UUIDF}
import ch.epfl.bluebrain.nexus.delta.sdk.{Organizations, Permissions, Projects}
import ch.epfl.bluebrain.nexus.sourcing.SnapshotStrategy.NoSnapshot
import ch.epfl.bluebrain.nexus.sourcing.processor.EventSourceProcessor.persistenceId
import ch.epfl.bluebrain.nexus.sourcing.processor.ShardedAggregate
import ch.epfl.bluebrain.nexus.sourcing.projections.StreamSupervisor
import ch.epfl.bluebrain.nexus.sourcing.{Aggregate, EventLog, PersistentEventDefinition}
import com.typesafe.scalalogging.Logger
import fs2.Stream
import io.circe.Json
import monix.bio.{IO, Task, UIO}
import monix.execution.Scheduler

/**
  * Operations for handling Blazegraph views.
  */
final class BlazegraphViews(
    agg: BlazegraphViewsAggregate,
    eventLog: EventLog[Envelope[BlazegraphViewEvent]],
    index: BlazegraphViewsCache,
    projects: Projects,
    orgs: Organizations,
    sourceDecoder: JsonLdSourceDecoder[BlazegraphViewRejection, BlazegraphViewValue]
)(implicit rcr: RemoteContextResolution) {

  /**
    * Create a new Blazegraph view where the id is either present on the payload or self generated.
    *
    * @param project  the project of to which the view belongs
    * @param source   the payload to create the view
    */
  def create(project: ProjectRef, source: Json)(implicit
      subject: Subject
  ): IO[BlazegraphViewRejection, BlazegraphViewResource] = {
    for {
      p                <- projects.fetchActiveProject(project)
      (iri, viewValue) <- sourceDecoder(p, source)
      res              <- eval(CreateBlazegraphView(iri, project, viewValue, source, subject), p)
    } yield res
  }.named("createBlazegraphView", moduleType)

  /**
    * Create a new view with the provided id.
    *
    * @param id       the view identifier
    * @param project  the project to which the view belongs
    * @param source   the payload to create the view
    */
  def create(id: IdSegment, project: ProjectRef, source: Json)(implicit
      subject: Subject
  ): IO[BlazegraphViewRejection, BlazegraphViewResource] = {
    for {
      p         <- projects.fetchActiveProject(project)
      iri       <- expandIri(id, p)
      viewValue <- sourceDecoder(p, iri, source)
      res       <- eval(CreateBlazegraphView(iri, project, viewValue, source, subject), p)
    } yield res
  }.named("createBlazegraphView", moduleType)

  /**
    * Create a new view with the provided id and the [[BlazegraphViewValue]] instead of [[Json]] payload.
    * @param id       the view identifier
    * @param project  the project to which the view belongs
    * @param view     the value of the view
    */
  def create(id: IdSegment, project: ProjectRef, view: BlazegraphViewValue)(implicit
      subject: Subject
  ): IO[BlazegraphViewRejection, BlazegraphViewResource] = {
    for {
      p     <- projects.fetchActiveProject(project)
      iri   <- expandIri(id, p)
      source = view.toJson(iri)
      res   <- eval(CreateBlazegraphView(iri, project, view, source, subject), p)
    } yield res
  }.named("createBlazegraphView", moduleType)

  /**
    * Update an existing view with [[Json]] source.
    * @param id       the view identifier
    * @param project  the project to which the view belongs
    * @param rev      the current revision of the view
    * @param source   the view source
    */
  def update(id: IdSegment, project: ProjectRef, rev: Long, source: Json)(implicit
      subject: Subject
  ): IO[BlazegraphViewRejection, BlazegraphViewResource] = {
    for {
      p         <- projects.fetchActiveProject(project)
      iri       <- expandIri(id, p)
      viewValue <- sourceDecoder(p, iri, source)
      res       <- eval(UpdateBlazegraphView(iri, project, viewValue, rev, source, subject), p)
    } yield res
  }.named("updateBlazegraphView", moduleType)

  /**
    * Update an existing view.
    *
    * @param id       the identifier of the view
    * @param project  the project to which the view belongs
    * @param rev      the current revision of the view
    * @param view     the view value
    */
  def update(id: IdSegment, project: ProjectRef, rev: Long, view: BlazegraphViewValue)(implicit
      subject: Subject
  ): IO[BlazegraphViewRejection, BlazegraphViewResource] = {
    for {
      p     <- projects.fetchActiveProject(project)
      iri   <- expandIri(id, p)
      source = view.toJson(iri)
      res   <- eval(UpdateBlazegraphView(iri, project, view, rev, source, subject), p)
    } yield res
  }.named("updateBlazegraphView", moduleType)

  /**
    * Add a tag to an existing view.
    *
    * @param id       the id of the view
    * @param project  the project to which the view belongs
    * @param tag      the tag label
    * @param tagRev   the target revision of the tag
    * @param rev      the current revision of the view
    */
  def tag(
      id: IdSegment,
      project: ProjectRef,
      tag: TagLabel,
      tagRev: Long,
      rev: Long
  )(implicit subject: Subject): IO[BlazegraphViewRejection, BlazegraphViewResource] = {
    for {
      p   <- projects.fetchActiveProject(project)
      iri <- expandIri(id, p)
      res <- eval(TagBlazegraphView(iri, project, tagRev, tag, rev, subject), p)
    } yield res
  }.named("tagBlazegraphView", moduleType)

  /**
    * Deprecate a view.
    *
    * @param id       the view id
    * @param project  the project to which the view belongs
    * @param rev      the current revision of the view
    */
  def deprecate(
      id: IdSegment,
      project: ProjectRef,
      rev: Long
  )(implicit subject: Subject): IO[BlazegraphViewRejection, BlazegraphViewResource] = {
    for {
      p   <- projects.fetchActiveProject(project)
      iri <- expandIri(id, p)
      res <- eval(DeprecateBlazegraphView(iri, project, rev, subject), p)
    } yield res
  }.named("deprecateBlazegraphView", moduleType)

  /**
    * Fetch the latest revision of a view.
    *
    * @param id       the view id
    * @param project  the project to which the view belongs
    */
  def fetch(
      id: IdSegment,
      project: ProjectRef
  ): IO[BlazegraphViewRejection, BlazegraphViewResource] =
    fetch(id, project, None).named("fetchBlazegraphView", moduleType)

  /**
    * Fetch the view at a specific revision.
    *
    * @param id       the view id
    * @param project  the project to which the view belongs
    * @param rev      the revision to fetch
    */
  def fetchAt(
      id: IdSegment,
      project: ProjectRef,
      rev: Long
  ): IO[BlazegraphViewRejection, BlazegraphViewResource] =
    fetch(id, project, Some(rev)).named("fetchBlazegraphViewAt", moduleType)

  /**
    * Fetch view by tag.
    *
    * @param id       the view id
    * @param project  the project to which the view belongs
    * @param tag      the tag to fetch
    */
  def fetchBy(id: IdSegment, project: ProjectRef, tag: TagLabel): IO[BlazegraphViewRejection, BlazegraphViewResource] =
    fetch(id, project, None)
      .flatMap { resource =>
        resource.value.tags.get(tag) match {
          case Some(rev) => fetchAt(id, project, rev).leftMap(_ => TagNotFound(tag))
          case None      => IO.raiseError(TagNotFound(tag))
        }
      }
      .named("fetchBlazegraphViewBy", moduleType)

  /**
    * List views.
    *
    * @param pagination the pagination settings
    * @param params     filtering parameters for the listing
    * @param ordering   the response ordering
    */
  def list(
      pagination: FromPagination,
      params: BlazegraphViewSearchParams,
      ordering: Ordering[BlazegraphViewResource]
  ): UIO[UnscoredSearchResults[BlazegraphViewResource]] = index.values
    .map { resources =>
      val results = resources.filter(params.matches).sorted(ordering)
      UnscoredSearchResults(
        results.size.toLong,
        results.map(UnscoredResultEntry(_)).slice(pagination.from, pagination.from + pagination.size)
      )
    }
    .named("listBlazegraphViews", moduleType)

  /**
    * A non terminating stream of events for Blazegraph views. After emitting all known events it sleeps until new events.
    *
    * @param organization the organization to filter the events
    * @param offset       the last seen event offset; it will not be emitted by the stream
    */
  def events(
      organization: Label,
      offset: Offset
  ): IO[WrappedOrganizationRejection, Stream[Task, Envelope[BlazegraphViewEvent]]] = orgs
    .fetchOrganization(organization)
    .as(eventLog.eventsByTag(s"${Organizations.moduleType}=$organization", offset))

  /**
    * A non terminating stream of events for Blazegraph views. After emitting all known events it sleeps until new events.
    *
    * @param projectRef the project to filter the events
    * @param offset     the last seen event offset; it will not be emitted by the stream
    */
  def events(
      projectRef: ProjectRef,
      offset: Offset
  ): IO[BlazegraphViewRejection, Stream[Task, Envelope[BlazegraphViewEvent]]] = projects
    .fetchProject(projectRef)
    .as(eventLog.eventsByTag(s"${Projects.moduleType}=$projectRef", offset))

  /**
    * A non terminating stream of events for Blazegraph views. After emitting all known events it sleeps until new events.
    *
    * @param offset     the last seen event offset; it will not be emitted by the stream
    */
  def events(offset: Offset): Stream[Task, Envelope[BlazegraphViewEvent]] = eventLog.eventsByTag(moduleType, offset)

  private def eval(cmd: BlazegraphViewCommand, project: Project): IO[BlazegraphViewRejection, BlazegraphViewResource] =
    for {
      evaluationResult <- agg.evaluate(identifier(cmd.project, cmd.id), cmd).mapError(_.value)
      resourceOpt       = evaluationResult.state.toResource(project.apiMappings, project.base)
      res              <- IO.fromOption(resourceOpt, UnexpectedInitialState(cmd.id, project.ref))
      _                <- index.put(cmd.project, cmd.id, res)
    } yield res

  private def identifier(project: ProjectRef, id: Iri): String =
    s"${project}_$id"

  private def fetch(
      id: IdSegment,
      project: ProjectRef,
      rev: Option[Long]
  ): IO[BlazegraphViewRejection, BlazegraphViewResource] = for {
    p     <- projects.fetchProject(project)
    iri   <- expandIri(id, p)
    state <- rev.fold(currentState(project, iri))(stateAt(project, iri, _))
    res   <- IO.fromOption(state.toResource(p.apiMappings, p.base), ViewNotFound(iri, project))
  } yield res

  private def currentState(project: ProjectRef, iri: Iri): IO[BlazegraphViewRejection, BlazegraphViewState] =
    agg.state(identifier(project, iri))

  private def stateAt(project: ProjectRef, iri: Iri, rev: Long) =
    EventLogUtils
      .fetchStateAt(eventLog, persistenceId(moduleType, identifier(project, iri)), rev, Initial, next)
      .leftMap(RevisionNotFound(rev, _))

}

object BlazegraphViews {

  /**
    * The Blazegraph module type
    */
  final val moduleType: String = "blazegraph"

  private val logger: Logger = Logger[BlazegraphViews]

  val expandIri: ExpandIri[InvalidBlazegraphViewId] = new ExpandIri(InvalidBlazegraphViewId.apply)

  type ValidatePermission = Permission => IO[PermissionIsNotDefined, Unit]
  type ValidateRef        = ViewRef => IO[InvalidViewReference, Unit]

  type BlazegraphViewsAggregate =
    Aggregate[String, BlazegraphViewState, BlazegraphViewCommand, BlazegraphViewEvent, BlazegraphViewRejection]

  type BlazegraphViewsCache = CompositeKeyValueStore[ProjectRef, Iri, BlazegraphViewResource]

  private[blazegraph] def next(
      state: BlazegraphViewState,
      event: BlazegraphViewEvent
  ): BlazegraphViewState = {
    // format: off
    def created(e: BlazegraphViewCreated): BlazegraphViewState = state match {
      case Initial     => Current(e.id, e.project, e.uuid, e.value, e.source, Map.empty, e.rev, deprecated = false,  e.instant, e.subject, e.instant, e.subject)
      case s: Current  => s
    }

    def updated(e: BlazegraphViewUpdated): BlazegraphViewState = state match {
      case Initial    => Initial
      case s: Current => s.copy(rev = e.rev, value = e.value, source = e.source, updatedAt = e.instant, updatedBy = e.subject)
    }

    def tagAdded(e: BlazegraphViewTagAdded): BlazegraphViewState = state match {
      case Initial    => Initial
      case s: Current => s.copy(rev = e.rev, tags = s.tags + (e.tag -> e.targetRev), updatedAt = e.instant, updatedBy = e.subject)
    }
    // format: on

    def deprecated(e: BlazegraphViewDeprecated): BlazegraphViewState = state match {
      case Initial    => Initial
      case s: Current => s.copy(rev = e.rev, deprecated = true, updatedAt = e.instant, updatedBy = e.subject)
    }

    event match {
      case e: BlazegraphViewCreated    => created(e)
      case e: BlazegraphViewUpdated    => updated(e)
      case e: BlazegraphViewTagAdded   => tagAdded(e)
      case e: BlazegraphViewDeprecated => deprecated(e)
    }
  }

  private[blazegraph] def evaluate(
      validatePermission: ValidatePermission,
      validateRef: ValidateRef
  )(state: BlazegraphViewState, cmd: BlazegraphViewCommand)(implicit
      clock: Clock[UIO] = IO.clock,
      uuidF: UUIDF = UUIDF.random
  ): IO[BlazegraphViewRejection, BlazegraphViewEvent] = {

    def validate(value: BlazegraphViewValue): IO[BlazegraphViewRejection, Unit] =
      value match {
        case v: AggregateBlazegraphViewValue =>
          IO.parTraverseUnordered(v.views.toSortedSet)(validateRef).void
        case v: IndexingBlazegraphViewValue  =>
          for {
            _ <- validatePermission(v.permission)
          } yield ()
      }

    def create(c: CreateBlazegraphView) = state match {
      case Initial =>
        for {
          _ <- validate(c.value)
          t <- IOUtils.instant
          u <- uuidF()
        } yield BlazegraphViewCreated(c.id, c.project, u, c.value, c.source, 1L, t, c.subject)
      case _       => IO.raiseError(ViewAlreadyExists(c.id))
    }

    def update(c: UpdateBlazegraphView) = state match {
      case Initial                                  =>
        IO.raiseError(ViewNotFound(c.id, c.project))
      case s: Current if s.rev != c.rev             =>
        IO.raiseError(IncorrectRev(c.rev, s.rev))
      case s: Current if s.deprecated               =>
        IO.raiseError(ViewIsDeprecated(c.id))
      case s: Current if c.value.tpe != s.value.tpe =>
        IO.raiseError(DifferentBlazegraphViewType(s.id, c.value.tpe, s.value.tpe))
      case s: Current                               =>
        for {
          _ <- validate(c.value)
          t <- IOUtils.instant
        } yield BlazegraphViewUpdated(c.id, c.project, s.uuid, c.value, c.source, s.rev + 1L, t, c.subject)
    }

    def tag(c: TagBlazegraphView) = state match {
      case Initial                                                =>
        IO.raiseError(ViewNotFound(c.id, c.project))
      case s: Current if s.rev != c.rev                           =>
        IO.raiseError(IncorrectRev(c.rev, s.rev))
      case s: Current if s.deprecated                             =>
        IO.raiseError(ViewIsDeprecated(c.id))
      case s: Current if c.targetRev <= 0L || c.targetRev > s.rev =>
        IO.raiseError(RevisionNotFound(c.targetRev, s.rev))
      case s: Current                                             =>
        IOUtils.instant.map(
          BlazegraphViewTagAdded(c.id, c.project, s.uuid, c.targetRev, c.tag, s.rev + 1L, _, c.subject)
        )
    }

    def deprecate(c: DeprecateBlazegraphView) = state match {
      case Initial                      =>
        IO.raiseError(ViewNotFound(c.id, c.project))
      case s: Current if s.rev != c.rev =>
        IO.raiseError(IncorrectRev(c.rev, s.rev))
      case s: Current if s.deprecated   =>
        IO.raiseError(ViewIsDeprecated(c.id))
      case s: Current                   =>
        IOUtils.instant.map(BlazegraphViewDeprecated(c.id, c.project, s.uuid, s.rev + 1L, _, c.subject))
    }

    cmd match {
      case c: CreateBlazegraphView    => create(c)
      case c: UpdateBlazegraphView    => update(c)
      case c: TagBlazegraphView       => tag(c)
      case c: DeprecateBlazegraphView => deprecate(c)
    }
  }

  /**
    * Constructs a [[BlazegraphViews]] instance.
    *
    * @param config       the views configuration
    * @param eventLog     the [[EventLog]] instance for [[BlazegraphViewEvent]]
    * @param permissions  the permissions operations bundle
    * @param orgs         the organizations operations bundle
    * @param projects     the project operations bundle
    */
  def apply(
      config: BlazegraphViewsConfig,
      eventLog: EventLog[Envelope[BlazegraphViewEvent]],
      permissions: Permissions,
      orgs: Organizations,
      projects: Projects
  )(implicit
      uuidF: UUIDF,
      clock: Clock[UIO],
      scheduler: Scheduler,
      as: ActorSystem[Nothing],
      rcr: RemoteContextResolution
  ): UIO[BlazegraphViews] = {
    (for {
      validateRefDeferred <- Deferred[Task, ValidateRef]
      agg                 <- aggregate(config, validatePermissions(permissions), validateRefDeferred)
      index               <- UIO.delay(cache(config))
      sourceDecoder        = new JsonLdSourceDecoder[BlazegraphViewRejection, BlazegraphViewValue](contexts.blazegraph, uuidF)
      views                = new BlazegraphViews(agg, eventLog, index, projects, orgs, sourceDecoder)
      _                   <- validateRefDeferred.complete(validateRef(views))
      _                   <- UIO.delay(startIndexing(config, eventLog, index, views))
    } yield views).hideErrors

  }

  private def validatePermissions(permissions: Permissions): ValidatePermission = p =>
    permissions.fetchPermissionSet.flatMap { perms =>
      if (perms.contains(p)) IO.unit else IO.raiseError(PermissionIsNotDefined(p))
    }
  private def validateRef(views: BlazegraphViews): ValidateRef = { viewRef: ViewRef =>
    views
      .fetch(IriSegment(viewRef.viewId), viewRef.project)
      .leftMap(_ => InvalidViewReference(viewRef))
      .flatMap(view => if (view.deprecated) IO.raiseError(InvalidViewReference(viewRef)) else IO.unit)
  }

  private def aggregate(
      config: BlazegraphViewsConfig,
      validateP: ValidatePermission,
      validateRefDefferred: Deferred[Task, ValidateRef]
  )(implicit
      as: ActorSystem[Nothing],
      uuidF: UUIDF,
      clock: Clock[UIO]
  ) = {

    val validateRef: ValidateRef = viewRef => validateRefDefferred.get.hideErrors.flatMap { vRef => vRef(viewRef) }

    val definition = PersistentEventDefinition(
      entityType = moduleType,
      initialState = Initial,
      next = next,
      evaluate = evaluate(validateP, validateRef),
      tagger = (event: BlazegraphViewEvent) =>
        Set(
          moduleType,
          s"${Projects.moduleType}=${event.project}",
          s"${Organizations.moduleType}=${event.project.organization}"
        ),
      snapshotStrategy = NoSnapshot,
      stopStrategy = config.aggregate.stopStrategy.persistentStrategy
    )

    ShardedAggregate.persistentSharded(
      definition = definition,
      config = config.aggregate.processor,
      retryStrategy = RetryStrategy.alwaysGiveUp
      // TODO: configure the number of shards
    )
  }

  private def cache(config: BlazegraphViewsConfig)(implicit as: ActorSystem[Nothing]): BlazegraphViewsCache = {
    implicit val cfg: KeyValueStoreConfig             = config.keyValueStore
    val clock: (Long, BlazegraphViewResource) => Long = (_, resource) => resource.rev
    CompositeKeyValueStore(moduleType, clock)
  }
  private def startIndexing(
      config: BlazegraphViewsConfig,
      eventLog: EventLog[Envelope[BlazegraphViewEvent]],
      index: BlazegraphViewsCache,
      views: BlazegraphViews
  )(implicit as: ActorSystem[Nothing], sc: Scheduler)                           =
    StreamSupervisor.runAsSingleton(
      "BlazegraphViewsIndex",
      streamTask = Task.delay(
        eventLog
          .eventsByTag(moduleType, Offset.noOffset)
          .mapAsync(config.indexing.concurrency)(envelope =>
            views
              .fetch(IriSegment(envelope.event.id), envelope.event.project)
              .redeemCauseWith(_ => IO.unit, res => index.put(res.value.project, res.value.id, res))
          )
      ),
      retryStrategy = RetryStrategy(
        config.indexing.retry,
        _ => true,
        RetryStrategy.logError(logger, "Blazegraph views indexing")
      )
    )

}
