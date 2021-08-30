package ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch

import akka.actor.typed.ActorSystem
import akka.persistence.query.Offset
import cats.effect.Clock
import cats.effect.concurrent.Deferred
import cats.implicits._
import ch.epfl.bluebrain.nexus.delta.kernel.utils.{IOUtils, UUIDF}
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.ElasticSearchViews._
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.client.{ElasticSearchClient, IndexLabel}
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.config.ElasticSearchViewsConfig
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.indexing.ElasticSearchViewsIndexing
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.model.ElasticSearchView.{AggregateElasticSearchView, IndexingElasticSearchView}
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.model.ElasticSearchViewCommand._
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.model.ElasticSearchViewEvent._
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.model.ElasticSearchViewRejection._
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.model.ElasticSearchViewState.{Current, Initial}
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.model.ElasticSearchViewType.{AggregateElasticSearch => ElasticSearchAggregate, ElasticSearch => ElasticSearchIndexing}
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.model.ElasticSearchViewValue._
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.model._
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.encoder.JsonLdEncoder
import ch.epfl.bluebrain.nexus.delta.sdk.EventExchange.EventExchangeValue
import ch.epfl.bluebrain.nexus.delta.sdk.ProjectReferenceFinder.ProjectReferenceMap
import ch.epfl.bluebrain.nexus.delta.sdk.ReferenceExchange.ReferenceExchangeValue
import ch.epfl.bluebrain.nexus.delta.sdk.ResourceIdCheck.IdAvailability
import ch.epfl.bluebrain.nexus.delta.sdk._
import ch.epfl.bluebrain.nexus.delta.sdk.cache.{CompositeKeyValueStore, KeyValueStoreConfig}
import ch.epfl.bluebrain.nexus.delta.sdk.http.HttpClientError.HttpClientStatusError
import ch.epfl.bluebrain.nexus.delta.sdk.jsonld.ExpandIri
import ch.epfl.bluebrain.nexus.delta.sdk.model._
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Caller
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Identity.Subject
import ch.epfl.bluebrain.nexus.delta.sdk.model.permissions.Permission
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.ProjectFetchOptions._
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.{ApiMappings, Project, ProjectFetchOptions, ProjectRef}
import ch.epfl.bluebrain.nexus.delta.sdk.model.resolvers.ResolverContextResolution
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.Pagination.{FromPagination, OnePage}
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.ResultEntry.UnscoredResultEntry
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.SearchResults.UnscoredSearchResults
import ch.epfl.bluebrain.nexus.delta.sdk.syntax._
import ch.epfl.bluebrain.nexus.delta.sdk.views.ViewRefVisitor.VisitedView
import ch.epfl.bluebrain.nexus.delta.sdk.views.model.ViewRef
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
  * ElasticSearchViews resource lifecycle operations.
  */
