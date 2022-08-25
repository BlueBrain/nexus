package ch.epfl.bluebrain.nexus.delta.plugins.blazegraph

import cats.effect.Clock
import cats.implicits._
import ch.epfl.bluebrain.nexus.delta.kernel.database.Transactors
import ch.epfl.bluebrain.nexus.delta.kernel.kamon.KamonMetricComponent
import ch.epfl.bluebrain.nexus.delta.kernel.utils.{IOUtils, UUIDF}
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.BlazegraphViews._
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.client.BlazegraphClient
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.model.BlazegraphView.{AggregateBlazegraphView, IndexingBlazegraphView}
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.model.BlazegraphViewCommand._
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.model.BlazegraphViewEvent._
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.model.BlazegraphViewRejection._
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.model.BlazegraphViewType.{AggregateBlazegraphView => AggregateBlazegraphViewType, IndexingBlazegraphView => IndexingBlazegraphViewType}
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.model.BlazegraphViewValue._
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.model._
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.api.JsonLdApi
import ch.epfl.bluebrain.nexus.delta.sdk.identities.model.Caller
import ch.epfl.bluebrain.nexus.delta.sdk.implicits._
import ch.epfl.bluebrain.nexus.delta.sdk.jsonld.ExpandIri
import ch.epfl.bluebrain.nexus.delta.sdk.jsonld.JsonLdSourceProcessor.JsonLdSourceResolvingDecoder
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
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{EntityDependency, EntityType, ProjectRef}
import ch.epfl.bluebrain.nexus.delta.sourcing.projections.ProjectionId.ViewProjectionId
import io.circe.Json
import monix.bio.{IO, Task, UIO}

import java.util.UUID

/**
  * Operations for handling Blazegraph views.
  */
