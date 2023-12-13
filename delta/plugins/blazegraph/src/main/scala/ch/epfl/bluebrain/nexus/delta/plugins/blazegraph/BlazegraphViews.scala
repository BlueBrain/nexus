package ch.epfl.bluebrain.nexus.delta.plugins.blazegraph

import cats.effect.{Clock, IO}
import cats.syntax.all._
import ch.epfl.bluebrain.nexus.delta.kernel.kamon.KamonMetricComponent
import ch.epfl.bluebrain.nexus.delta.kernel.utils.UUIDF
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.BlazegraphViews._
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.client.{BlazegraphClient, SparqlClientError}
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.indexing.IndexingViewDef
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.indexing.IndexingViewDef.{ActiveViewDef, DeprecatedViewDef}
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.model.BlazegraphView.IndexingBlazegraphView
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.model.BlazegraphViewCommand._
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.model.BlazegraphViewEvent._
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.model.BlazegraphViewRejection._
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.model.BlazegraphViewType.AggregateBlazegraphView
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.model.BlazegraphViewValue._
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.model._
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.api.JsonLdApi
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.RemoteContextResolution
import ch.epfl.bluebrain.nexus.delta.sdk.identities.model.Caller
import ch.epfl.bluebrain.nexus.delta.sdk.implicits._
import ch.epfl.bluebrain.nexus.delta.sdk.jsonld.ExpandIri
import ch.epfl.bluebrain.nexus.delta.sdk.jsonld.JsonLdSourceProcessor.JsonLdSourceResolvingDecoder
import ch.epfl.bluebrain.nexus.delta.sdk.model.IdSegmentRef.{Latest, Revision, Tag}
import ch.epfl.bluebrain.nexus.delta.sdk.model._
import ch.epfl.bluebrain.nexus.delta.sdk.projects.FetchContext
import ch.epfl.bluebrain.nexus.delta.sdk.projects.model.ApiMappings
import ch.epfl.bluebrain.nexus.delta.sdk.resolvers.ResolverContextResolution
import ch.epfl.bluebrain.nexus.delta.sourcing.ScopedEntityDefinition.Tagger
import ch.epfl.bluebrain.nexus.delta.sourcing._
import ch.epfl.bluebrain.nexus.delta.sourcing.config.EventLogConfig
import ch.epfl.bluebrain.nexus.delta.sourcing.model.EntityDependency.DependsOn
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.Subject
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Tag.UserTag
import ch.epfl.bluebrain.nexus.delta.sourcing.model._
import ch.epfl.bluebrain.nexus.delta.sourcing.offset.Offset
import io.circe.Json

import java.util.UUID

/**
  * Operations for handling Blazegraph views.
  */