final class ElasticSearchViews private (
    aggregate: ElasticSearchViewAggregate,
    eventLog: EventLog[Envelope[ElasticSearchViewEvent]],
    cache: ElasticSearchViewCache,
    orgs: Organizations,
    projects: Projects,
    sourceDecoder: ElasticSearchViewJsonLdSourceDecoder
)(implicit uuidF: UUIDF) {

  /**
    * Creates a new ElasticSearchView with a generated id.
    *
    * @param project the parent project of the view
    * @param value   the view configuration
    * @param subject the subject that initiated the action
    */
  def create(
      project: ProjectRef,
      value: ElasticSearchViewValue
  )(implicit subject: Subject): IO[ElasticSearchViewRejection, ViewResource] =
    uuidF().flatMap(uuid => create(uuid.toString, project, value))

  /**
    * Creates a new ElasticSearchView with a provided id.
    *
    * @param id      the id of the view either in Iri or aliased form
    * @param project the parent project of the view
    * @param value   the view configuration
    * @param subject the subject that initiated the action
    */
  def create(
      id: IdSegment,
      project: ProjectRef,
      value: ElasticSearchViewValue
  )(implicit subject: Subject): IO[ElasticSearchViewRejection, ViewResource] = {
    for {
      p   <- projects.fetchProject(project, notDeprecatedOrDeletedWithQuotas)
      iri <- expandIri(id, p)
      res <- eval(CreateElasticSearchView(iri, project, value, value.toJson(iri), subject), p)
    } yield res
  }.named("createElasticSearchView", moduleType)

  /**
    * Creates a new ElasticSearchView from a json representation. If an identifier exists in the provided json it will
    * be used; otherwise a new identifier will be generated.
    *
    * @param project the parent project of the view
    * @param source  the json representation of the view
    * @param caller  the caller that initiated the action
    */
  def create(
      project: ProjectRef,
      source: Json
  )(implicit caller: Caller): IO[ElasticSearchViewRejection, ViewResource] = {
    for {
      p            <- projects.fetchProject(project, notDeprecatedOrDeletedWithQuotas)
      (iri, value) <- sourceDecoder(p, source)
      res          <- eval(CreateElasticSearchView(iri, project, value, source, caller.subject), p)
    } yield res
  }.named("createElasticSearchView", moduleType)

  /**
    * Creates a new ElasticSearchView from a json representation. If an identifier exists in the provided json it will
    * be used as long as it matches the provided id in Iri form or as an alias; otherwise the action will be rejected.
    *
    * @param project the parent project of the view
    * @param source  the json representation of the view
    * @param caller  the caller that initiated the action
    */
  def create(
      id: IdSegment,
      project: ProjectRef,
      source: Json
  )(implicit caller: Caller): IO[ElasticSearchViewRejection, ViewResource] = {
    for {
      p     <- projects.fetchProject(project, notDeprecatedOrDeletedWithQuotas)
      iri   <- expandIri(id, p)
      value <- sourceDecoder(p, iri, source)
      res   <- eval(CreateElasticSearchView(iri, project, value, source, caller.subject), p)
    } yield res
  }.named("createElasticSearchView", moduleType)

  /**
    * Updates an existing ElasticSearchView.
    *
    * @param id      the view identifier
    * @param project the view parent project
    * @param rev     the current view revision
    * @param value   the new view configuration
    * @param subject the subject that initiated the action
    */
  def update(
      id: IdSegment,
      project: ProjectRef,
      rev: Long,
      value: ElasticSearchViewValue
  )(implicit subject: Subject): IO[ElasticSearchViewRejection, ViewResource] = {
    for {
      p   <- projects.fetchProject(project, notDeprecatedOrDeletedWithEventQuotas)
      iri <- expandIri(id, p)
      res <- eval(UpdateElasticSearchView(iri, project, rev, value, value.toJson(iri), subject), p)
    } yield res
  }.named("updateElasticSearchView", moduleType)

  /**
    * Updates an existing ElasticSearchView.
    *
    * @param id      the view identifier
    * @param project the view parent project
    * @param rev     the current view revision
    * @param source  the new view configuration in json representation
    * @param caller  the caller that initiated the action
    */
  def update(
      id: IdSegment,
      project: ProjectRef,
      rev: Long,
      source: Json
  )(implicit caller: Caller): IO[ElasticSearchViewRejection, ViewResource] = {
    for {
      p     <- projects.fetchProject(project, notDeprecatedOrDeletedWithEventQuotas)
      iri   <- expandIri(id, p)
      value <- sourceDecoder(p, iri, source)
      res   <- eval(UpdateElasticSearchView(iri, project, rev, value, source, caller.subject), p)
    } yield res
  }.named("updateElasticSearchView", moduleType)

  /**
    * Applies a tag to an existing ElasticSearchView revision.
    *
    * @param id      the view identifier
    * @param project the view parent project
    * @param tag     the tag to apply
    * @param tagRev  the target revision of the tag
    * @param rev     the current view revision
    * @param subject the subject that initiated the action
    */
  def tag(
      id: IdSegment,
      project: ProjectRef,
      tag: TagLabel,
      tagRev: Long,
      rev: Long
  )(implicit subject: Subject): IO[ElasticSearchViewRejection, ViewResource] = {
    for {
      p   <- projects.fetchProject(project, notDeprecatedOrDeletedWithEventQuotas)
      iri <- expandIri(id, p)
      res <- eval(TagElasticSearchView(iri, project, tagRev, tag, rev, subject), p)
    } yield res
  }.named("tagElasticSearchView", moduleType)

  /**
    * Deprecates an existing ElasticSearchView. View deprecation implies blocking any query capabilities and in case of
    * an IndexingElasticSearchView the corresponding index is deleted.
    *
    * @param id      the view identifier
    * @param project the view parent project
    * @param rev     the current view revision
    * @param subject the subject that initiated the action
    */
  def deprecate(
      id: IdSegment,
      project: ProjectRef,
      rev: Long
  )(implicit subject: Subject): IO[ElasticSearchViewRejection, ViewResource] =
    deprecate(id, project, notDeprecatedOrDeletedWithEventQuotas, rev)

  /**
    * Deprecate a view without any extra checks on the projects API.
    * @see [[deprecate(id, project, rev)]]
    */
  private[elasticsearch] def deprecateWithoutProjectChecks(
      id: IdSegment,
      project: ProjectRef,
      rev: Long
  )(implicit subject: Subject): IO[ElasticSearchViewRejection, ViewResource] =
    deprecate(id, project, Set.empty, rev)

  private def deprecate(
      id: IdSegment,
      project: ProjectRef,
      projectFetchOptions: Set[ProjectFetchOptions],
      rev: Long
  )(implicit subject: Subject): IO[ElasticSearchViewRejection, ViewResource] = {
    for {
      p   <- projects.fetchProject(project, projectFetchOptions)
      iri <- expandIri(id, p)
      res <- eval(DeprecateElasticSearchView(iri, project, rev, subject), p)
    } yield res
  }.named("deprecateElasticSearchView", moduleType)

  /**
    * Retrieves a current ElasticSearchView resource.
    *
    * @param id      the identifier that will be expanded to the Iri of the view with its optional rev/tag
    * @param project the view parent project
    */
  def fetch(id: IdSegmentRef, project: ProjectRef): IO[ElasticSearchViewRejection, ViewResource] =
    id.asTag
      .fold(
        for {
          p               <- projects.fetchProject(project)
          iri             <- expandIri(id.value, p)
          state           <- id.asRev.fold(currentState(project, iri))(id => stateAt(project, iri, id.rev))
          defaultMapping  <- defaultElasticsearchMapping
          defaultSettings <- defaultElasticsearchSettings
          res             <- IO.fromOption(
                               state.toResource(p.apiMappings, p.base, defaultMapping, defaultSettings),
                               ViewNotFound(iri, project)
                             )
        } yield res
      )(fetchBy(_, project))
      .named("fetchElasticSearchView", moduleType)

  /**
    * Retrieves a current IndexingElasticSearchView resource.
    *
    * @param id      the view identifier
    * @param project the view parent project
    */
  def fetchIndexingView(
      id: IdSegmentRef,
      project: ProjectRef
  ): IO[ElasticSearchViewRejection, IndexingViewResource] =
    fetch(id, project)
      .flatMap { res =>
        res.value match {
          case v: IndexingElasticSearchView  =>
            IO.pure(res.as(v))
          case _: AggregateElasticSearchView =>
            IO.raiseError(DifferentElasticSearchViewType(res.id, ElasticSearchAggregate, ElasticSearchIndexing))
        }
      }

  private def fetchBy(id: IdSegmentRef.Tag, project: ProjectRef): IO[ElasticSearchViewRejection, ViewResource] =
    fetch(id.toLatest, project).flatMap { view =>
      view.value.tags.get(id.tag) match {
        case Some(rev) => fetch(id.toRev(rev), project).mapError(_ => TagNotFound(id.tag))
        case None      => IO.raiseError(TagNotFound(id.tag))
      }
    }

  /**
    * Retrieves a list of ElasticSearchViews using specific pagination, filter and ordering configuration.
    *
    * @param pagination the pagination configuration
    * @param params     the filtering configuration
    * @param ordering   the ordering configuration
    */
  def list(
      pagination: FromPagination,
      params: ElasticSearchViewSearchParams,
      ordering: Ordering[ViewResource]
  ): UIO[UnscoredSearchResults[ViewResource]] =
    cache.values
      .map { resources =>
        val results = resources.filter(params.matches).sorted(ordering)
        UnscoredSearchResults(
          results.size.toLong,
          results.map(UnscoredResultEntry(_)).slice(pagination.from, pagination.from + pagination.size)
        )
      }
      .named("listElasticSearchViews", moduleType)

  /**
    * A terminating stream of events for views. It finishes the stream after emitting all known events.
    *
    * @param projectRef the project reference where the elasticsearch view belongs
    * @param offset     the last seen event offset; it will not be emitted by the stream
    */
  def currentEvents(
      projectRef: ProjectRef,
      offset: Offset
  ): IO[ElasticSearchViewRejection, Stream[Task, Envelope[ElasticSearchViewEvent]]] =
    eventLog.currentProjectEvents(projects, projectRef, offset)

  /**
    * A non terminating stream of events for elasticsearch views. After emitting all known events it sleeps until new events
    * are recorded.
    *
    * @param projectRef the project reference where the elasticsearch view belongs
    * @param offset     the last seen event offset; it will not be emitted by the stream
    */
  def events(
      projectRef: ProjectRef,
      offset: Offset
  ): IO[ElasticSearchViewRejection, Stream[Task, Envelope[ElasticSearchViewEvent]]] =
    eventLog.projectEvents(projects, projectRef, offset)

  /**
    * A non terminating stream of events for elasticsearch views. After emitting all known events it sleeps until new events
    * are recorded.
    *
    * @param organization the organization label reference where the elasticsearch view belongs
    * @param offset       the last seen event offset; it will not be emitted by the stream
    */
  def events(
      organization: Label,
      offset: Offset
  ): IO[WrappedOrganizationRejection, Stream[Task, Envelope[ElasticSearchViewEvent]]] =
    eventLog.orgEvents(orgs, organization, offset)

  /**
    * Retrieves the ordered collection of events for all ElasticSearchViews starting from the last known offset. The
    * event corresponding to the provided offset will not be included in the results. The use of NoOffset implies the
    * retrieval of all events.
    *
    * @param offset the starting offset for the event log
    */
  def events(offset: Offset): Stream[Task, Envelope[ElasticSearchViewEvent]] =
    eventLog.eventsByTag(moduleType, offset)

  private def currentState(project: ProjectRef, iri: Iri): IO[ElasticSearchViewRejection, ElasticSearchViewState] =
    aggregate.state(identifier(project, iri)).named("currentState", moduleType)

  private def stateAt(project: ProjectRef, iri: Iri, rev: Long): IO[RevisionNotFound, ElasticSearchViewState] =
    eventLog
      .fetchStateAt(persistenceId(moduleType, identifier(project, iri)), rev, Initial, next)
      .mapError(RevisionNotFound(rev, _))
      .named("stateAt", moduleType)

  private def eval(
      cmd: ElasticSearchViewCommand,
      project: Project
  ): IO[ElasticSearchViewRejection, ViewResource] =
    for {
      result          <- aggregate.evaluate(identifier(cmd.project, cmd.id), cmd).mapError(_.value)
      (am, base)       = project.apiMappings -> project.base
      defaultMapping  <- defaultElasticsearchMapping
      defaultSettings <- defaultElasticsearchSettings
      resource        <- IO.fromOption(
                           result.state.toResource(am, base, defaultMapping, defaultSettings),
                           UnexpectedInitialState(cmd.id, project.ref)
                         )
      _               <- cache.put(cmd.project, cmd.id, resource)
    } yield resource

  private def identifier(project: ProjectRef, id: Iri): String =
    s"${project}_$id"

}