final class BlazegraphViews(
    log: BlazegraphLog,
    fetchContext: FetchContext[BlazegraphViewRejection],
    sourceDecoder: JsonLdSourceResolvingDecoder[BlazegraphViewRejection, BlazegraphViewValue],
    createNamespace: ViewResource => IO[BlazegraphViewRejection, Unit]
) {

  implicit private val kamonComponent: KamonMetricComponent = KamonMetricComponent(entityType.value)

  /**
    * Create a new Blazegraph view where the id is either present on the payload or self generated.
    *
    * @param project
    *   the project of to which the view belongs
    * @param source
    *   the payload to create the view
    */
  def create(project: ProjectRef, source: Json)(implicit caller: Caller): IO[BlazegraphViewRejection, ViewResource] = {
    for {
      pc               <- fetchContext.onCreate(project)
      (iri, viewValue) <- sourceDecoder(project, pc, source)
      res              <- eval(CreateBlazegraphView(iri, project, viewValue, source, caller.subject), pc)
      _                <- createNamespace(res)
    } yield res
  }.span("createBlazegraphView")

  /**
    * Create a new view with the provided id.
    *
    * @param id
    *   the view identifier
    * @param project
    *   the project to which the view belongs
    * @param source
    *   the payload to create the view
    */
  def create(
      id: IdSegment,
      project: ProjectRef,
      source: Json
  )(implicit caller: Caller): IO[BlazegraphViewRejection, ViewResource] = {
    for {
      pc        <- fetchContext.onCreate(project)
      iri       <- expandIri(id, pc)
      viewValue <- sourceDecoder(project, pc, iri, source)
      res       <- eval(CreateBlazegraphView(iri, project, viewValue, source, caller.subject), pc)
      _         <- createNamespace(res)
    } yield res
  }.span("createBlazegraphView")

  /**
    * Create a new view with the provided id and the [[BlazegraphViewValue]] instead of [[Json]] payload.
    * @param id
    *   the view identifier
    * @param project
    *   the project to which the view belongs
    * @param view
    *   the value of the view
    */
  def create(id: IdSegment, project: ProjectRef, view: BlazegraphViewValue)(implicit
      subject: Subject
  ): IO[BlazegraphViewRejection, ViewResource] = {
    for {
      pc    <- fetchContext.onCreate(project)
      iri   <- expandIri(id, pc)
      source = view.toJson(iri)
      res   <- eval(CreateBlazegraphView(iri, project, view, source, subject), pc)
      _     <- createNamespace(res)
    } yield res
  }.span("createBlazegraphView")

  /**
    * Update an existing view with [[Json]] source.
    * @param id
    *   the view identifier
    * @param project
    *   the project to which the view belongs
    * @param rev
    *   the current revision of the view
    * @param source
    *   the view source
    */
  def update(
      id: IdSegment,
      project: ProjectRef,
      rev: Int,
      source: Json
  )(implicit caller: Caller): IO[BlazegraphViewRejection, ViewResource] = {
    for {
      pc        <- fetchContext.onModify(project)
      iri       <- expandIri(id, pc)
      viewValue <- sourceDecoder(project, pc, iri, source)
      res       <- eval(UpdateBlazegraphView(iri, project, viewValue, rev, source, caller.subject), pc)
      _         <- createNamespace(res)
    } yield res
  }.span("updateBlazegraphView")

  /**
    * Update an existing view.
    *
    * @param id
    *   the identifier of the view
    * @param project
    *   the project to which the view belongs
    * @param rev
    *   the current revision of the view
    * @param view
    *   the view value
    */
  def update(id: IdSegment, project: ProjectRef, rev: Int, view: BlazegraphViewValue)(implicit
      subject: Subject
  ): IO[BlazegraphViewRejection, ViewResource] = {
    for {
      pc    <- fetchContext.onModify(project)
      iri   <- expandIri(id, pc)
      source = view.toJson(iri)
      res   <- eval(UpdateBlazegraphView(iri, project, view, rev, source, subject), pc)
      _     <- createNamespace(res)
    } yield res
  }.span("updateBlazegraphView")

  /**
    * Add a tag to an existing view.
    *
    * @param id
    *   the id of the view
    * @param project
    *   the project to which the view belongs
    * @param tag
    *   the tag label
    * @param tagRev
    *   the target revision of the tag
    * @param rev
    *   the current revision of the view
    */
  def tag(
      id: IdSegment,
      project: ProjectRef,
      tag: UserTag,
      tagRev: Int,
      rev: Int
  )(implicit subject: Subject): IO[BlazegraphViewRejection, ViewResource] = {
    for {
      pc  <- fetchContext.onModify(project)
      iri <- expandIri(id, pc)
      res <- eval(TagBlazegraphView(iri, project, tagRev, tag, rev, subject), pc)
      _   <- createNamespace(res)
    } yield res
  }.span("tagBlazegraphView")

  /**
    * Deprecate a view.
    *
    * @param id
    *   the view id
    * @param project
    *   the project to which the view belongs
    * @param rev
    *   the current revision of the view
    */
  def deprecate(
      id: IdSegment,
      project: ProjectRef,
      rev: Int
  )(implicit subject: Subject): IO[BlazegraphViewRejection, ViewResource] = {
    for {
      pc  <- fetchContext.onModify(project)
      iri <- expandIri(id, pc)
      res <- eval(DeprecateBlazegraphView(iri, project, rev, subject), pc)
    } yield res
  }.span("deprecateBlazegraphView")

  /**
    * Fetch the latest revision of a view.
    *
    * @param id
    *   the identifier that will be expanded to the Iri of the view with its optional rev/tag
    * @param project
    *   the project to which the view belongs
    */
  def fetch(id: IdSegmentRef, project: ProjectRef): IO[BlazegraphViewRejection, ViewResource] =
    fetchState(id, project).map { case (pc, state) =>
      state.toResource(pc.apiMappings, pc.base)
    }

  def fetchState(
      id: IdSegmentRef,
      project: ProjectRef
  ): IO[BlazegraphViewRejection, (ProjectContext, BlazegraphViewState)] = {
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
  }.span("fetchBlazegraphView")

  /**
    * Retrieves a current [[IndexingBlazegraphView]] resource.
    *
    * @param id
    *   the identifier that will be expanded to the Iri of the view with its optional rev/tag
    * @param project
    *   the view parent project
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

  /**
    * List views.
    *
    * @param pagination
    *   the pagination settings
    * @param params
    *   filtering parameters for the listing
    * @param ordering
    *   the response ordering
    */
  def list(
      pagination: FromPagination,
      params: BlazegraphViewSearchParams,
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
                state.toResource(pc.apiMappings, pc.base)
              params.matches(res).map(Option.when(_)(res))
            }
          )
      },
      pagination,
      ordering
    )
      .span("listBlazegraphViews")
  }

  private def eval(cmd: BlazegraphViewCommand, pc: ProjectContext): IO[BlazegraphViewRejection, ViewResource] =
    log
      .evaluate(cmd.project, cmd.id, cmd)
      .map(_._2.toResource(pc.apiMappings, pc.base))
}

