package ch.epfl.bluebrain.nexus.delta.plugins.blazegraph

import akka.actor.typed.ActorSystem
import akka.persistence.query.Offset
import cats.effect.Clock
import cats.effect.concurrent.Deferred
import cats.implicits._
import ch.epfl.bluebrain.nexus.delta.kernel.utils.{IOUtils, UUIDF}
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.BlazegraphViews.{BlazegraphViewsCache, _}
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.client.BlazegraphClient
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.indexing.{BlazegraphIndexingStreamEntry, BlazegraphViewsIndexing}
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.model.BlazegraphView.{AggregateBlazegraphView, IndexingBlazegraphView}
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.model.BlazegraphViewCommand._
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.model.BlazegraphViewEvent._
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.model.BlazegraphViewRejection._
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.model.BlazegraphViewState._
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.model.BlazegraphViewType.{AggregateBlazegraphView => AggregateBlazegraphViewType, IndexingBlazegraphView => IndexingBlazegraphViewType}
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.model.BlazegraphViewValue._
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.model._
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.RemoteContextResolution
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.encoder.JsonLdEncoder
import ch.epfl.bluebrain.nexus.delta.sdk.EventExchange.EventExchangeValue
import ch.epfl.bluebrain.nexus.delta.sdk.ReferenceExchange.ReferenceExchangeValue
import ch.epfl.bluebrain.nexus.delta.sdk.ResourceIdCheck.IdAvailability
import ch.epfl.bluebrain.nexus.delta.sdk._
import ch.epfl.bluebrain.nexus.delta.sdk.cache.{CompositeKeyValueStore, KeyValueStoreConfig}
import ch.epfl.bluebrain.nexus.delta.sdk.error.ServiceError.{IndexingActionFailed, IndexingFailed}
import ch.epfl.bluebrain.nexus.delta.sdk.jsonld.ExpandIri
import ch.epfl.bluebrain.nexus.delta.sdk.jsonld.JsonLdSourceProcessor.JsonLdSourceResolvingDecoder
import ch.epfl.bluebrain.nexus.delta.sdk.model._
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Caller
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Identity.Subject
import ch.epfl.bluebrain.nexus.delta.sdk.model.permissions.Permission
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.{ApiMappings, Project, ProjectRef}
import ch.epfl.bluebrain.nexus.delta.sdk.model.resolvers.ResolverContextResolution
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.Pagination.FromPagination
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.ResultEntry.UnscoredResultEntry
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.SearchResults.UnscoredSearchResults
import ch.epfl.bluebrain.nexus.delta.sdk.syntax._
import ch.epfl.bluebrain.nexus.delta.sdk.views.ViewRefVisitor.VisitedView
import ch.epfl.bluebrain.nexus.delta.sdk.views.model.ViewRef
import ch.epfl.bluebrain.nexus.delta.sourcing.SnapshotStrategy.NoSnapshot
import ch.epfl.bluebrain.nexus.delta.sourcing.config.ExternalIndexingConfig
import ch.epfl.bluebrain.nexus.delta.sourcing.processor.EventSourceProcessor.persistenceId
import ch.epfl.bluebrain.nexus.delta.sourcing.processor.ShardedAggregate
import ch.epfl.bluebrain.nexus.delta.sourcing.projections.ProjectionId.ViewProjectionId
import ch.epfl.bluebrain.nexus.delta.sourcing.{Aggregate, EventLog, PersistentEventDefinition}
import fs2.Stream
import io.circe.Json
import monix.bio.{IO, Task, UIO}
import monix.execution.Scheduler

import java.util.UUID

/**
  * Operations for handling Blazegraph views.
  */