object ElasticSearchViews {

  /**
    * The elasticsearch module type.
    */
  val moduleType: String = "elasticsearch"

  /**
    * The views module tag.
    */
  val moduleTag = "view"

  /**
    * Iri expansion logic for ElasticSearchViews.
    */
  val expandIri: ExpandIri[InvalidElasticSearchViewId] = new ExpandIri(InvalidElasticSearchViewId.apply)

  /**
    * The default Elasticsearch API mappings
    */
  val mappings: ApiMappings = ApiMappings("view" -> schema.original, "documents" -> defaultViewId)

  /**
    * Constructs a projectionId for an elasticsearch view
    */
  def projectionId(view: IndexingViewResource): ViewProjectionId =
    projectionId(view.value.uuid, view.rev)

  /**
    * Constructs a projectionId for an elasticsearch view
    */
  def projectionId(uuid: UUID, rev: Long): ViewProjectionId =
    ViewProjectionId(s"$moduleType-${uuid}_$rev")

  /**
    * Constructs the index name for an Elasticsearch view
    */
  def index(view: IndexingViewResource, config: ExternalIndexingConfig): String =
    index(view.value.uuid, view.rev, config)

  def index(uuid: UUID, rev: Long, config: ExternalIndexingConfig): String =
    IndexLabel.fromView(config.prefix, uuid, rev).value

