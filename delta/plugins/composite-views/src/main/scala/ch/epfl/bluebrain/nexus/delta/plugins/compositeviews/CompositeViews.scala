package ch.epfl.bluebrain.nexus.delta.plugins.compositeviews

import cats.effect.Clock
import cats.syntax.all._
import ch.epfl.bluebrain.nexus.delta.kernel.database.Transactors
import ch.epfl.bluebrain.nexus.delta.kernel.kamon.KamonMetricComponent
import ch.epfl.bluebrain.nexus.delta.kernel.syntax.kamonSyntax
import ch.epfl.bluebrain.nexus.delta.kernel.utils.{IOUtils, UUIDF}
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.BlazegraphViews
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.CompositeViews._
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.config.CompositeViewsConfig
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.model.CompositeViewCommand._
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.model.CompositeViewEvent._
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.model.CompositeViewProjection.{ElasticSearchProjection, SparqlProjection}
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.model.CompositeViewRejection._
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.model.CompositeViewSource._
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.model.ProjectionType.{ElasticSearchProjectionType, SparqlProjectionType}
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.model._
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.serialization.CompositeViewFieldsJsonLdSourceDecoder
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.ElasticSearchViews
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.client.IndexLabel
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.api.JsonLdApi
import ch.epfl.bluebrain.nexus.delta.sdk.crypto.Crypto
import ch.epfl.bluebrain.nexus.delta.sdk.identities.model.Caller
import ch.epfl.bluebrain.nexus.delta.sdk.implicits._
import ch.epfl.bluebrain.nexus.delta.sdk.jsonld.ExpandIri
import ch.epfl.bluebrain.nexus.delta.sdk.model.IdSegmentRef.{Latest, Revision, Tag}
import ch.epfl.bluebrain.nexus.delta.sdk.model._
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.Pagination.FromPagination
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.SearchResults
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.SearchResults.UnscoredSearchResults
import ch.epfl.bluebrain.nexus.delta.sdk.projects.FetchContext
import ch.epfl.bluebrain.nexus.delta.sdk.projects.model.ProjectContext
import ch.epfl.bluebrain.nexus.delta.sdk.resolvers.ResolverContextResolution
import ch.epfl.bluebrain.nexus.delta.sourcing.ScopedEntityDefinition.Tagger
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.Subject
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Tag.UserTag
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{EntityDependency, EntityType, ProjectRef}
import ch.epfl.bluebrain.nexus.delta.sourcing.projections.ProjectionId.{CompositeViewProjectionId, SourceProjectionId}
import ch.epfl.bluebrain.nexus.delta.sourcing.{Predicate, ScopedEntityDefinition, ScopedEventLog, StateMachine}
import io.circe.Json
import monix.bio.{IO, Task, UIO}

import java.util.UUID

/**
  * Composite views resource lifecycle operations.
  */