final class BlazegraphViews(
    agg: BlazegraphViewsAggregate,
    eventLog: EventLog[Envelope[BlazegraphViewEvent]],
    index: BlazegraphViewsCache,
    projects: Projects,
    orgs: Organizations,
    sourceDecoder: JsonLdSourceResolvingDecoder[BlazegraphViewRejection, BlazegraphViewValue],
    createNamespace: ViewResource => IO[BlazegraphViewRejection, Unit],
    indexingAction: IndexingAction
) {

  /**
    * Create a new Blazegraph view where the id is either present on the payload or self generated.
    *
    * @param project        the project of to which the view belongs
    * @param source         the payload to create the view
    * @param indexing  the type of indexing for this action
    */
  def create(project: ProjectRef, source: Json, indexing: Indexing)(implicit
      caller: Caller
  ): IO[BlazegraphViewRejection, ViewResource] = {
    for {
      p                <- projects.fetchActiveProject(project)
      (iri, viewValue) <- sourceDecoder(p, source)
      res              <- eval(CreateBlazegraphView(iri, project, viewValue, source, caller.subject), p)
      _                <- createNamespace(res)
      _                <- indexingAction(project, eventExchangeValue(res), indexing)

    } yield res
  }.named("createBlazegraphView", moduleType)

  /**
    * Create a new view with the provided id.
    *
    * @param id       the view identifier
    * @param project  the project to which the view belongs
    * @param source   the payload to create the view
    * @param indexing the type of indexing for this action
    */
  def create(
      id: IdSegment,
      project: ProjectRef,
      source: Json,
      indexing: Indexing
  )(implicit caller: Caller): IO[BlazegraphViewRejection, ViewResource] = {
    for {
      p         <- projects.fetchActiveProject(project)
      iri       <- expandIri(id, p)
      viewValue <- sourceDecoder(p, iri, source)
      res       <- eval(CreateBlazegraphView(iri, project, viewValue, source, caller.subject), p)
      _         <- createNamespace(res)
      _         <- indexingAction(project, eventExchangeValue(res), indexing)

    } yield res
  }.named("createBlazegraphView", moduleType)

  /**
    * Create a new view with the provided id and the [[BlazegraphViewValue]] instead of [[Json]] payload.
    * @param id       the view identifier
    * @param project  the project to which the view belongs
    * @param view     the value of the view
    * @param indexing the type of indexing for this action
    */
  def create(id: IdSegment, project: ProjectRef, view: BlazegraphViewValue, indexing: Indexing)(implicit
      subject: Subject
  ): IO[BlazegraphViewRejection, ViewResource] = {
    for {
      p     <- projects.fetchActiveProject(project)
      iri   <- expandIri(id, p)
      source = view.toJson(iri)
      res   <- eval(CreateBlazegraphView(iri, project, view, source, subject), p)
      _     <- createNamespace(res)
      _     <- indexingAction(project, eventExchangeValue(res), indexing)

    } yield res
  }.named("createBlazegraphView", moduleType)

  /**
    * Update an existing view with [[Json]] source.
    * @param id       the view identifier
    * @param project  the project to which the view belongs
    * @param rev      the current revision of the view
    * @param source   the view source
    * @param indexing the type of indexing for this action
    */
  def update(
      id: IdSegment,
      project: ProjectRef,
      rev: Long,
      source: Json,
      indexing: Indexing
  )(implicit caller: Caller): IO[BlazegraphViewRejection, ViewResource] = {
    for {
      p         <- projects.fetchActiveProject(project)
      iri       <- expandIri(id, p)
      viewValue <- sourceDecoder(p, iri, source)
      res       <- eval(UpdateBlazegraphView(iri, project, viewValue, rev, source, caller.subject), p)
      _         <- indexingAction(project, eventExchangeValue(res), indexing)
    } yield res
  }.named("updateBlazegraphView", moduleType)

  /**
    * Update an existing view.
    *
    * @param id       the identifier of the view
    * @param project  the project to which the view belongs
    * @param rev      the current revision of the view
    * @param view     the view value
    * @param indexing the type of indexing for this action
    */
  def update(id: IdSegment, project: ProjectRef, rev: Long, view: BlazegraphViewValue, indexing: Indexing)(implicit
      subject: Subject
  ): IO[BlazegraphViewRejection, ViewResource] = {
    for {
      p     <- projects.fetchActiveProject(project)
      iri   <- expandIri(id, p)
      source = view.toJson(iri)
      res   <- eval(UpdateBlazegraphView(iri, project, view, rev, source, subject), p)
      _     <- indexingAction(project, eventExchangeValue(res), indexing)

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
    * @param indexing the type of indexing for this action
    */
  def tag(
      id: IdSegment,
      project: ProjectRef,
      tag: TagLabel,
      tagRev: Long,
      rev: Long,
      indexing: Indexing
  )(implicit subject: Subject): IO[BlazegraphViewRejection, ViewResource] = {
    for {
      p   <- projects.fetchActiveProject(project)
      iri <- expandIri(id, p)
      res <- eval(TagBlazegraphView(iri, project, tagRev, tag, rev, subject), p)
      _   <- indexingAction(project, eventExchangeValue(res), indexing)

    } yield res
  }.named("tagBlazegraphView", moduleType)

  /**
    * Deprecate a view.
    *
    * @param id       the view id
    * @param project  the project to which the view belongs
    * @param rev      the current revision of the view
    * @param indexing the type of indexing for this action
    */
  def deprecate(
      id: IdSegment,
      project: ProjectRef,
      rev: Long,
      indexing: Indexing
  )(implicit subject: Subject): IO[BlazegraphViewRejection, ViewResource] = {
    for {
      p   <- projects.fetchActiveProject(project)
      iri <- expandIri(id, p)
      res <- eval(DeprecateBlazegraphView(iri, project, rev, subject), p)
      _   <- indexingAction(project, eventExchangeValue(res), indexing)
    } yield res
  }.named("deprecateBlazegraphView", moduleType)

  /**
    * Fetch the latest revision of a view.
    *
    * @param id      the identifier that will be expanded to the Iri of the view with its optional rev/tag
    * @param project the project to which the view belongs
    */
  def fetch(
      id: IdSegmentRef,
      project: ProjectRef
  ): IO[BlazegraphViewRejection, ViewResource] =
    id.asTag
      .fold(
        for {
          p     <- projects.fetchProject(project)
          iri   <- expandIri(id.value, p)
          state <- id.asRev.fold(currentState(project, iri))(id => stateAt(project, iri, id.rev))
          res   <- IO.fromOption(state.toResource(p.apiMappings, p.base), ViewNotFound(iri, project))
        } yield res
      )(fetchBy(_, project))
      .named("fetchBlazegraphView", moduleType)

  /**
    * Retrieves a current [[IndexingBlazegraphView]] resource.
    *
    * @param id      the identifier that will be expanded to the Iri of the view with its optional rev/tag
    * @param project the view parent project
    */
  def fetchIndexingView(
      id: IdSegmentRef,
      project: ProjectRef
  ): IO[BlazegraphViewRejection, IndexingViewResource] =
    fetch(id, project).flatMap { res =>
      res.value match {
        case v: IndexingBlazegraphView  =>
          IO.pure(res.as(v))
        case _: AggregateBlazegraphView =>
          IO.raiseError(DifferentBlazegraphViewType(res.id, AggregateBlazegraphViewType, IndexingBlazegraphViewType))
      }
    }

  private def fetchBy(id: IdSegmentRef.Tag, project: ProjectRef): IO[BlazegraphViewRejection, ViewResource] =
    fetch(id.toLatest, project).flatMap { view =>
      view.value.tags.get(id.tag) match {
        case Some(rev) => fetch(id.toRev(rev), project).mapError(_ => TagNotFound(id.tag))
        case None      => IO.raiseError(TagNotFound(id.tag))
      }
    }

  private def eventExchangeValue(res: ViewResource)(implicit enc: JsonLdEncoder[BlazegraphView]) =
    EventExchangeValue(ReferenceExchangeValue(res, res.value.source, enc), JsonLdValue(res.value.metadata))

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
      ordering: Ordering[ViewResource]
  ): UIO[UnscoredSearchResults[ViewResource]] = index.values
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
  ): IO[WrappedOrganizationRejection, Stream[Task, Envelope[BlazegraphViewEvent]]] =
    eventLog.orgEvents(orgs, organization, offset)

  /**
    * A non terminating stream of events for Blazegraph views. After emitting all known events it sleeps until new events.
    *
    * @param projectRef the project to filter the events
    * @param offset     the last seen event offset; it will not be emitted by the stream
    */
  def events(
      projectRef: ProjectRef,
      offset: Offset
  ): IO[BlazegraphViewRejection, Stream[Task, Envelope[BlazegraphViewEvent]]] =
    eventLog.projectEvents(projects, projectRef, offset)

  /**
    * A non terminating stream of events for Blazegraph views. After emitting all known events it sleeps until new events.
    *
    * @param offset     the last seen event offset; it will not be emitted by the stream
    */
  def events(offset: Offset): Stream[Task, Envelope[BlazegraphViewEvent]] =
    eventLog.eventsByTag(moduleType, offset)

  private def eval(cmd: BlazegraphViewCommand, project: Project): IO[BlazegraphViewRejection, ViewResource] =
    for {
      evaluationResult <- agg.evaluate(identifier(cmd.project, cmd.id), cmd).mapError(_.value)
      resourceOpt       = evaluationResult.state.toResource(project.apiMappings, project.base)
      res              <- IO.fromOption(resourceOpt, UnexpectedInitialState(cmd.id, project.ref))
      _                <- index.put(cmd.project, cmd.id, res)
    } yield res

  private def identifier(project: ProjectRef, id: Iri): String =
    s"${project}_$id"

  private def currentState(project: ProjectRef, iri: Iri): IO[BlazegraphViewRejection, BlazegraphViewState] =
    agg.state(identifier(project, iri))

  private def stateAt(project: ProjectRef, iri: Iri, rev: Long) =
    eventLog
      .fetchStateAt(persistenceId(moduleType, identifier(project, iri)), rev, Initial, next)
      .mapError(RevisionNotFound(rev, _))

}