  /**
    * Create [[EventExchangeValue]] for a elasticsearch view.
    */
  def eventExchangeValue(res: ViewResource)(implicit
      enc: JsonLdEncoder[ElasticSearchView]
  ): EventExchangeValue[ElasticSearchView, ElasticSearchView.Metadata] =
    EventExchangeValue(ReferenceExchangeValue(res, res.value.source, enc), JsonLdValue(res.value.metadata))

  /**
    * Create a reference exchange from a [[ElasticSearchViews]] instance
    */
  def referenceExchange(views: ElasticSearchViews): ReferenceExchange = {
    val fetch = (ref: ResourceRef, projectRef: ProjectRef) => views.fetch(ref.toIdSegmentRef, projectRef)
    ReferenceExchange[ElasticSearchView](fetch(_, _), _.source)
  }

  /**
    * Create a project reference finder for elasticsearch views
    */
  def projectReferenceFinder(views: ElasticSearchViews): ProjectReferenceFinder =
    (project: ProjectRef) => {
      val params = ElasticSearchViewSearchParams(
        deprecated = Some(false),
        filter = {
          case a: AggregateElasticSearchView => a.project != project && a.views.value.exists(_.project == project)
          case _                             => false
        }
      )
      views.list(OnePage, params, ProjectReferenceFinder.ordering).map {
        _.results.foldMap { r =>
          ProjectReferenceMap.single(r.source.value.project, r.source.id)
        }
      }
    }