final class CompositeViews private (
    log: CompositeViewsLog,
    fetchContext: FetchContext[CompositeViewRejection],
    sourceDecoder: CompositeViewFieldsJsonLdSourceDecoder
)(implicit uuidF: UUIDF) {

  implicit private val kamonComponent: KamonMetricComponent = KamonMetricComponent(entityType.value)

  /**
    * Create a new composite view with a generate id.
    *
    * @param project
    *   the parent project of the view
    * @param value
    *   the view configuration
    */
  def create(
      project: ProjectRef,
      value: CompositeViewFields
  )(implicit subject: Subject, baseUri: BaseUri): IO[CompositeViewRejection, ViewResource] =
    uuidF().flatMap(uuid => create(uuid.toString, project, value))

  /**
    * Create a new composite view with a provided id
    *
    * @param id
    *   the id of the view either in Iri or aliased form
    * @param project
    *   the parent project of the view
    * @param value
    *   the view configuration
    */
  def create(
      id: IdSegment,
      project: ProjectRef,
      value: CompositeViewFields
  )(implicit subject: Subject, baseUri: BaseUri): IO[CompositeViewRejection, ViewResource] = {
    for {
      pc  <- fetchContext.onCreate(project)
      iri <- expandIri(id, pc)
      res <- eval(CreateCompositeView(iri, project, value, value.toJson(iri), subject, pc.base), pc)
    } yield res
  }.span("createCompositeView")

  /**
    * Creates a new composite from a json representation. If an identifier exists in the provided json it will be used;
    * otherwise a new identifier will be generated.
    *
    * @param project
    *   the parent project of the view
    * @param source
    *   the json representation of the view
    * @param caller
    *   the caller that initiated the action
    */
  def create(project: ProjectRef, source: Json)(implicit caller: Caller): IO[CompositeViewRejection, ViewResource] = {
    for {
      pc           <- fetchContext.onCreate(project)
      (iri, value) <- sourceDecoder(project, pc, source)
      res          <- eval(CreateCompositeView(iri, project, value, source.removeAllKeys("token"), caller.subject, pc.base), pc)
    } yield res
  }.span("createCompositeView")

  /**
    * Creates a new composite from a json representation. If an identifier exists in the provided json it will be used
    * as long as it matches the provided id in Iri form or as an alias; otherwise the action will be rejected.
    *
    * @param project
    *   the parent project of the view
    * @param source
    *   the json representation of the view
    * @param caller
    *   the caller that initiated the action
    */
  def create(id: IdSegment, project: ProjectRef, source: Json)(implicit
      caller: Caller
  ): IO[CompositeViewRejection, ViewResource] = {
    for {
      pc        <- fetchContext.onCreate(project)
      iri       <- expandIri(id, pc)
      viewValue <- sourceDecoder(project, pc, iri, source)
      res       <-
        eval(CreateCompositeView(iri, project, viewValue, source.removeAllKeys("token"), caller.subject, pc.base), pc)
    } yield res
  }.span("createCompositeView")

  /**
    * Updates an existing composite view.
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
      value: CompositeViewFields
  )(implicit
      subject: Subject,
      baseUri: BaseUri
  ): IO[CompositeViewRejection, ViewResource] = {
    for {
      pc    <- fetchContext.onModify(project)
      iri   <- expandIri(id, pc)
      source = value.toJson(iri)
      res   <- eval(UpdateCompositeView(iri, project, rev, value, source, subject, pc.base), pc)
    } yield res
  }.span("updateCompositeView")

  /**
    * Updates an existing composite view.
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
  def update(id: IdSegment, project: ProjectRef, rev: Int, source: Json)(implicit
      caller: Caller
  ): IO[CompositeViewRejection, ViewResource] = {
    for {
      pc        <- fetchContext.onModify(project)
      iri       <- expandIri(id, pc)
      viewValue <- sourceDecoder(project, pc, iri, source)
      res       <-
        eval(
          UpdateCompositeView(iri, project, rev, viewValue, source.removeAllKeys("token"), caller.subject, pc.base),
          pc
        )
    } yield res
  }.span("updateCompositeView")

  /**
    * Applies a tag to an existing composite revision.
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
  )(implicit subject: Subject): IO[CompositeViewRejection, ViewResource] = {
    for {
      pc  <- fetchContext.onModify(project)
      iri <- expandIri(id, pc)
      res <- eval(TagCompositeView(iri, project, tagRev, tag, rev, subject), pc)
    } yield res
  }.span("tagCompositeView")

  /**
    * Deprecates an existing composite view.
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
  )(implicit subject: Subject): IO[CompositeViewRejection, ViewResource] = {
    for {
      pc  <- fetchContext.onModify(project)
      iri <- expandIri(id, pc)
      res <- eval(DeprecateCompositeView(iri, project, rev, subject), pc)
    } yield res
  }.span("deprecateCompositeView")

  /**
    * Retrieves a current composite view resource.
    *
    * @param id
    *   the identifier that will be expanded to the Iri of the view with its optional rev/tag
    * @param project
    *   the view parent project
    */
  def fetch(id: IdSegmentRef, project: ProjectRef): IO[CompositeViewRejection, ViewResource] =
    fetchState(id, project).map { case (pc, state) =>
      state.toResource(pc.apiMappings, pc.base)
    }

  def fetchState(
      id: IdSegmentRef,
      project: ProjectRef
  ): IO[CompositeViewRejection, (ProjectContext, CompositeViewState)] = {
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
  }.span("fetchCompositeView")

  /**
    * Retrieves a current composite view resource and its selected projection.
    *
    * @param id
    *   the view identifier
    * @param projectionId
    *   the view projection identifier
    * @param project
    *   the view parent project
    */
  def fetchProjection(
      id: IdSegment,
      projectionId: IdSegment,
      project: ProjectRef
  ): IO[CompositeViewRejection, ViewProjectionResource]       =
    for {
      (p, view)     <- fetchState(id, project)
      projectionIri <- expandIri(projectionId, p)
      projection    <- IO.fromOption(
                         view.value.projections.value.find(_.id == projectionIri),
                         ProjectionNotFound(view.id, projectionIri, project)
                       )
    } yield view.toResource(p.apiMappings, p.base).map(_ -> projection)

  /**
    * Retrieves a current composite view resource and its selected source.
    *
    * @param id
    *   the view identifier
    * @param sourceId
    *   the view source identifier
    * @param project
    *   the view parent project
    */
  def fetchSource(
      id: IdSegment,
      sourceId: IdSegment,
      project: ProjectRef
  ): IO[CompositeViewRejection, ViewSourceResource]           =
    for {
      (p, view) <- fetchState(id, project)
      sourceIri <- expandIri(sourceId, p)
      source    <- IO.fromOption(
                     view.value.sources.value.find(_.id == sourceIri),
                     SourceNotFound(view.id, sourceIri, project)
                   )
    } yield view.toResource(p.apiMappings, p.base).map(_ -> source)

  /**
    * Retrieves a current composite view resource and its selected blazegraph projection.
    *
    * @param id
    *   the view identifier
    * @param projectionId
    *   the view projection identifier
    * @param project
    *   the view parent project
    */
  def fetchBlazegraphProjection(
      id: IdSegment,
      projectionId: IdSegment,
      project: ProjectRef
  ): IO[CompositeViewRejection, ViewSparqlProjectionResource] =
    fetchProjection(id, projectionId, project).flatMap { v =>
      val (view, projection) = v.value
      IO.fromOption(
        projection.asSparql.map(p => v.as(view -> p)),
        ProjectionNotFound(v.id, projection.id, project, SparqlProjectionType)
      )
    }

  /**
    * Retrieves a current composite view resource and its selected elasticsearch projection.
    *
    * @param id
    *   the view identifier
    * @param projectionId
    *   the view projection identifier
    * @param project
    *   the view parent project
    */
  def fetchElasticSearchProjection(
      id: IdSegment,
      projectionId: IdSegment,
      project: ProjectRef
  ): IO[CompositeViewRejection, ViewElasticSearchProjectionResource] =
    fetchProjection(id, projectionId, project).flatMap { v =>
      val (view, projection) = v.value
      IO.fromOption(
        projection.asElasticSearch.map(p => v.as(view -> p)),
        ProjectionNotFound(v.id, projection.id, project, ElasticSearchProjectionType)
      )
    }

  /**
    * Retrieves a list of CompositeViews using specific pagination, filter and ordering configuration.
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
      params: CompositeViewSearchParams,
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
              val res = state.toResource(pc.apiMappings, pc.base)
              params.matches(res).map(Option.when(_)(res))
            }
          )
      },
      pagination,
      ordering
    ).span("listCompositeViews")
  }

  private def eval(
      cmd: CompositeViewCommand,
      pc: ProjectContext
  ): IO[CompositeViewRejection, ViewResource] =
    log.evaluate(cmd.project, cmd.id, cmd).map(_._2.toResource(pc.apiMappings, pc.base))

}

object CompositeViews {

  final val entityType: EntityType = EntityType("compositeviews")

  type CompositeViewsLog = ScopedEventLog[
    Iri,
    CompositeViewState,
    CompositeViewCommand,
    CompositeViewEvent,
    CompositeViewRejection
  ]

  val expandIri: ExpandIri[InvalidCompositeViewId] = new ExpandIri(InvalidCompositeViewId.apply)

  private[compositeviews] def next(
      state: Option[CompositeViewState],
      event: CompositeViewEvent
  ): Option[CompositeViewState] = {

    def created(e: CompositeViewCreated): Option[CompositeViewState] = Option.when(state.isEmpty) {
      CompositeViewState(
        e.id,
        e.project,
        e.uuid,
        e.value,
        e.source,
        Tags.empty,
        e.rev,
        deprecated = false,
        e.instant,
        e.subject,
        e.instant,
        e.subject
      )
    }

    def updated(e: CompositeViewUpdated): Option[CompositeViewState] = state.map { s =>
      s.copy(rev = e.rev, value = e.value, source = e.source, updatedAt = e.instant, updatedBy = e.subject)
    }

    def tagAdded(e: CompositeViewTagAdded): Option[CompositeViewState] = state.map { s =>
      s.copy(rev = e.rev, tags = s.tags + (e.tag -> e.targetRev), updatedAt = e.instant, updatedBy = e.subject)
    }

    def deprecated(e: CompositeViewDeprecated): Option[CompositeViewState] = state.map { s =>
      s.copy(rev = e.rev, deprecated = true, updatedAt = e.instant, updatedBy = e.subject)
    }

    event match {
      case e: CompositeViewCreated    => created(e)
      case e: CompositeViewUpdated    => updated(e)
      case e: CompositeViewTagAdded   => tagAdded(e)
      case e: CompositeViewDeprecated => deprecated(e)
    }
  }

  private[compositeviews] def evaluate(
      validate: ValidateCompositeView
  )(state: Option[CompositeViewState], cmd: CompositeViewCommand)(implicit
      clock: Clock[UIO],
      uuidF: UUIDF
  ): IO[CompositeViewRejection, CompositeViewEvent] = {

    def create(c: CreateCompositeView) = state match {
      case None    =>
        for {
          t     <- IOUtils.instant
          u     <- uuidF()
          value <- CompositeViewValue(c.value, Map.empty, Map.empty, c.projectBase)
          _     <- validate(u, 1, value)
        } yield CompositeViewCreated(c.id, c.project, u, value, c.source, 1, t, c.subject)
      case Some(_) => IO.raiseError(ViewAlreadyExists(c.id, c.project))
    }

    def update(c: UpdateCompositeView) = state match {
      case None                      =>
        IO.raiseError(ViewNotFound(c.id, c.project))
      case Some(s) if s.rev != c.rev =>
        IO.raiseError(IncorrectRev(c.rev, s.rev))
      case Some(s) if s.deprecated   =>
        IO.raiseError(ViewIsDeprecated(c.id))
      case Some(s)                   =>
        for {
          value <- CompositeViewValue(
                     c.value,
                     s.value.sources.map(source => source.id -> source.uuid).toList.toMap,
                     s.value.projections.map(projection => projection.id -> projection.uuid).toList.toMap,
                     c.projectBase
                   )
          newRev = s.rev + 1
          _     <- validate(s.uuid, newRev, value)
          t     <- IOUtils.instant
        } yield CompositeViewUpdated(c.id, c.project, s.uuid, value, c.source, newRev, t, c.subject)
    }

    def tag(c: TagCompositeView) = state match {
      case None                                               =>
        IO.raiseError(ViewNotFound(c.id, c.project))
      case Some(s) if s.rev != c.rev                          =>
        IO.raiseError(IncorrectRev(c.rev, s.rev))
      case Some(s) if c.targetRev <= 0 || c.targetRev > s.rev =>
        IO.raiseError(RevisionNotFound(c.targetRev, s.rev))
      case Some(s)                                            =>
        IOUtils.instant.map(
          CompositeViewTagAdded(c.id, c.project, s.uuid, c.targetRev, c.tag, s.rev + 1, _, c.subject)
        )
    }

    def deprecate(c: DeprecateCompositeView) = state match {
      case None                      =>
        IO.raiseError(ViewNotFound(c.id, c.project))
      case Some(s) if s.rev != c.rev =>
        IO.raiseError(IncorrectRev(c.rev, s.rev))
      case Some(s) if s.deprecated   =>
        IO.raiseError(ViewIsDeprecated(c.id))
      case Some(s)                   =>
        IOUtils.instant.map(CompositeViewDeprecated(c.id, c.project, s.uuid, s.rev + 1, _, c.subject))
    }

    cmd match {
      case c: CreateCompositeView    => create(c)
      case c: UpdateCompositeView    => update(c)
      case c: TagCompositeView       => tag(c)
      case c: DeprecateCompositeView => deprecate(c)
    }
  }

  /**
    * The [[SourceProjectionId]] of a view source
    *
    * @param view
    *   the view
    * @param rev
    *   the revision of the view
    * @param sourceId
    *   the source Iri
    */
  def sourceProjection(view: CompositeView, rev: Int, sourceId: Iri): Option[SourceProjectionId] =
    view.sources.value.find(_.id == sourceId).map(sourceProjection(_, rev))

  /**
    * The [[SourceProjectionId]] of a view source
    *
    * @param source
    *   the view source
    * @param rev
    *   the revision of the view
    */
  def sourceProjection(source: CompositeViewSource, rev: Int): SourceProjectionId =
    SourceProjectionId(s"${source.uuid}_$rev")

  /**
    * All projection ids
    *
    * @param view
    *   the view
    * @param rev
    *   the revision of the view
    */
  def projectionIds(view: CompositeView, rev: Int): Set[(Iri, Iri, CompositeViewProjectionId)] =
    for {
      s: CompositeViewSource <- view.sources.toSortedSet.toSet
      p                      <- view.projections.toList
    } yield (s.id, p.id, projectionId(sourceProjection(s, rev), p, rev))

  import cats.implicits.catsKernelStdOrderForTuple2

  /**
    * The [[CompositeViewProjectionId]] s of a view projection.
    *
    * @param view
    *   the view
    * @param source
    *   the view source
    * @param rev
    *   the revision of the view
    */
  def projectionIds(
      view: CompositeView,
      source: CompositeViewSource,
      rev: Int
  ): Set[(Iri, CompositeViewProjectionId)] =
    view.projections.map(projection => projection.id -> projectionId(source, projection, rev)).toSortedSet

  /**
    * The [[CompositeViewProjectionId]] s of a view projection.
    *
    * @param view
    *   the view
    * @param projection
    *   the view projection
    * @param rev
    *   the revision of the view
    */
  def projectionIds(
      view: CompositeView,
      projection: CompositeViewProjection,
      rev: Int
  ): Set[(Iri, CompositeViewProjectionId)] =
    view.sources.value.map(source => source.id -> projectionId(source, projection, rev)).toSortedSet

  /**
    * The [[CompositeViewProjectionId]] of a view projection.
    *
    * @param source
    *   the view source
    * @param projection
    *   the view projection
    * @param rev
    *   the revision of the view
    */
  def projectionId(
      source: CompositeViewSource,
      projection: CompositeViewProjection,
      rev: Int
  ): CompositeViewProjectionId = {
    val sourceProjectionId = sourceProjection(source, rev)
    projectionId(sourceProjectionId, projection, rev)
  }

  /**
    * The [[CompositeViewProjectionId]] of a view projection
    *
    * @param sourceId
    *   the source projection id
    * @param projection
    *   the view projection
    * @param rev
    *   the revision of the view
    */
  def projectionId(
      sourceId: SourceProjectionId,
      projection: CompositeViewProjection,
      rev: Int
  ): CompositeViewProjectionId =
    projection match {
      case p: ElasticSearchProjection =>
        CompositeViewProjectionId(sourceId, ElasticSearchViews.projectionId(p.uuid, rev))
      case p: SparqlProjection        =>
        CompositeViewProjectionId(sourceId, BlazegraphViews.projectionId(p.uuid, rev))
    }

  /**
    * The Elasticsearch index for the passed projection
    *
    * @param projection
    *   the views' Elasticsearch projection
    * @param view
    *   the view
    * @param rev
    *   the view revision
    * @param prefix
    *   the index prefix
    */
  def index(projection: ElasticSearchProjection, view: CompositeView, rev: Int, prefix: String): IndexLabel =
    index(projection, view.uuid, rev, prefix)

  def index(projection: ElasticSearchProjection, uuid: UUID, rev: Int, prefix: String): IndexLabel = {
    val completePrefix = projection.indexGroup.fold(prefix) { i => s"${prefix}_$i" }
    IndexLabel.unsafe(s"${completePrefix}_${uuid}_${projection.uuid}_$rev")
  }

  /**
    * The Blazegraph namespace for the passed projection
    *
    * @param projection
    *   the views' Blazegraph projection
    * @param view
    *   the view
    * @param rev
    *   the view revision
    * @param prefix
    *   the namespace prefix
    */
  def namespace(projection: SparqlProjection, view: CompositeView, rev: Int, prefix: String): String =
    s"${prefix}_${view.uuid}_${projection.uuid}_$rev"

  def definition(validate: ValidateCompositeView, crypto: Crypto)(implicit
      clock: Clock[UIO],
      uuidF: UUIDF
  ): ScopedEntityDefinition[Iri, CompositeViewState, CompositeViewCommand, CompositeViewEvent, CompositeViewRejection] =
    ScopedEntityDefinition(
      entityType,
      StateMachine(None, evaluate(validate), next),
      CompositeViewEvent.serializer(crypto),
      CompositeViewState.serializer(crypto),
      Tagger[CompositeViewEvent](
        {
          case r: CompositeViewTagAdded => Some(r.tag -> r.targetRev)
          case _                        => None
        },
        { _ =>
          None
        }
      ),
      state =>
        Some(
          state.value.sources.value.foldLeft(Set.empty[EntityDependency]) {
            case (acc, s: CrossProjectSource) => acc + EntityDependency(s.project, s.project.toString)
            case (acc, _)                     => acc
          }
        ),
      onUniqueViolation = (id: Iri, c: CompositeViewCommand) =>
        c match {
          case c: CompositeViewCommand => ResourceAlreadyExists(id, c.project)
          case c                       => IncorrectRev(c.rev, c.rev + 1)
        }
    )

  def apply(
      fetchContext: FetchContext[CompositeViewRejection],
      contextResolution: ResolverContextResolution,
      validate: ValidateCompositeView,
      crypto: Crypto,
      config: CompositeViewsConfig,
      xas: Transactors
  )(implicit
      api: JsonLdApi,
      clock: Clock[UIO],
      uuidF: UUIDF
  ): Task[CompositeViews] =
    Task
      .delay(
        CompositeViewFieldsJsonLdSourceDecoder(uuidF, contextResolution, config.minIntervalRebuild)
      )
      .map { sourceDecoder =>
        new CompositeViews(
          ScopedEventLog(
            definition(validate, crypto),
            config.eventLog,
            xas
          ),
          fetchContext,
          sourceDecoder
        )
      }
}
