package ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch

import cats.effect.{Clock, IO}
import cats.syntax.all._
import ch.epfl.bluebrain.nexus.delta.kernel.effect.migration.{ioToTaskK, toCatsIOOps, toMonixBIOOps}
import ch.epfl.bluebrain.nexus.delta.kernel.kamon.KamonMetricComponent
import ch.epfl.bluebrain.nexus.delta.kernel.utils.{IOInstant, UUIDF}
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.ElasticSearchViews._
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.client.IndexLabel
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.indexing.IndexingViewDef
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.indexing.IndexingViewDef.{ActiveViewDef, DeprecatedViewDef}
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.model.ElasticSearchViewCommand._
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.model.ElasticSearchViewEvent._
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.model.ElasticSearchViewRejection._
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.model.ElasticSearchViewType.{AggregateElasticSearch, ElasticSearch}
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.model.ElasticSearchViewValue.IndexingElasticSearchViewValue.nextIndexingRev
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.model.ElasticSearchViewValue.{AggregateElasticSearchViewValue, IndexingElasticSearchViewValue}
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.model._
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.api.JsonLdApi
import ch.epfl.bluebrain.nexus.delta.sdk.identities.model.Caller
import ch.epfl.bluebrain.nexus.delta.sdk.implicits._
import ch.epfl.bluebrain.nexus.delta.sdk.jsonld.ExpandIri
import ch.epfl.bluebrain.nexus.delta.sdk.model.IdSegmentRef.{Latest, Revision, Tag}
import ch.epfl.bluebrain.nexus.delta.sdk.model._
import ch.epfl.bluebrain.nexus.delta.sdk.projects.FetchContext
import ch.epfl.bluebrain.nexus.delta.sdk.projects.model.{ApiMappings, ProjectContext}
import ch.epfl.bluebrain.nexus.delta.sdk.resolvers.ResolverContextResolution
import ch.epfl.bluebrain.nexus.delta.sdk.views.IndexingRev
import ch.epfl.bluebrain.nexus.delta.sourcing.ScopedEntityDefinition.Tagger
import ch.epfl.bluebrain.nexus.delta.sourcing._
import ch.epfl.bluebrain.nexus.delta.sourcing.config.EventLogConfig
import ch.epfl.bluebrain.nexus.delta.sourcing.model.EntityDependency.DependsOn
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.Subject
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Tag.UserTag
import ch.epfl.bluebrain.nexus.delta.sourcing.model._
import ch.epfl.bluebrain.nexus.delta.sourcing.offset.Offset
import io.circe.{Json, JsonObject}
import monix.bio.{IO => BIO}

import java.util.UUID

/**
  * ElasticSearchViews resource lifecycle operations.
  */