  /**
    * Constructs a new [[ElasticSearchViews]] instance.
    */
  def apply(
      deferred: Deferred[Task, ElasticSearchViews],
      config: ElasticSearchViewsConfig,
      eventLog: EventLog[Envelope[ElasticSearchViewEvent]],
      contextResolution: ResolverContextResolution,
      cache: ElasticSearchViewCache,
      agg: ElasticSearchViewAggregate,
      orgs: Organizations,
      projects: Projects
  )(implicit
      uuidF: UUIDF,
      scheduler: Scheduler,
      as: ActorSystem[Nothing]
  ): Task[ElasticSearchViews] = {

    for {
      decoder <- Task.delay(ElasticSearchViewJsonLdSourceDecoder(uuidF, contextResolution))
      views   <- Task.delay(new ElasticSearchViews(agg, eventLog, cache, orgs, projects, decoder))
      _       <- deferred.complete(views)
      _       <- ElasticSearchViewsIndexing.populateCache(config.cacheIndexing.retry, views, cache)
    } yield views
  }

  type ElasticSearchViewAggregate = Aggregate[
    String,
    ElasticSearchViewState,
    ElasticSearchViewCommand,
    ElasticSearchViewEvent,
    ElasticSearchViewRejection
  ]

  type ElasticSearchViewCache = CompositeKeyValueStore[ProjectRef, Iri, ViewResource]

  /**
    * Creates a new distributed ElasticSearchViewCache.
    */
  def cache(
      config: ElasticSearchViewsConfig
  )(implicit as: ActorSystem[Nothing]): UIO[ElasticSearchViewCache] =
    UIO.delay {
      implicit val cfg: KeyValueStoreConfig   = config.keyValueStore
      val clock: (Long, ViewResource) => Long = (_, resource) => resource.rev
      CompositeKeyValueStore(moduleType, clock)
    }

  def aggregate(
      config: ElasticSearchViewsConfig,
      permissions: Permissions,
      client: ElasticSearchClient,
      deferred: Deferred[Task, ElasticSearchViews],
      resourceIdCheck: ResourceIdCheck
  )(implicit as: ActorSystem[Nothing], uuidF: UUIDF, clock: Clock[UIO]): UIO[ElasticSearchViewAggregate] = {

    val validateIndex: ValidateIndex =
      (index, esValue) =>
        for {
          defaultMapping  <- defaultElasticsearchMapping
          defaultSettings <- defaultElasticsearchSettings
          _               <- client
                               .createIndex(
                                 index,
                                 esValue.mapping.orElse(Some(defaultMapping)),
                                 esValue.settings.orElse(Some(defaultSettings))
                               )
                               .mapError {
                                 case err: HttpClientStatusError => InvalidElasticSearchIndexPayload(err.jsonBody)
                                 case err                        => WrappedElasticSearchClientError(err)
                               }
                               .void
        } yield ()

    aggregate(config, permissions, validateIndex, deferred, resourceIdCheck)
  }

