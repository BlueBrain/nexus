package ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch

import cats.effect.Clock
import cats.implicits._
import ch.epfl.bluebrain.nexus.delta.kernel.database.Transactors
import ch.epfl.bluebrain.nexus.delta.kernel.kamon.KamonMetricComponent
import ch.epfl.bluebrain.nexus.delta.kernel.utils.{IOUtils, UUIDF}
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.ElasticSearchViews._
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.client.IndexLabel
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.model.ElasticSearchView.{AggregateElasticSearchView, IndexingElasticSearchView}
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.model.ElasticSearchViewCommand._
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.model.ElasticSearchViewEvent._
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.model.ElasticSearchViewRejection._
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.model.ElasticSearchViewType.{AggregateElasticSearch => ElasticSearchAggregate, ElasticSearch => ElasticSearchIndexing}
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.model.ElasticSearchViewValue.AggregateElasticSearchViewValue
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.model._
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.api.JsonLdApi
import ch.epfl.bluebrain.nexus.delta.sdk.identities.model.Caller
import ch.epfl.bluebrain.nexus.delta.sdk.implicits._
import ch.epfl.bluebrain.nexus.delta.sdk.jsonld.ExpandIri
import ch.epfl.bluebrain.nexus.delta.sdk.model.IdSegmentRef.{Latest, Revision, Tag}
import ch.epfl.bluebrain.nexus.delta.sdk.model._
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.Pagination.FromPagination
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.SearchResults
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.SearchResults.UnscoredSearchResults
import ch.epfl.bluebrain.nexus.delta.sdk.projects.FetchContext
import ch.epfl.bluebrain.nexus.delta.sdk.projects.model.{ApiMappings, ProjectContext}
import ch.epfl.bluebrain.nexus.delta.sdk.resolvers.ResolverContextResolution
import ch.epfl.bluebrain.nexus.delta.sourcing.ScopedEntityDefinition.Tagger
import ch.epfl.bluebrain.nexus.delta.sourcing._
import ch.epfl.bluebrain.nexus.delta.sourcing.config.EventLogConfig
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.Subject
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Tag.UserTag
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{EntityDependency, EntityType, EnvelopeStream, ProjectRef}
import ch.epfl.bluebrain.nexus.delta.sourcing.offset.Offset
import ch.epfl.bluebrain.nexus.delta.sourcing.projections.ProjectionId.ViewProjectionId
import io.circe.{Json, JsonObject}
import monix.bio.{IO, Task, UIO}

import java.util.UUID

/**
  * ElasticSearchViews resource lifecycle operations.
  */