final class BlazegraphViews(
    log: BlazegraphLog,
    fetchContext: FetchContext[BlazegraphViewRejection],
    sourceDecoder: JsonLdSourceResolvingDecoder[BlazegraphViewRejection, BlazegraphViewValue],
    createNamespace: ViewResource => IO[Unit],
    prefix: String
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
  def create(project: ProjectRef, source: Json)(implicit caller: Caller): IO[ViewResource] = {
    for {
      pc               <- fetchContext.onCreate(project)
      (iri, viewValue) <- sourceDecoder(project, pc, source)
      res              <- eval(CreateBlazegraphView(iri, project, viewValue, source, caller.subject))
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
  )(implicit caller: Caller): IO[ViewResource] = {
    for {
      pc        <- fetchContext.onCreate(project)
      iri       <- expandIri(id, pc)
      viewValue <- sourceDecoder(project, pc, iri, source)
      res       <- eval(CreateBlazegraphView(iri, project, viewValue, source, caller.subject))
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
  ): IO[ViewResource] = {
    for {
      pc    <- fetchContext.onCreate(project)
      iri   <- expandIri(id, pc)
      source = view.toJson(iri)
      res   <- eval(CreateBlazegraphView(iri, project, view, source, subject))
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
  )(implicit caller: Caller): IO[ViewResource] = {
    for {
      pc        <- fetchContext.onModify(project)
      iri       <- expandIri(id, pc)
      _         <- validateNotDefaultView(iri)
      viewValue <- sourceDecoder(project, pc, iri, source)
      res       <- eval(UpdateBlazegraphView(iri, project, viewValue, rev, source, caller.subject))
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
  ): IO[ViewResource] = {
    for {
      pc    <- fetchContext.onModify(project)
      iri   <- expandIri(id, pc)
      source = view.toJson(iri)
      res   <- eval(UpdateBlazegraphView(iri, project, view, rev, source, subject))
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
  )(implicit subject: Subject): IO[ViewResource] = {
    for {
      pc  <- fetchContext.onModify(project)
      iri <- expandIri(id, pc)
      _   <- validateNotDefaultView(iri)
      res <- eval(TagBlazegraphView(iri, project, tagRev, tag, rev, subject))
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
  )(implicit subject: Subject): IO[ViewResource] = {
    for {
      pc  <- fetchContext.onModify(project)
      iri <- expandIri(id, pc)
      _   <- validateNotDefaultView(iri)
      res <- eval(DeprecateBlazegraphView(iri, project, rev, subject))
    } yield res
  }.span("deprecateBlazegraphView")

  private def validateNotDefaultView(iri: Iri): IO[Unit] = {
    IO.raiseWhen(iri == defaultViewId)(ViewIsDefaultView)
  }

  /**
    * Undeprecate a view.
    *
    * @param id
    *   the view id
    * @param project
    *   the project to which the view belongs
    * @param rev
    *   the current revision of the view
    */
  def undeprecate(
      id: IdSegment,
      project: ProjectRef,
      rev: Int
  )(implicit subject: Subject): IO[ViewResource] = {
    for {
      pc  <- fetchContext.onModify(project)
      iri <- expandIri(id, pc)
      res <- eval(UndeprecateBlazegraphView(iri, project, rev, subject))
    } yield res
  }.span("undeprecateBlazegraphView")

  /**
    * Deprecate a view without applying preliminary checks on the project status
    *
    * @param id
    *   the view identifier
    * @param project
    *   the view parent project
    * @param rev
    *   the current view revision
    */
  private[blazegraph] def internalDeprecate(id: Iri, project: ProjectRef, rev: Int)(implicit
      subject: Subject
  ): IO[Unit] =
    eval(DeprecateBlazegraphView(id, project, rev, subject)).void

  /**
    * Fetch the latest revision of a view.
    *
    * @param id
    *   the identifier that will be expanded to the Iri of the view with its optional rev/tag
    * @param project
    *   the project to which the view belongs
    */
  def fetch(id: IdSegmentRef, project: ProjectRef): IO[ViewResource] =
    fetchState(id, project).map(_.toResource)

  def fetchState(
      id: IdSegmentRef,
      project: ProjectRef
  ): IO[BlazegraphViewState] = {
    for {
      pc      <- fetchContext.onRead(project)
      iri     <- expandIri(id.value, pc)
      notFound = ViewNotFound(iri, project)
      state   <- id match {
                   case Latest(_)        => log.stateOr(project, iri, notFound)
                   case Revision(_, rev) =>
                     log.stateOr(project, iri, rev, notFound, RevisionNotFound)
                   case Tag(_, tag)      =>
                     log.stateOr(project, iri, tag, notFound, TagNotFound(tag))
                 }
    } yield state
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
  ): IO[ActiveViewDef] =
    fetchState(id, project).flatMap { state =>
      IndexingViewDef(state, prefix) match {
        case Some(viewDef) =>
          viewDef match {
            case v: ActiveViewDef     => IO.pure(v)
            case v: DeprecatedViewDef =>
              IO.raiseError(ViewIsDeprecated(v.ref.viewId))
          }
        case None          =>
          IO.raiseError(
            DifferentBlazegraphViewType(state.id, AggregateBlazegraphView, BlazegraphViewType.IndexingBlazegraphView)
          )
      }
    }

  /**
    * Return the existing indexing views in a project in a finite stream
    */
  def currentIndexingViews(project: ProjectRef): ElemStream[IndexingViewDef] =
    log.currentStates(Scope.Project(project)).evalMapFilter { envelope =>
      IO.pure(toIndexViewDef(envelope))
    }

  /**
    * Return all existing indexing views in a finite stream
    */
  def currentIndexingViews: ElemStream[IndexingViewDef] =
    log.currentStates(Scope.Root).evalMapFilter { envelope =>
      IO.pure(toIndexViewDef(envelope))
    }

  /**
    * Return the indexing views in a non-ending stream
    */
  def indexingViews(start: Offset): ElemStream[IndexingViewDef] =
    log.states(Scope.Root, start).evalMapFilter { envelope =>
      IO.pure(toIndexViewDef(envelope))
    }

  private def toIndexViewDef(envelope: Envelope[BlazegraphViewState]) =
    envelope.toElem { v => Some(v.project) }.traverse { v =>
      IndexingViewDef(v, prefix)
    }

  private def eval(cmd: BlazegraphViewCommand): IO[ViewResource] =
    log.evaluate(cmd.project, cmd.id, cmd).map(_._2.toResource)
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

  def projectionName(state: BlazegraphViewState): String =
    projectionName(state.project, state.id, state.indexingRev)

  /**
    * Constructs a projectionId for a blazegraph view
    */
  def projectionName(project: ProjectRef, id: Iri, indexingRev: Int): String =
    s"blazegraph-$project-$id-$indexingRev"

  /**
    * Constructs the namespace for a Blazegraph view
    */
  def namespace(view: IndexingBlazegraphView, prefix: String): String =
    namespace(view.uuid, view.indexingRev, prefix)

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
          e.rev,
          deprecated = false,
          e.instant,
          e.subject,
          e.instant,
          e.subject
        )
      }

    def updated(e: BlazegraphViewUpdated): Option[BlazegraphViewState] = state.map { s =>
      val newIndexingRev =
        (e.value.asIndexingValue, s.value.asIndexingValue)
          .mapN(nextIndexingRev(_, _, s.indexingRev))
          .getOrElse(s.indexingRev)

      s.copy(
        rev = e.rev,
        indexingRev = newIndexingRev,
        value = e.value,
        source = e.source,
        updatedAt = e.instant,
        updatedBy = e.subject
      )
    }

    def tagAdded(e: BlazegraphViewTagAdded): Option[BlazegraphViewState] = state.map { s =>
      s.copy(rev = e.rev, tags = s.tags + (e.tag -> e.targetRev), updatedAt = e.instant, updatedBy = e.subject)
    }

    def deprecated(e: BlazegraphViewDeprecated): Option[BlazegraphViewState] = state.map { s =>
      s.copy(rev = e.rev, deprecated = true, updatedAt = e.instant, updatedBy = e.subject)
    }

    def undeprecated(e: BlazegraphViewUndeprecated): Option[BlazegraphViewState] = state.map { s =>
      s.copy(rev = e.rev, deprecated = false, updatedAt = e.instant, updatedBy = e.subject)
    }

    event match {
      case e: BlazegraphViewCreated      => created(e)
      case e: BlazegraphViewUpdated      => updated(e)
      case e: BlazegraphViewTagAdded     => tagAdded(e)
      case e: BlazegraphViewDeprecated   => deprecated(e)
      case e: BlazegraphViewUndeprecated => undeprecated(e)
    }
  }

  private[blazegraph] def evaluate(
      validate: ValidateBlazegraphView,
      clock: Clock[IO]
  )(state: Option[BlazegraphViewState], cmd: BlazegraphViewCommand)(implicit
      uuidF: UUIDF
  ): IO[BlazegraphViewEvent] = {

    def create(c: CreateBlazegraphView) = state match {
      case None    =>
        for {
          _ <- validate(c.value)
          t <- clock.realTimeInstant
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
          t <- clock.realTimeInstant
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
        clock.realTimeInstant.map(
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
        clock.realTimeInstant.map(
          BlazegraphViewDeprecated(c.id, c.project, s.value.tpe, s.uuid, s.rev + 1, _, c.subject)
        )
    }

    def undeprecate(c: UndeprecateBlazegraphView) = state match {
      case None                      =>
        IO.raiseError(ViewNotFound(c.id, c.project))
      case Some(s) if s.rev != c.rev =>
        IO.raiseError(IncorrectRev(c.rev, s.rev))
      case Some(s) if !s.deprecated  =>
        IO.raiseError(ViewIsNotDeprecated(c.id))
      case Some(s)                   =>
        clock.realTimeInstant.map(
          BlazegraphViewUndeprecated(c.id, c.project, s.value.tpe, s.uuid, s.rev + 1, _, c.subject)
        )
    }

    cmd match {
      case c: CreateBlazegraphView      => create(c)
      case c: UpdateBlazegraphView      => update(c)
      case c: TagBlazegraphView         => tag(c)
      case c: DeprecateBlazegraphView   => deprecate(c)
      case c: UndeprecateBlazegraphView => undeprecate(c)
    }
  }

  def definition(validate: ValidateBlazegraphView, clock: Clock[IO])(implicit uuidF: UUIDF): ScopedEntityDefinition[
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
        evaluate(validate, clock),
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
            Some(a.views.map { v => DependsOn(v.project, v.viewId) }.toSortedSet)
          case _: IndexingBlazegraphViewValue  => None
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
      xas: Transactors,
      clock: Clock[IO]
  )(implicit
      api: JsonLdApi,
      uuidF: UUIDF
  ): IO[BlazegraphViews] = {
    val createNameSpace = (v: ViewResource) =>
      v.value match {
        case i: IndexingBlazegraphView =>
          client
            .createNamespace(BlazegraphViews.namespace(i, prefix))
            .adaptError { case e: SparqlClientError => WrappedBlazegraphClientError(e) }
            .void
        case _                         => IO.unit
      }
    apply(fetchContext, contextResolution, validate, createNameSpace, eventLogConfig, prefix, xas, clock)
  }

  private[blazegraph] def apply(
      fetchContext: FetchContext[BlazegraphViewRejection],
      contextResolution: ResolverContextResolution,
      validate: ValidateBlazegraphView,
      createNamespace: ViewResource => IO[Unit],
      eventLogConfig: EventLogConfig,
      prefix: String,
      xas: Transactors,
      clock: Clock[IO]
  )(implicit
      api: JsonLdApi,
      uuidF: UUIDF
  ): IO[BlazegraphViews] = {
    implicit val rcr: RemoteContextResolution = contextResolution.rcr

    BlazegraphDecoderConfiguration.apply
      .map { implicit config =>
        new JsonLdSourceResolvingDecoder[BlazegraphViewRejection, BlazegraphViewValue](
          contexts.blazegraph,
          contextResolution,
          uuidF
        )
      }
      .map { sourceDecoder =>
        new BlazegraphViews(
          ScopedEventLog(
            definition(validate, clock),
            eventLogConfig,
            xas
          ),
          fetchContext,
          sourceDecoder,
          createNamespace,
          prefix
        )
      }

  }
}