  private[elasticsearch] def aggregate(
      config: ElasticSearchViewsConfig,
      permissions: Permissions,
      validateIndex: ValidateIndex,
      deferred: Deferred[Task, ElasticSearchViews],
      resourceIdCheck: ResourceIdCheck
  )(implicit as: ActorSystem[Nothing], uuidF: UUIDF, clock: Clock[UIO]): UIO[ElasticSearchViewAggregate] = {

    val validatePermission: ValidatePermission = { permission =>
      permissions.fetchPermissionSet.flatMap { set =>
        IO.raiseUnless(set.contains(permission))(PermissionIsNotDefined(permission))
      }
    }

    val viewResolution: ViewRefResolution = { viewRefs =>
      deferred.get.hideErrors.flatMap { views =>
        ElasticSearchViewRefVisitor(views, config.indexing).visitAll(viewRefs)
      }
    }

    val validateRef: ValidateRef = { viewRef =>
      deferred.get.hideErrors.flatMap { views =>
        views
          .fetch(viewRef.viewId, viewRef.project)
          .redeemWith(
            _ => IO.raiseError(InvalidViewReference(viewRef)),
            resource => IO.raiseWhen(resource.deprecated)(InvalidViewReference(viewRef))
          )
      }
    }

    val idAvailability: IdAvailability[ResourceAlreadyExists] = (project, id) =>
      resourceIdCheck.isAvailableOr(project, id)(ResourceAlreadyExists(id, project))

    aggregate(config, validatePermission, validateIndex, viewResolution, validateRef, idAvailability)
  }

  private def aggregate(
      config: ElasticSearchViewsConfig,
      validatePermission: ValidatePermission,
      validateIndex: ValidateIndex,
      viewResolution: ViewRefResolution,
      validateRef: ValidateRef,
      idAvailability: IdAvailability[ResourceAlreadyExists]
  )(implicit as: ActorSystem[Nothing], uuidF: UUIDF, clock: Clock[UIO]): UIO[ElasticSearchViewAggregate] = {
    val definition = PersistentEventDefinition(
      entityType = moduleType,
      initialState = Initial,
      next = next,
      evaluate = evaluate(
        validatePermission,
        validateIndex,
        validateRef,
        viewResolution,
        idAvailability,
        config.indexing.prefix,
        config.maxViewRefs
      ),
      tagger = EventTags.forProjectScopedEvent(moduleTag, moduleType),
      snapshotStrategy = config.aggregate.snapshotStrategy.strategy,
      stopStrategy = config.aggregate.stopStrategy.persistentStrategy
    )

    ShardedAggregate.persistentSharded(
      definition = definition,
      config = config.aggregate.processor
    )
  }

  private[elasticsearch] def next(
      state: ElasticSearchViewState,
      event: ElasticSearchViewEvent
  ): ElasticSearchViewState = {
    // format: off
    def created(e: ElasticSearchViewCreated): ElasticSearchViewState = state match {
      case Initial     => Current(e.id, e.project, e.uuid, e.value, e.source, Map.empty, e.rev, deprecated = false,  e.instant, e.subject, e.instant, e.subject)
      case s: Current  => s
    }

    def updated(e: ElasticSearchViewUpdated): ElasticSearchViewState = state match {
      case Initial    => Initial
      case s: Current => s.copy(rev = e.rev, value = e.value, source = e.source, updatedAt = e.instant, updatedBy = e.subject)
    }

    def tagAdded(e: ElasticSearchViewTagAdded): ElasticSearchViewState = state match {
      case Initial    => Initial
      case s: Current => s.copy(rev = e.rev, tags = s.tags + (e.tag -> e.targetRev), updatedAt = e.instant, updatedBy = e.subject)
    }
    // format: on

    def deprecated(e: ElasticSearchViewDeprecated): ElasticSearchViewState = state match {
      case Initial    => Initial
      case s: Current => s.copy(rev = e.rev, deprecated = true, updatedAt = e.instant, updatedBy = e.subject)
    }

    event match {
      case e: ElasticSearchViewCreated    => created(e)
      case e: ElasticSearchViewUpdated    => updated(e)
      case e: ElasticSearchViewTagAdded   => tagAdded(e)
      case e: ElasticSearchViewDeprecated => deprecated(e)
    }
  }