final class ElasticSearchViews private (
    log: ElasticsearchLog,
    fetchContext: FetchContext[ElasticSearchViewRejection],
    sourceDecoder: ElasticSearchViewJsonLdSourceDecoder,
    defaultElasticsearchMapping: JsonObject,
    defaultElasticsearchSettings: JsonObject
)(implicit uuidF: UUIDF) {

  implicit private val kamonComponent: KamonMetricComponent = KamonMetricComponent(entityType.value)

  /**
    * Creates a new ElasticSearchView with a generated id.
    *
    * @param project
    *   the parent project of the view
    * @param value
    *   the view configuration
    * @param subject
    *   the subject that initiated the action
    */
  def create(
      project: ProjectRef,
      value: ElasticSearchViewValue
  )(implicit subject: Subject): IO[ElasticSearchViewRejection, ViewResource] =
    uuidF().flatMap(uuid => create(uuid.toString, project, value))

  /**
    * Creates a new ElasticSearchView with a provided id.
    *
    * @param id
    *   the id of the view either in Iri or aliased form
    * @param project
    *   the parent project of the view
    * @param value
    *   the view configuration
    * @param subject
    *   the subject that initiated the action
    */
  def create(
      id: IdSegment,
      project: ProjectRef,
      value: ElasticSearchViewValue
  )(implicit subject: Subject): IO[ElasticSearchViewRejection, ViewResource] = {
    for {
      pc  <- fetchContext.onCreate(project)
      iri <- expandIri(id, pc)
      res <- eval(CreateElasticSearchView(iri, project, value, value.toJson(iri), subject), pc)
    } yield res
  }.span("createElasticSearchView")

  /**
    * Creates a new ElasticSearchView from a json representation. If an identifier exists in the provided json it will
    * be used; otherwise a new identifier will be generated.
    *
    * @param project
    *   the parent project of the view
    * @param source
    *   the json representation of the view
    * @param caller
    *   the caller that initiated the action
    */
  def create(
      project: ProjectRef,
      source: Json
  )(implicit caller: Caller): IO[ElasticSearchViewRejection, ViewResource] = {
    for {
      pc           <- fetchContext.onCreate(project)
      (iri, value) <- sourceDecoder(project, pc, source)
      res          <- eval(CreateElasticSearchView(iri, project, value, source, caller.subject), pc)
    } yield res
  }.span("createElasticSearchView")

  /**
    * Creates a new ElasticSearchView from a json representation. If an identifier exists in the provided json it will
    * be used as long as it matches the provided id in Iri form or as an alias; otherwise the action will be rejected.
    *
    * @param project
    *   the parent project of the view
    * @param source
    *   the json representation of the view
    * @param caller
    *   the caller that initiated the action
    */
  def create(
      id: IdSegment,
      project: ProjectRef,
      source: Json
  )(implicit caller: Caller): IO[ElasticSearchViewRejection, ViewResource] = {
    for {
      pc    <- fetchContext.onCreate(project)
      iri   <- expandIri(id, pc)
      value <- sourceDecoder(project, pc, iri, source)
      res   <- eval(CreateElasticSearchView(iri, project, value, source, caller.subject), pc)
    } yield res
  }.span("createElasticSearchView")

  /**
    * Updates an existing ElasticSearchView.
    *
    * @param id
    *   the view identifier
    * @param project
    *   the view parent project
    * @param rev
    *   the current view revision
    * @param value
    *   the new view configuration
    * @param subject
    *   the subject that initiated the action
    */
  def update(
      id: IdSegment,
      project: ProjectRef,
      rev: Int,
      value: ElasticSearchViewValue
  )(implicit subject: Subject): IO[ElasticSearchViewRejection, ViewResource] = {
    for {
      pc  <- fetchContext.onModify(project)
      iri <- expandIri(id, pc)
      res <- eval(UpdateElasticSearchView(iri, project, rev, value, value.toJson(iri), subject), pc)
    } yield res
  }.span("updateElasticSearchView")

  /**
    * Updates an existing ElasticSearchView.
    *
    * @param id
    *   the view identifier
    * @param project
    *   the view parent project
    * @param rev
    *   the current view revision
    * @param source
    *   the new view configuration in json representation
    * @param caller
    *   the caller that initiated the action
    */
  def update(
      id: IdSegment,
      project: ProjectRef,
      rev: Int,
      source: Json
  )(implicit caller: Caller): IO[ElasticSearchViewRejection, ViewResource] = {
    for {
      pc    <- fetchContext.onModify(project)
      iri   <- expandIri(id, pc)
      value <- sourceDecoder(project, pc, iri, source)
      res   <- eval(UpdateElasticSearchView(iri, project, rev, value, source, caller.subject), pc)
    } yield res
  }.span("updateElasticSearchView")

  /**
    * Applies a tag to an existing ElasticSearchView revision.
    *
    * @param id
    *   the view identifier
    * @param project
    *   the view parent project
    * @param tag
    *   the tag to apply
    * @param tagRev
    *   the target revision of the tag
    * @param rev
    *   the current view revision
    * @param subject
    *   the subject that initiated the action
    */
  def tag(
      id: IdSegment,
      project: ProjectRef,
      tag: UserTag,
      tagRev: Int,
      rev: Int
  )(implicit subject: Subject): IO[ElasticSearchViewRejection, ViewResource] = {
    for {
      pc  <- fetchContext.onModify(project)
      iri <- expandIri(id, pc)
      res <- eval(TagElasticSearchView(iri, project, tagRev, tag, rev, subject), pc)
    } yield res
  }.span("tagElasticSearchView")

  /**
    * Deprecates an existing ElasticSearchView. View deprecation implies blocking any query capabilities and in case of
    * an IndexingElasticSearchView the corresponding index is deleted.
    *
    * @param id
    *   the view identifier
    * @param project
    *   the view parent project
    * @param rev
    *   the current view revision
    * @param subject
    *   the subject that initiated the action
    */
  def deprecate(
      id: IdSegment,
      project: ProjectRef,
      rev: Int
  )(implicit subject: Subject): IO[ElasticSearchViewRejection, ViewResource] = {
    for {
      pc  <- fetchContext.onModify(project)
      iri <- expandIri(id, pc)
      res <- eval(DeprecateElasticSearchView(iri, project, rev, subject), pc)
    } yield res
  }.span("deprecateElasticSearchView")

  /**
    * Retrieves a current ElasticSearchView resource.
    *
    * @param id
    *   the identifier that will be expanded to the Iri of the view with its optional rev/tag
    * @param project
    *   the view parent project
    */
  def fetch(id: IdSegmentRef, project: ProjectRef): IO[ElasticSearchViewRejection, ViewResource] =
    fetchState(id, project).map { case (pc, state) =>
      state.toResource(pc.apiMappings, pc.base, defaultElasticsearchMapping, defaultElasticsearchSettings)
    }

  def fetchState(
      id: IdSegmentRef,
      project: ProjectRef
  ): IO[ElasticSearchViewRejection, (ProjectContext, ElasticSearchViewState)] = {
    for {
      pc      <- fetchContext.onRead(project)
      iri     <- expandIri(id.value, pc)
      notFound = ViewNotFound(iri, project)
      state   <- id match {
                   case Latest(_)        => log.stateOr(project, iri, notFound)
                   case Revision(_, rev) =>
                     log.stateOr(project, iri, rev.toInt, notFound, RevisionNotFound)
                   case Tag(_, tag)      =>
                     log.stateOr(project, iri, tag, notFound, TagNotFound(tag))
                 }
    } yield (pc, state)
  }.span("fetchElasticSearchView")

  /**
    * Retrieves a current IndexingElasticSearchView resource.
    *
    * @param id
    *   the view identifier
    * @param project
    *   the view parent project
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

  /**
    * Retrieves a list of ElasticSearchViews using specific pagination, filter and ordering configuration.
    *
    * @param pagination
    *   the pagination configuration
    * @param params
    *   the filtering configuration
    * @param ordering
    *   the ordering configuration
    */
  def list(
      pagination: FromPagination,
      params: ElasticSearchViewSearchParams,
      ordering: Ordering[ViewResource]
  ): UIO[UnscoredSearchResults[ViewResource]] = {
    val predicate = params.project.fold[Predicate](Predicate.Root)(ref => Predicate.Project(ref))
    SearchResults(
      log.currentStates(predicate, identity(_)).evalMapFilter[Task, ViewResource] { state =>
        fetchContext.cacheOnReads
          .onRead(state.project)
          .redeemWith(
            _ => UIO.none,
            pc => {
              val res =
                state.toResource(pc.apiMappings, pc.base, defaultElasticsearchMapping, defaultElasticsearchSettings)
              params.matches(res).map(Option.when(_)(res))
            }
          )
      },
      pagination,
      ordering
    ).span("listElasticSearchViews")
  }

  def states(start: Offset): EnvelopeStream[Iri, ElasticSearchViewState] =
    log.states(Predicate.Root, start)

  private def eval(
      cmd: ElasticSearchViewCommand,
      pc: ProjectContext
  ): IO[ElasticSearchViewRejection, ViewResource] =
    log
      .evaluate(cmd.project, cmd.id, cmd)
      .map(_._2.toResource(pc.apiMappings, pc.base, defaultElasticsearchMapping, defaultElasticsearchSettings))

}