object BlazegraphViews {

  /**
    * The Blazegraph module type
    */
  val moduleType: String = "blazegraph"

  /**
    * The views module tag.
    */
  val moduleTag = "view"

  val expandIri: ExpandIri[InvalidBlazegraphViewId] = new ExpandIri(InvalidBlazegraphViewId.apply)

  /**
    * Constructs a projectionId for a blazegraph view
    */
  def projectionId(view: IndexingViewResource): ViewProjectionId =
    projectionId(view.value.uuid, view.rev)

  /**
    * Constructs a projectionId for a blazegraph view
    */
  def projectionId(uuid: UUID, rev: Long): ViewProjectionId =
    ViewProjectionId(s"$moduleType-${uuid}_$rev")

  /**
    * Constructs the namespace for a Blazegraph view
    */
  def namespace(view: IndexingViewResource, config: ExternalIndexingConfig): String =
    namespace(view.value.uuid, view.rev, config)

  /**
    * Constructs the namespace for a Blazegraph view
    */
  def namespace(uuid: UUID, rev: Long, config: ExternalIndexingConfig): String =
    s"${config.prefix}_${uuid}_$rev"

  /**
    * The default Blazegraph API mappings
    */
  val mappings: ApiMappings = ApiMappings("view" -> schema.original, "graph" -> defaultViewId)