  type ValidatePermission = Permission => IO[PermissionIsNotDefined, Unit]
  type ValidateIndex      = (IndexLabel, IndexingElasticSearchViewValue) => IO[ElasticSearchViewRejection, Unit]
  type ViewRefResolution  = NonEmptySet[ViewRef] => IO[ElasticSearchViewRejection, Set[VisitedView]]
  type ValidateRef        = ViewRef => IO[InvalidViewReference, Unit]

  private[elasticsearch] def evaluate(
      validatePermission: ValidatePermission,
      validateIndex: ValidateIndex,
      validateRef: ValidateRef,
      viewRefResolution: ViewRefResolution,
      idAvailability: IdAvailability[ResourceAlreadyExists],
      indexingPrefix: String,
      maxViewRefs: Int
  )(state: ElasticSearchViewState, cmd: ElasticSearchViewCommand)(implicit
      clock: Clock[UIO],
      uuidF: UUIDF
  ): IO[ElasticSearchViewRejection, ElasticSearchViewEvent] = {

    def validate(uuid: UUID, rev: Long, value: ElasticSearchViewValue): IO[ElasticSearchViewRejection, Unit] =
      value match {
        case v: AggregateElasticSearchViewValue =>
          for {
            _               <- IO.parTraverseUnordered(v.views.value)(validateRef).void
            refs            <- viewRefResolution(v.views)
            indexedRefsCount = refs.count(_.isIndexed)
            _               <- IO.raiseWhen(indexedRefsCount > maxViewRefs)(TooManyViewReferences(indexedRefsCount, maxViewRefs))
          } yield ()
        case v: IndexingElasticSearchViewValue  =>
          for {
            _ <- validateIndex(IndexLabel.fromView(indexingPrefix, uuid, rev), v)
            _ <- validatePermission(v.permission)
          } yield ()
      }

    def create(c: CreateElasticSearchView) = state match {
      case Initial =>
        for {
          t <- IOUtils.instant
          u <- uuidF()
          _ <- validate(u, 1L, c.value)
          _ <- idAvailability(c.project, c.id)
        } yield ElasticSearchViewCreated(c.id, c.project, u, c.value, c.source, 1L, t, c.subject)
      case _       => IO.raiseError(ResourceAlreadyExists(c.id, c.project))
    }

    def update(c: UpdateElasticSearchView) = state match {
      case Initial                                  =>
        IO.raiseError(ViewNotFound(c.id, c.project))
      case s: Current if s.rev != c.rev             =>
        IO.raiseError(IncorrectRev(c.rev, s.rev))
      case s: Current if s.deprecated               =>
        IO.raiseError(ViewIsDeprecated(c.id))
      case s: Current if c.value.tpe != s.value.tpe =>
        IO.raiseError(DifferentElasticSearchViewType(s.id, c.value.tpe, s.value.tpe))
      case s: Current                               =>
        for {
          _ <- validate(s.uuid, s.rev + 1L, c.value)
          t <- IOUtils.instant
        } yield ElasticSearchViewUpdated(c.id, c.project, s.uuid, c.value, c.source, s.rev + 1L, t, c.subject)
    }

    def tag(c: TagElasticSearchView) = state match {
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
          ElasticSearchViewTagAdded(c.id, c.project, s.value.tpe, s.uuid, c.targetRev, c.tag, s.rev + 1L, _, c.subject)
        )
    }

    def deprecate(c: DeprecateElasticSearchView) = state match {
      case Initial                      =>
        IO.raiseError(ViewNotFound(c.id, c.project))
      case s: Current if s.rev != c.rev =>
        IO.raiseError(IncorrectRev(c.rev, s.rev))
      case s: Current if s.deprecated   =>
        IO.raiseError(ViewIsDeprecated(c.id))
      case s: Current                   =>
        IOUtils.instant.map(ElasticSearchViewDeprecated(c.id, c.project, s.value.tpe, s.uuid, s.rev + 1L, _, c.subject))
    }

    cmd match {
      case c: CreateElasticSearchView    => create(c)
      case c: UpdateElasticSearchView    => update(c)
      case c: TagElasticSearchView       => tag(c)
      case c: DeprecateElasticSearchView => deprecate(c)
    }
  }
}