object ElasticSearchViews {

  final val entityType: EntityType = EntityType("elasticsearch")

  type ElasticsearchLog = ScopedEventLog[
    Iri,
    ElasticSearchViewState,
    ElasticSearchViewCommand,
    ElasticSearchViewEvent,
    ElasticSearchViewRejection
  ]

  /**
    * Iri expansion logic for ElasticSearchViews.
    */
  val expandIri: ExpandIri[InvalidElasticSearchViewId] = new ExpandIri(InvalidElasticSearchViewId.apply)

  /**
    * The default Elasticsearch API mappings
    */
  val mappings: ApiMappings = ApiMappings("view" -> schema.original, "documents" -> defaultViewId)

  def projectionName(state: ElasticSearchViewState): String =
    projectionName(state.project, state.id, state.rev)

  def projectionName(project: ProjectRef, id: Iri, rev: Int): String = {
    s"elasticsearch-${project}-$id-$rev"
  }

  /**
    * Constructs a projectionId for an elasticsearch view
    */
  def projectionId(view: IndexingViewResource): ViewProjectionId =
    projectionId(view.value.uuid, view.rev.toInt)

  /**
    * Constructs a projectionId for an elasticsearch view
    */
  def projectionId(uuid: UUID, rev: Int): ViewProjectionId =
    ViewProjectionId(s"elasticsearch-${uuid}_$rev")