final class ElasticSearchViews private (
    log: ElasticsearchLog,
    fetchContext: FetchContext[ElasticSearchViewRejection],
    sourceDecoder: ElasticSearchViewJsonLdSourceDecoder,
    defaultElasticsearchMapping: JsonObject,
    defaultElasticsearchSettings: JsonObject,
    prefix: String
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
  )(implicit subject: Subject): IO[ViewResource] =
    uuidF().toCatsIO.flatMap(uuid => create(uuid.toString, project, value))

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
  )(implicit subject: Subject): IO[ViewResource] = {
    for {
      (iri, _)  <- expandWithContext(fetchContext.onCreate, project, id)
      res <- eval(CreateElasticSearchView(iri, project, value, value.toJson(iri), subject))
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
  )(implicit caller: Caller): IO[ViewResource] = {
    for {
      pc           <- fetchContext.onCreate(project).toCatsIO
      (iri, value) <- sourceDecoder(project, pc, source)
      res          <- eval(CreateElasticSearchView(iri, project, value, source, caller.subject))
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
  )(implicit caller: Caller): IO[ViewResource] = {
    for {
      (iri, pc)  <- expandWithContext(fetchContext.onCreate, project, id)
      value <- sourceDecoder(project, pc, iri, source)
      res   <- eval(CreateElasticSearchView(iri, project, value, source, caller.subject))
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
  )(implicit subject: Subject): IO[ViewResource] = {
    for {
      (iri, _)  <- expandWithContext(fetchContext.onModify, project, id)
      res <- eval(UpdateElasticSearchView(iri, project, rev, value, value.toJson(iri), subject))
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
  )(implicit caller: Caller): IO[ViewResource] = {
    for {
      (iri, pc)  <- expandWithContext(fetchContext.onModify, project, id)
      value <- sourceDecoder(project, pc, iri, source)
      res   <- eval(UpdateElasticSearchView(iri, project, rev, value, source, caller.subject))
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
  )(implicit subject: Subject): IO[ViewResource] = {
    for {
      (iri, _)  <- expandWithContext(fetchContext.onModify, project, id)
      res <- eval(TagElasticSearchView(iri, project, tagRev, tag, rev, subject))
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
  )(implicit subject: Subject): IO[ViewResource] = {
    for {
      (iri, _)  <- expandWithContext(fetchContext.onModify, project, id)
      res <- eval(DeprecateElasticSearchView(iri, project, rev, subject))
    } yield res
  }.span("deprecateElasticSearchView")

  /**
    * Deprecates an existing ElasticSearchView without applying preliminary checks on the project status
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
  private[elasticsearch] def internalDeprecate(id: Iri, project: ProjectRef, rev: Int)(implicit
      subject: Subject
  ): IO[Unit] =
    eval(DeprecateElasticSearchView(id, project, rev, subject)).void

  /**
    * Retrieves a current ElasticSearchView resource.
    *
    * @param id
    *   the identifier that will be expanded to the Iri of the view with its optional rev/tag
    * @param project
    *   the view parent project
    */
  def fetch(id: IdSegmentRef, project: ProjectRef): IO[ViewResource] =
    fetchState(id, project).map { state =>
      state.toResource(defaultElasticsearchMapping, defaultElasticsearchSettings)
    }

  def fetchState(
      id: IdSegmentRef,
      project: ProjectRef
  ): IO[ElasticSearchViewState] = {
    for {
      (iri, _)  <- expandWithContext(fetchContext.onRead, project, id.value)
      state   <- stateOrNotFound(id, project, iri)
    } yield state
  }.span("fetchElasticSearchView")

  private def stateOrNotFound(id: IdSegmentRef, project: ProjectRef, iri: Iri) = {
    val notFound = ViewNotFound(iri, project)
    id match {
      case Latest(_) => log.stateOr(project, iri, notFound)
      case Revision(_, rev) => log.stateOr(project, iri, rev, notFound, RevisionNotFound)
      case Tag(_, tag) => log.stateOr(project, iri, tag, notFound, TagNotFound(tag))
    }
  }.toCatsIO

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
  ): IO[ActiveViewDef] =
    fetchState(id, project)
      .flatMap { state =>
        IndexingViewDef(state, defaultElasticsearchMapping, defaultElasticsearchSettings, prefix) match {
          case Some(viewDef) =>
            viewDef match {
              case v: ActiveViewDef     => IO.pure(v)
              case v: DeprecatedViewDef => IO.raiseError(ViewIsDeprecated(v.ref.viewId))
            }
          case None          =>
            IO.raiseError(DifferentElasticSearchViewType(state.id.toString, AggregateElasticSearch, ElasticSearch))
        }
      }

  /**
    * Return the existing indexing views in a project in a finite stream
    */
  def currentIndexingViews(project: ProjectRef): ElemStream[IndexingViewDef] =
    log.currentStates(Scope.Project(project)).evalMapFilter { envelope =>
      IO.pure(toIndexViewDef(envelope))
    }.translate(ioToTaskK)

  /**
    * Return all existing indexing views in a finite stream
    */
  def currentIndexingViews: ElemStream[IndexingViewDef] =
    log.currentStates(Scope.Root).evalMapFilter { envelope =>
      IO.pure(toIndexViewDef(envelope))
    }.translate(ioToTaskK)

  /**
    * Return the indexing views in a non-ending stream
    */
  def indexingViews(start: Offset): ElemStream[IndexingViewDef] =
    log.states(Scope.Root, start).evalMapFilter { envelope =>
      IO.pure(toIndexViewDef(envelope))
    }.translate(ioToTaskK)

  private def toIndexViewDef(envelope: Envelope[ElasticSearchViewState]) =
    envelope.toElem { v => Some(v.project) }.traverse { v =>
      IndexingViewDef(v, defaultElasticsearchMapping, defaultElasticsearchSettings, prefix)
    }

  private def eval(cmd: ElasticSearchViewCommand): IO[ViewResource] =
    log
      .evaluate(cmd.project, cmd.id, cmd)
      .map(_._2.toResource(defaultElasticsearchMapping, defaultElasticsearchSettings)).toCatsIO

  private def expandWithContext(
                                 fetchCtx: ProjectRef => BIO[ElasticSearchViewRejection, ProjectContext],
                                 ref: ProjectRef,
                                 id: IdSegment
                               ): IO[(Iri, ProjectContext)] =
    fetchCtx(ref).flatMap(pc => expandIri(id, pc).map(_ -> pc)).toCatsIO
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

  def projectionName(viewDef: ActiveViewDef): String =
    projectionName(viewDef.ref.project, viewDef.ref.viewId, viewDef.indexingRev)

  def projectionName(state: ElasticSearchViewState): String =
    projectionName(state.project, state.id, state.indexingRev)

  def projectionName(project: ProjectRef, id: Iri, indexingRev: IndexingRev): String = {
    s"elasticsearch-$project-$id-${indexingRev.value}"
  }

  def index(uuid: UUID, indexingRev: IndexingRev, prefix: String): IndexLabel =
    IndexLabel.fromView(prefix, uuid, indexingRev)

  def apply(
      fetchContext: FetchContext[ElasticSearchViewRejection],
      contextResolution: ResolverContextResolution,
      validate: ValidateElasticSearchView,
      eventLogConfig: EventLogConfig,
      prefix: String,
      xas: Transactors
  )(implicit api: JsonLdApi, clock: Clock[IO], uuidF: UUIDF): IO[ElasticSearchViews] = {
    for {
      sourceDecoder   <- ElasticSearchViewJsonLdSourceDecoder(uuidF, contextResolution)
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
      defaultSettings,
      prefix
    )
  }

  private[elasticsearch] def next(
      state: Option[ElasticSearchViewState],
      event: ElasticSearchViewEvent
  ): Option[ElasticSearchViewState] = {
    // format: off
    def created(e: ElasticSearchViewCreated): Option[ElasticSearchViewState] =
      Option.when(state.isEmpty) {
        ElasticSearchViewState(e.id, e.project, e.uuid, e.value, e.source, Tags.empty, e.rev, IndexingRev.init, deprecated = false,  e.instant, e.subject, e.instant, e.subject)
      }
      
    def updated(e: ElasticSearchViewUpdated): Option[ElasticSearchViewState] = state.map { s =>
      val newIndexingRev = nextIndexingRev(s.value, e.value, s.indexingRev, e.rev)
      s.copy(rev = e.rev, indexingRev = newIndexingRev, value = e.value, source = e.source, updatedAt = e.instant, updatedBy = e.subject)
    }
    // format: on

    def tagAdded(e: ElasticSearchViewTagAdded): Option[ElasticSearchViewState] = state.map { s =>
      s.copy(rev = e.rev, tags = s.tags + (e.tag -> e.targetRev), updatedAt = e.instant, updatedBy = e.subject)
    }

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
      clock: Clock[IO],
      uuidF: UUIDF
  ): IO[ElasticSearchViewEvent] = {

    def create(c: CreateElasticSearchView) = state match {
      case None    =>
        for {
          t <- IOInstant.now
          u <- uuidF().toCatsIO
          _ <- validate(u, IndexingRev.init, c.value)
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
        IO.raiseError(DifferentElasticSearchViewType(s.id.toString, c.value.tpe, s.value.tpe))
      case Some(s)                               =>
        val newIndexingRev = nextIndexingRev(s.value, c.value, s.indexingRev, c.rev)
        for {
          _ <- validate(s.uuid, newIndexingRev, c.value)
          t <- IOInstant.now
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
        IOInstant.now.map(
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
        IOInstant.now.map(ElasticSearchViewDeprecated(c.id, c.project, s.value.tpe, s.uuid, s.rev + 1, _, c.subject))
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
  )(implicit clock: Clock[IO], uuidF: UUIDF): ScopedEntityDefinition[
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
        evaluate(validate)(_, _).toBIO[ElasticSearchViewRejection],
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
            Some(a.views.map { v => DependsOn(v.project, v.viewId) }.toSortedSet)
          case _: IndexingElasticSearchViewValue  => None
        }
      },
      onUniqueViolation = (id: Iri, c: ElasticSearchViewCommand) =>
        c match {
          case c: CreateElasticSearchView => ResourceAlreadyExists(id, c.project)
          case c                          => IncorrectRev(c.rev, c.rev + 1)
        }
    )
}