  type ValidatePermission = Permission => IO[PermissionIsNotDefined, Unit]
  type ValidateRef        = ViewRef => IO[InvalidViewReference, Unit]
  type ViewRefResolution  = NonEmptySet[ViewRef] => IO[BlazegraphViewRejection, Set[VisitedView]]

  type BlazegraphViewsAggregate =
    Aggregate[String, BlazegraphViewState, BlazegraphViewCommand, BlazegraphViewEvent, BlazegraphViewRejection]

  type BlazegraphViewsCache = CompositeKeyValueStore[ProjectRef, Iri, ViewResource]

  /**
    * Create a reference exchange from a [[BlazegraphViews]] instance
    */
  def referenceExchange(views: BlazegraphViews): ReferenceExchange = {
    val fetch = (ref: ResourceRef, projectRef: ProjectRef) => views.fetch(ref.toIdSegmentRef, projectRef)
    ReferenceExchange[BlazegraphView](fetch(_, _), _.source)
  }

  def indexingAction(
      client: BlazegraphClient,
      cache: BlazegraphViewsCache,
      indexingConfig: ExternalIndexingConfig
  )(implicit cr: RemoteContextResolution, baseUri: BaseUri): IndexingAction = {
    new IndexingAction {
      override def execute(
          project: ProjectRef,
          res: EventExchangeValue[_, _]
      ): IO[IndexingActionFailed, Unit] = {
        (for {
          projectViews <- cache.get(project).map { vs =>
                            vs.filter(v => v.value.tpe == IndexingBlazegraphViewType && !v.deprecated)
                              .map(_.map(_.asInstanceOf[IndexingBlazegraphView]))
                          }
          streamEntry  <- BlazegraphIndexingStreamEntry.fromEventExchange(res)
          queries      <- projectViews
                            .traverse { v =>
                              streamEntry
                                .writeOrNone(v.value)
                                .map(_.map(q => (BlazegraphViews.namespace(v, indexingConfig), q)))
                            }
                            .map(_.flatten)
          _            <- queries.parTraverse { case (index, query) =>
                            client.bulk(index, Seq(query))
                          }
        } yield ()).mapError(err => IndexingFailed(err.getMessage, res.value.resource.void))
      }
    }
  }

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
      validateRef: ValidateRef,
      viewRefResolution: ViewRefResolution,
      idAvailability: IdAvailability[ResourceAlreadyExists],
      maxViewRefs: Int
  )(state: BlazegraphViewState, cmd: BlazegraphViewCommand)(implicit
      clock: Clock[UIO],
      uuidF: UUIDF
  ): IO[BlazegraphViewRejection, BlazegraphViewEvent] = {

    def validate(value: BlazegraphViewValue): IO[BlazegraphViewRejection, Unit] =
      value match {
        case v: AggregateBlazegraphViewValue =>
          for {
            _               <- IO.parTraverseUnordered(v.views.value)(validateRef).void
            refs            <- viewRefResolution(v.views)
            indexedRefsCount = refs.count(_.isIndexed)
            _               <- IO.raiseWhen(indexedRefsCount > maxViewRefs)(TooManyViewReferences(indexedRefsCount, maxViewRefs))
          } yield ()
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
          _ <- idAvailability(c.project, c.id)
        } yield BlazegraphViewCreated(c.id, c.project, u, c.value, c.source, 1L, t, c.subject)
      case _       => IO.raiseError(ResourceAlreadyExists(c.id, c.project))
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
          BlazegraphViewTagAdded(c.id, c.project, s.value.tpe, s.uuid, c.targetRev, c.tag, s.rev + 1L, _, c.subject)
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
        IOUtils.instant.map(BlazegraphViewDeprecated(c.id, c.project, s.value.tpe, s.uuid, s.rev + 1L, _, c.subject))
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
    */
  def apply(
      config: BlazegraphViewsConfig,
      eventLog: EventLog[Envelope[BlazegraphViewEvent]],
      contextResolution: ResolverContextResolution,
      permissions: Permissions,
      cache: BlazegraphViewsCache,
      orgs: Organizations,
      projects: Projects,
      resourceIdCheck: ResourceIdCheck,
      client: BlazegraphClient,
      indexingAction: IndexingAction
  )(implicit
      uuidF: UUIDF,
      clock: Clock[UIO],
      scheduler: Scheduler,
      as: ActorSystem[Nothing]
  ): Task[BlazegraphViews] = {
    val idAvailability: IdAvailability[ResourceAlreadyExists] = (project, id) =>
      resourceIdCheck.isAvailableOr(project, id)(ResourceAlreadyExists(id, project))
    val createNameSpace                                       = (v: ViewResource) =>
      v.value match {
        case i: IndexingBlazegraphView =>
          client
            .createNamespace(BlazegraphViews.namespace(v.as(i), config.indexing))
            .mapError(WrappedBlazegraphClientError.apply)
            .void
        case _                         => IO.unit
      }
    apply(
      config,
      eventLog,
      contextResolution,
      permissions,
      cache,
      orgs,
      projects,
      idAvailability,
      createNameSpace,
      indexingAction
    )
  }

  private[blazegraph] def apply(
      config: BlazegraphViewsConfig,
      eventLog: EventLog[Envelope[BlazegraphViewEvent]],
      contextResolution: ResolverContextResolution,
      permissions: Permissions,
      cache: BlazegraphViewsCache,
      orgs: Organizations,
      projects: Projects,
      idAvailability: IdAvailability[ResourceAlreadyExists],
      createNamespace: ViewResource => IO[BlazegraphViewRejection, Unit],
      indexingAction: IndexingAction
  )(implicit
      uuidF: UUIDF,
      clock: Clock[UIO],
      scheduler: Scheduler,
      as: ActorSystem[Nothing]
  ): Task[BlazegraphViews] = {
    def viewResolution(deferred: Deferred[Task, BlazegraphViews]): ViewRefResolution = { viewRefs =>
      deferred.get.hideErrors.flatMap { views =>
        BlazegraphViewRefVisitor(views, config.indexing).visitAll(viewRefs)
      }
    }

    for {
      deferred     <- Deferred[Task, BlazegraphViews]
      agg          <- aggregate(
                        config,
                        validatePermissions(permissions),
                        viewResolution(deferred),
                        idAvailability,
                        validateRef(deferred)
                      )
      sourceDecoder = new JsonLdSourceResolvingDecoder[BlazegraphViewRejection, BlazegraphViewValue](
                        contexts.blazegraph,
                        contextResolution,
                        uuidF
                      )
      views         = new BlazegraphViews(agg, eventLog, cache, projects, orgs, sourceDecoder, createNamespace, indexingAction)
      _            <- deferred.complete(views)
      _            <- BlazegraphViewsIndexing.populateCache(config.cacheIndexing.retry, views, cache)
    } yield views
  }

  private def validatePermissions(permissions: Permissions): ValidatePermission = p =>
    permissions.fetchPermissionSet.flatMap { perms =>
      IO.when(!perms.contains(p))(IO.raiseError(PermissionIsNotDefined(p)))
    }

  private def validateRef(deferred: Deferred[Task, BlazegraphViews]): ValidateRef = { viewRef: ViewRef =>
    deferred.get.hideErrors.flatMap { views =>
      views
        .fetch(viewRef.viewId, viewRef.project)
        .mapError(_ => InvalidViewReference(viewRef))
        .flatMap(view => IO.when(view.deprecated)(IO.raiseError(InvalidViewReference(viewRef))))
    }
  }

  private def aggregate(
      config: BlazegraphViewsConfig,
      validateP: ValidatePermission,
      viewResolution: ViewRefResolution,
      idAvailability: IdAvailability[ResourceAlreadyExists],
      validateRef: ValidateRef
  )(implicit
      as: ActorSystem[Nothing],
      uuidF: UUIDF,
      clock: Clock[UIO]
  ) = {

    val definition = PersistentEventDefinition(
      entityType = moduleType,
      initialState = Initial,
      next = next,
      evaluate = evaluate(validateP, validateRef, viewResolution, idAvailability, config.maxViewRefs),
      tagger = EventTags.forProjectScopedEvent(moduleTag, moduleType),
      snapshotStrategy = NoSnapshot,
      stopStrategy = config.aggregate.stopStrategy.persistentStrategy
    )

    ShardedAggregate.persistentSharded(
      definition = definition,
      config = config.aggregate.processor
    )
  }

  private[blazegraph] def cache(
      config: BlazegraphViewsConfig
  )(implicit as: ActorSystem[Nothing]): BlazegraphViewsCache = {
    implicit val cfg: KeyValueStoreConfig   = config.keyValueStore
    val clock: (Long, ViewResource) => Long = (_, resource) => resource.rev
    CompositeKeyValueStore(moduleType, clock)
  }
}