  /**
    * Constructs the index name for an Elasticsearch view
    */
  def index(view: IndexingViewResource, prefix: String): String =
    index(view.value.uuid, view.rev.toInt, prefix).value

  def index(uuid: UUID, rev: Int, prefix: String): IndexLabel =
    IndexLabel.fromView(prefix, uuid, rev)

  def apply(
      fetchContext: FetchContext[ElasticSearchViewRejection],
      contextResolution: ResolverContextResolution,
      validate: ValidateElasticSearchView,
      eventLogConfig: EventLogConfig,
      xas: Transactors
  )(implicit api: JsonLdApi, clock: Clock[UIO], uuidF: UUIDF): Task[ElasticSearchViews] = {
    for {
      sourceDecoder   <- Task.delay(ElasticSearchViewJsonLdSourceDecoder(uuidF, contextResolution))
      defaultMapping  <- defaultElasticsearchMapping
      defaultSettings <- defaultElasticsearchSettings
    } yield new ElasticSearchViews(
      ScopedEventLog(
        definition(validate),
        eventLogConfig,
        xas
      ),
      fetchContext,
      sourceDecoder,
      defaultMapping,
      defaultSettings
    )
  }

  private[elasticsearch] def next(
      state: Option[ElasticSearchViewState],
      event: ElasticSearchViewEvent
  ): Option[ElasticSearchViewState] = {
    // format: off
    def created(e: ElasticSearchViewCreated): Option[ElasticSearchViewState] =
      Option.when(state.isEmpty) {
        ElasticSearchViewState(e.id, e.project, e.uuid, e.value, e.source, Tags.empty, e.rev, deprecated = false,  e.instant, e.subject, e.instant, e.subject)
      }

    def updated(e: ElasticSearchViewUpdated): Option[ElasticSearchViewState] = state.map { s =>
      s.copy(rev = e.rev, value = e.value, source = e.source, updatedAt = e.instant, updatedBy = e.subject)
    }

    def tagAdded(e: ElasticSearchViewTagAdded): Option[ElasticSearchViewState] = state.map { s =>
      s.copy(rev = e.rev, tags = s.tags + (e.tag -> e.targetRev), updatedAt = e.instant, updatedBy = e.subject)
    }
    // format: on

    def deprecated(e: ElasticSearchViewDeprecated): Option[ElasticSearchViewState] = state.map { s =>
      s.copy(rev = e.rev, deprecated = true, updatedAt = e.instant, updatedBy = e.subject)
    }

    event match {
      case e: ElasticSearchViewCreated    => created(e)
      case e: ElasticSearchViewUpdated    => updated(e)
      case e: ElasticSearchViewTagAdded   => tagAdded(e)
      case e: ElasticSearchViewDeprecated => deprecated(e)
    }
  }