object BlazegraphViews {

  final val entityType: EntityType = EntityType("blazegraph")

  type BlazegraphLog = ScopedEventLog[
    Iri,
    BlazegraphViewState,
    BlazegraphViewCommand,
    BlazegraphViewEvent,
    BlazegraphViewRejection
  ]

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
    ViewProjectionId(s"blazegraph-${uuid}_$rev")

  /**
    * Constructs the namespace for a Blazegraph view
    */
  def namespace(view: IndexingViewResource, prefix: String): String =
    namespace(view.value.uuid, view.rev.toInt, prefix)

  /**
    * Constructs the namespace for a Blazegraph view
    */
  def namespace(uuid: UUID, rev: Int, prefix: String): String =
    s"${prefix}_${uuid}_$rev"

  /**
    * The default Blazegraph API mappings
    */
  val mappings: ApiMappings = ApiMappings("view" -> schema.original, "graph" -> defaultViewId)

  private[blazegraph] def next(
      state: Option[BlazegraphViewState],
      event: BlazegraphViewEvent
  ): Option[BlazegraphViewState] = {

    def created(e: BlazegraphViewCreated): Option[BlazegraphViewState] =
      Option.when(state.isEmpty) {
        BlazegraphViewState(
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

    def updated(e: BlazegraphViewUpdated): Option[BlazegraphViewState] = state.map { s =>
      s.copy(rev = e.rev, value = e.value, source = e.source, updatedAt = e.instant, updatedBy = e.subject)
    }

    def tagAdded(e: BlazegraphViewTagAdded): Option[BlazegraphViewState] = state.map { s =>
      s.copy(rev = e.rev, tags = s.tags + (e.tag -> e.targetRev), updatedAt = e.instant, updatedBy = e.subject)
    }

    def deprecated(e: BlazegraphViewDeprecated): Option[BlazegraphViewState] = state.map { s =>
      s.copy(rev = e.rev, deprecated = true, updatedAt = e.instant, updatedBy = e.subject)
    }

    event match {
      case e: BlazegraphViewCreated    => created(e)
      case e: BlazegraphViewUpdated    => updated(e)
      case e: BlazegraphViewTagAdded   => tagAdded(e)
      case e: BlazegraphViewDeprecated => deprecated(e)
    }
  }

  private[blazegraph] def evaluate(
      validate: ValidateBlazegraphView
  )(state: Option[BlazegraphViewState], cmd: BlazegraphViewCommand)(implicit
      clock: Clock[UIO],
      uuidF: UUIDF
  ): IO[BlazegraphViewRejection, BlazegraphViewEvent] = {

    def create(c: CreateBlazegraphView) = state match {
      case None    =>
        for {
          _ <- validate(c.value)
          t <- IOUtils.instant
          u <- uuidF()
        } yield BlazegraphViewCreated(c.id, c.project, u, c.value, c.source, 1, t, c.subject)
      case Some(_) => IO.raiseError(ResourceAlreadyExists(c.id, c.project))
    }

    def update(c: UpdateBlazegraphView) = state match {
      case None                                  =>
        IO.raiseError(ViewNotFound(c.id, c.project))
      case Some(s) if s.rev != c.rev             =>
        IO.raiseError(IncorrectRev(c.rev, s.rev))
      case Some(s) if s.deprecated               =>
        IO.raiseError(ViewIsDeprecated(c.id))
      case Some(s) if c.value.tpe != s.value.tpe =>
        IO.raiseError(DifferentBlazegraphViewType(s.id, c.value.tpe, s.value.tpe))
      case Some(s)                               =>
        for {
          _ <- validate(c.value)
          t <- IOUtils.instant
        } yield BlazegraphViewUpdated(c.id, c.project, s.uuid, c.value, c.source, s.rev + 1, t, c.subject)
    }

    def tag(c: TagBlazegraphView) = state match {
      case None                                               =>
        IO.raiseError(ViewNotFound(c.id, c.project))
      case Some(s) if s.rev != c.rev                          =>
        IO.raiseError(IncorrectRev(c.rev, s.rev))
      case Some(s) if c.targetRev <= 0 || c.targetRev > s.rev =>
        IO.raiseError(RevisionNotFound(c.targetRev, s.rev))
      case Some(s)                                            =>
        IOUtils.instant.map(
          BlazegraphViewTagAdded(c.id, c.project, s.value.tpe, s.uuid, c.targetRev, c.tag, s.rev + 1, _, c.subject)
        )
    }

    def deprecate(c: DeprecateBlazegraphView) = state match {
      case None                      =>
        IO.raiseError(ViewNotFound(c.id, c.project))
      case Some(s) if s.rev != c.rev =>
        IO.raiseError(IncorrectRev(c.rev, s.rev))
      case Some(s) if s.deprecated   =>
        IO.raiseError(ViewIsDeprecated(c.id))
      case Some(s)                   =>
        IOUtils.instant.map(BlazegraphViewDeprecated(c.id, c.project, s.value.tpe, s.uuid, s.rev + 1, _, c.subject))
    }

    cmd match {
      case c: CreateBlazegraphView    => create(c)
      case c: UpdateBlazegraphView    => update(c)
      case c: TagBlazegraphView       => tag(c)
      case c: DeprecateBlazegraphView => deprecate(c)
    }
  }

  def definition(validate: ValidateBlazegraphView)(implicit clock: Clock[UIO], uuidF: UUIDF): ScopedEntityDefinition[
    Iri,
    BlazegraphViewState,
    BlazegraphViewCommand,
    BlazegraphViewEvent,
    BlazegraphViewRejection
  ] =
    ScopedEntityDefinition(
      entityType,
      StateMachine(
        None,
        evaluate(validate),
        next
      ),
      BlazegraphViewEvent.serializer,
      BlazegraphViewState.serializer,
      Tagger[BlazegraphViewEvent](
        {
          case r: BlazegraphViewTagAdded => Some(r.tag -> r.targetRev)
          case _                         => None
        },
        { _ =>
          None
        }
      ),
      { s =>
        s.value match {
          case a: AggregateBlazegraphViewValue =>
            Some(a.views.value.map { v => EntityDependency(v.project, v.viewId.toString) })
          case _                               => None
        }
      },
      onUniqueViolation = (id: Iri, c: BlazegraphViewCommand) =>
        c match {
          case c: CreateBlazegraphView => ResourceAlreadyExists(id, c.project)
          case c                       => IncorrectRev(c.rev, c.rev + 1)
        }
    )

  /**
    * Constructs a [[BlazegraphViews]] instance.
    */
  def apply(
      fetchContext: FetchContext[BlazegraphViewRejection],
      contextResolution: ResolverContextResolution,
      validate: ValidateBlazegraphView,
      client: BlazegraphClient,
      eventLogConfig: EventLogConfig,
      prefix: String,
      xas: Transactors
  )(implicit
      api: JsonLdApi,
      clock: Clock[UIO],
      uuidF: UUIDF
  ): Task[BlazegraphViews] = {
    val createNameSpace = (v: ViewResource) =>
      v.value match {
        case i: IndexingBlazegraphView =>
          client
            .createNamespace(BlazegraphViews.namespace(v.as(i), prefix))
            .mapError(WrappedBlazegraphClientError.apply)
            .void
        case _                         => IO.unit
      }
    apply(fetchContext, contextResolution, validate, createNameSpace, eventLogConfig, xas)
  }

  private[blazegraph] def apply(
      fetchContext: FetchContext[BlazegraphViewRejection],
      contextResolution: ResolverContextResolution,
      validate: ValidateBlazegraphView,
      createNamespace: ViewResource => IO[BlazegraphViewRejection, Unit],
      eventLogConfig: EventLogConfig,
      xas: Transactors
  )(implicit
      api: JsonLdApi,
      clock: Clock[UIO],
      uuidF: UUIDF
  ): Task[BlazegraphViews] =
    Task
      .delay(
        new JsonLdSourceResolvingDecoder[BlazegraphViewRejection, BlazegraphViewValue](
          contexts.blazegraph,
          contextResolution,
          uuidF
        )
      )
      .map { sourceDecoder =>
        new BlazegraphViews(
          ScopedEventLog(
            definition(validate),
            eventLogConfig,
            xas
          ),
          fetchContext,
          sourceDecoder,
          createNamespace
        )
      }
}