  private[elasticsearch] def evaluate(
      validate: ValidateElasticSearchView
  )(state: Option[ElasticSearchViewState], cmd: ElasticSearchViewCommand)(implicit
      clock: Clock[UIO],
      uuidF: UUIDF
  ): IO[ElasticSearchViewRejection, ElasticSearchViewEvent] = {

    def create(c: CreateElasticSearchView) = state match {
      case None    =>
        for {
          t <- IOUtils.instant
          u <- uuidF()
          _ <- validate(u, 1, c.value)
        } yield ElasticSearchViewCreated(c.id, c.project, u, c.value, c.source, 1, t, c.subject)
      case Some(_) => IO.raiseError(ResourceAlreadyExists(c.id, c.project))
    }

    def update(c: UpdateElasticSearchView) = state match {
      case None                                  =>
        IO.raiseError(ViewNotFound(c.id, c.project))
      case Some(s) if s.rev != c.rev             =>
        IO.raiseError(IncorrectRev(c.rev, s.rev))
      case Some(s) if s.deprecated               =>
        IO.raiseError(ViewIsDeprecated(c.id))
      case Some(s) if c.value.tpe != s.value.tpe =>
        IO.raiseError(DifferentElasticSearchViewType(s.id, c.value.tpe, s.value.tpe))
      case Some(s)                               =>
        for {
          _ <- validate(s.uuid, s.rev + 1, c.value)
          t <- IOUtils.instant
        } yield ElasticSearchViewUpdated(c.id, c.project, s.uuid, c.value, c.source, s.rev + 1, t, c.subject)
    }

    def tag(c: TagElasticSearchView) = state match {
      case None                                               =>
        IO.raiseError(ViewNotFound(c.id, c.project))
      case Some(s) if s.rev != c.rev                          =>
        IO.raiseError(IncorrectRev(c.rev, s.rev))
      case Some(s) if c.targetRev <= 0 || c.targetRev > s.rev =>
        IO.raiseError(RevisionNotFound(c.targetRev, s.rev))
      case Some(s)                                            =>
        IOUtils.instant.map(
          ElasticSearchViewTagAdded(c.id, c.project, s.value.tpe, s.uuid, c.targetRev, c.tag, s.rev + 1, _, c.subject)
        )
    }

    def deprecate(c: DeprecateElasticSearchView) = state match {
      case None                      =>
        IO.raiseError(ViewNotFound(c.id, c.project))
      case Some(s) if s.rev != c.rev =>
        IO.raiseError(IncorrectRev(c.rev, s.rev))
      case Some(s) if s.deprecated   =>
        IO.raiseError(ViewIsDeprecated(c.id))
      case Some(s)                   =>
        IOUtils.instant.map(ElasticSearchViewDeprecated(c.id, c.project, s.value.tpe, s.uuid, s.rev + 1, _, c.subject))
    }

    cmd match {
      case c: CreateElasticSearchView    => create(c)
      case c: UpdateElasticSearchView    => update(c)
      case c: TagElasticSearchView       => tag(c)
      case c: DeprecateElasticSearchView => deprecate(c)
    }
  }

  def definition(
      validate: ValidateElasticSearchView
  )(implicit clock: Clock[UIO], uuidF: UUIDF): ScopedEntityDefinition[
    Iri,
    ElasticSearchViewState,
    ElasticSearchViewCommand,
    ElasticSearchViewEvent,
    ElasticSearchViewRejection
  ] =
    ScopedEntityDefinition(
      entityType,
      StateMachine(
        None,
        evaluate(validate),
        next
      ),
      ElasticSearchViewEvent.serializer,
      ElasticSearchViewState.serializer,
      Tagger[ElasticSearchViewEvent](
        {
          case r: ElasticSearchViewTagAdded => Some(r.tag -> r.targetRev)
          case _                            => None
        },
        { _ =>
          None
        }
      ),
      { s =>
        s.value match {
          case a: AggregateElasticSearchViewValue =>
            Some(a.views.value.map { v => EntityDependency(v.project, v.viewId.toString) })
          case _                                  => None
        }
      },
      onUniqueViolation = (id: Iri, c: ElasticSearchViewCommand) =>
        c match {
          case c: CreateElasticSearchView => ResourceAlreadyExists(id, c.project)
          case c                          => IncorrectRev(c.rev, c.rev + 1)
        }
    )
}
