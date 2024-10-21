package ch.epfl.bluebrain.nexus.delta.plugins.compositeviews

import cats.effect.{Clock, IO}
import ch.epfl.bluebrain.nexus.delta.kernel.kamon.KamonMetricComponent
import ch.epfl.bluebrain.nexus.delta.kernel.search.Pagination.FromPagination
import ch.epfl.bluebrain.nexus.delta.kernel.utils.UUIDF
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.CompositeViews._
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.indexing.CompositeViewDef
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.indexing.CompositeViewDef.{ActiveViewDef, DeprecatedViewDef}
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.model.CompositeViewCommand._
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.model.CompositeViewEvent._
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.model.CompositeViewRejection._
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.model.CompositeViewSource._
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.model._
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.serialization.CompositeViewFieldsJsonLdSourceDecoder
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.api.JsonLdApi
import ch.epfl.bluebrain.nexus.delta.sdk.identities.model.Caller
import ch.epfl.bluebrain.nexus.delta.sdk.implicits._
import ch.epfl.bluebrain.nexus.delta.sdk.jsonld.ExpandIri
import ch.epfl.bluebrain.nexus.delta.sdk.model.IdSegmentRef.{Latest, Revision, Tag}
import ch.epfl.bluebrain.nexus.delta.sdk.model._
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.SearchResults
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.SearchResults.UnscoredSearchResults
import ch.epfl.bluebrain.nexus.delta.sdk.projects.{FetchContext, Projects}
import ch.epfl.bluebrain.nexus.delta.sdk.resolvers.ResolverContextResolution
import ch.epfl.bluebrain.nexus.delta.sdk.views.IndexingRev
import ch.epfl.bluebrain.nexus.delta.sourcing._
import ch.epfl.bluebrain.nexus.delta.sourcing.config.EventLogConfig
import ch.epfl.bluebrain.nexus.delta.sourcing.model.EntityDependency.DependsOn
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.Subject
import ch.epfl.bluebrain.nexus.delta.sourcing.model._
import ch.epfl.bluebrain.nexus.delta.sourcing.offset.Offset
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.Elem
import io.circe.Json

import scala.concurrent.duration.FiniteDuration

/**
  * Composite views resource lifecycle operations.
  */
final class CompositeViews private (
    log: CompositeViewsLog,
    fetchContext: FetchContext,
    sourceDecoder: CompositeViewFieldsJsonLdSourceDecoder
) {

  implicit private val kamonComponent: KamonMetricComponent = KamonMetricComponent(entityType.value)

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
  def create(id: IdSegment, project: ProjectRef, value: CompositeViewFields)(implicit
      subject: Subject,
      baseUri: BaseUri
  ): IO[ViewResource] = {
    for {
      pc  <- fetchContext.onCreate(project)
      iri <- expandIri(id, pc)
      res <- eval(CreateCompositeView(iri, project, value, value.toJson(iri), subject, pc.base))
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
  def create(project: ProjectRef, source: Json)(implicit caller: Caller): IO[ViewResource] = {
    for {
      pc           <- fetchContext.onCreate(project)
      (iri, value) <- sourceDecoder(project, pc, source)
      res          <- eval(CreateCompositeView(iri, project, value, source, caller.subject, pc.base))
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
  def create(id: IdSegment, project: ProjectRef, source: Json)(implicit caller: Caller): IO[ViewResource] = {
    for {
      pc        <- fetchContext.onCreate(project)
      iri       <- expandIri(id, pc)
      viewValue <- sourceDecoder(project, pc, iri, source)
      res       <- eval(CreateCompositeView(iri, project, viewValue, source, caller.subject, pc.base))
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
  ): IO[ViewResource] = {
    for {
      pc    <- fetchContext.onModify(project)
      iri   <- expandIri(id, pc)
      source = value.toJson(iri)
      res   <- eval(UpdateCompositeView(iri, project, rev, value, source, subject, pc.base))
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
  def update(id: IdSegment, project: ProjectRef, rev: Int, source: Json)(implicit caller: Caller): IO[ViewResource] = {
    for {
      pc        <- fetchContext.onModify(project)
      iri       <- expandIri(id, pc)
      viewValue <- sourceDecoder(project, pc, iri, source)
      res       <- eval(UpdateCompositeView(iri, project, rev, viewValue, source, caller.subject, pc.base))
    } yield res
  }.span("updateCompositeView")

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
  )(implicit subject: Subject): IO[ViewResource] = {
    for {
      pc  <- fetchContext.onModify(project)
      iri <- expandIri(id, pc)
      res <- eval(DeprecateCompositeView(iri, project, rev, subject))
    } yield res
  }.span("deprecateCompositeView")

  /**
    * Undeprecates an existing composite view.
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
  def undeprecate(
      id: IdSegment,
      project: ProjectRef,
      rev: Int
  )(implicit subject: Subject): IO[ViewResource] = {
    for {
      pc  <- fetchContext.onModify(project)
      iri <- expandIri(id, pc)
      res <- eval(UndeprecateCompositeView(iri, project, rev, subject))
    } yield res
  }.span("undeprecateCompositeView")

  /**
    * Deprecates an existing composite view without applying preliminary checks on the project status
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
  private[compositeviews] def internalDeprecate(id: Iri, project: ProjectRef, rev: Int)(implicit
      subject: Subject
  ): IO[Unit] =
    eval(DeprecateCompositeView(id, project, rev, subject)).void

  /**
    * Retrieves a current composite view resource.
    *
    * @param id
    *   the identifier that will be expanded to the Iri of the view with its optional rev/tag
    * @param project
    *   the view parent project
    */
  def fetch(id: IdSegmentRef, project: ProjectRef): IO[ViewResource] =
    fetchState(id, project).map(_.toResource)

  def fetchState(
      id: IdSegmentRef,
      project: ProjectRef
  ): IO[CompositeViewState] = {
    for {
      pc      <- fetchContext.onRead(project)
      iri     <- expandIri(id.value, pc)
      notFound = ViewNotFound(iri, project)
      state   <- id match {
                   case Latest(_)        => log.stateOr(project, iri, notFound)
                   case Revision(_, rev) => log.stateOr(project, iri, rev, notFound, RevisionNotFound)
                   case t: Tag           => IO.raiseError(FetchByTagNotSupported(t))
                 }
    } yield state
  }.span("fetchCompositeView")

  /**
    * Fetch a non-deprecated view as an active view
    */
  def fetchIndexingView(id: IdSegmentRef, project: ProjectRef): IO[ActiveViewDef] =
    fetchState(id, project)
      .flatMap { state =>
        CompositeViewDef(state) match {
          case v: ActiveViewDef     => IO.pure(v)
          case d: DeprecatedViewDef => IO.raiseError(ViewIsDeprecated(d.ref.viewId))
        }
      }

  /**
    * Attempts to expand the segment to get back an [[Iri]]
    */
  def expand(id: IdSegmentRef, project: ProjectRef): IO[Iri] =
    fetchContext.onRead(project).flatMap(pc => expandIri(id.value, pc))

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
  ): IO[UnscoredSearchResults[ViewResource]] = {
    val scope = params.project.fold[Scope](Scope.Root)(ref => Scope.Project(ref))
    SearchResults(
      log.currentStates(scope, _.toResource).evalFilter(params.matches),
      pagination,
      ordering
    ).span("listCompositeViews")
  }

  /**
    * Return all existing views for the given project in a finite stream
    */
  def currentViews(project: ProjectRef): SuccessElemStream[CompositeViewDef] =
    log.currentStates(Scope.Project(project)).map(toCompositeViewDef)

  /**
    * Return all existing views for all projects in a finite stream
    */
  def currentViews: SuccessElemStream[CompositeViewDef] =
    log.currentStates(Scope.Root).map(toCompositeViewDef)

  /**
    * Return the indexing views in a non-ending stream
    */
  def views(start: Offset): SuccessElemStream[CompositeViewDef] =
    log.states(Scope.Root, start).map(toCompositeViewDef)

  private def toCompositeViewDef(elem: Elem.SuccessElem[CompositeViewState]) =
    elem.mapValue { v => CompositeViewDef(v) }

  private def eval(cmd: CompositeViewCommand): IO[ViewResource] =
    log.evaluate(cmd.project, cmd.id, cmd).map(_._2.toResource)

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

    def undeprecate(e: CompositeViewUndeprecated): Option[CompositeViewState] = state.map { s =>
      s.copy(rev = e.rev, deprecated = false, updatedAt = e.instant, updatedBy = e.subject)
    }

    event match {
      case e: CompositeViewCreated      => created(e)
      case e: CompositeViewUpdated      => updated(e)
      case e: CompositeViewTagAdded     => tagAdded(e)
      case e: CompositeViewDeprecated   => deprecated(e)
      case e: CompositeViewUndeprecated => undeprecate(e)
    }
  }

  private[compositeviews] def evaluate(
      validate: ValidateCompositeView,
      clock: Clock[IO]
  )(state: Option[CompositeViewState], cmd: CompositeViewCommand)(implicit
      uuidF: UUIDF
  ): IO[CompositeViewEvent] = {

    def create(c: CreateCompositeView) = state match {
      case None    =>
        for {
          t     <- clock.realTimeInstant
          u     <- uuidF()
          value <- CompositeViewFactory.create(c.value)(c.projectBase, uuidF)
          _     <- validate(u, value)
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
        val newRev         = s.rev + 1
        val newIndexingRev = IndexingRev(newRev)
        for {
          value <- CompositeViewFactory.update(c.value, s.value, newIndexingRev)(c.projectBase, uuidF)
          _     <- validate(s.uuid, value)
          t     <- clock.realTimeInstant
        } yield CompositeViewUpdated(c.id, c.project, s.uuid, value, c.source, newRev, t, c.subject)
    }

    def deprecate(c: DeprecateCompositeView) = state match {
      case None                      =>
        IO.raiseError(ViewNotFound(c.id, c.project))
      case Some(s) if s.rev != c.rev =>
        IO.raiseError(IncorrectRev(c.rev, s.rev))
      case Some(s) if s.deprecated   =>
        IO.raiseError(ViewIsDeprecated(c.id))
      case Some(s)                   =>
        clock.realTimeInstant.map(CompositeViewDeprecated(c.id, c.project, s.uuid, s.rev + 1, _, c.subject))
    }

    def undeprecate(c: UndeprecateCompositeView) = state match {
      case None                      =>
        IO.raiseError(ViewNotFound(c.id, c.project))
      case Some(s) if s.rev != c.rev =>
        IO.raiseError(IncorrectRev(c.rev, s.rev))
      case Some(s) if !s.deprecated  =>
        IO.raiseError(ViewIsNotDeprecated(c.id))
      case Some(s)                   =>
        clock.realTimeInstant.map(CompositeViewUndeprecated(c.id, c.project, s.uuid, s.rev + 1, _, c.subject))
    }

    cmd match {
      case c: CreateCompositeView      => create(c)
      case c: UpdateCompositeView      => update(c)
      case c: DeprecateCompositeView   => deprecate(c)
      case c: UndeprecateCompositeView => undeprecate(c)
    }
  }

  def definition(validate: ValidateCompositeView, clock: Clock[IO])(implicit
      uuidF: UUIDF
  ): ScopedEntityDefinition[Iri, CompositeViewState, CompositeViewCommand, CompositeViewEvent, CompositeViewRejection] =
    ScopedEntityDefinition.untagged(
      entityType,
      StateMachine(None, evaluate(validate, clock)(_, _), next),
      CompositeViewEvent.serializer,
      CompositeViewState.serializer,
      state =>
        Some(
          state.value.sources.foldLeft(Set.empty[DependsOn]) {
            case (acc, _: ProjectSource)       => acc
            case (acc, s: CrossProjectSource)  => acc + DependsOn(s.project, Projects.encodeId(s.project))
            case (acc, _: RemoteProjectSource) => acc
          }
        ),
      onUniqueViolation = (id: Iri, c: CompositeViewCommand) =>
        c match {
          case c: CompositeViewCommand => ResourceAlreadyExists(id, c.project)
          case c                       => IncorrectRev(c.rev, c.rev + 1)
        }
    )

  def apply(
      fetchContext: FetchContext,
      contextResolution: ResolverContextResolution,
      validate: ValidateCompositeView,
      minIntervalRebuild: FiniteDuration,
      eventLogConfig: EventLogConfig,
      xas: Transactors,
      clock: Clock[IO]
  )(implicit
      api: JsonLdApi,
      uuidF: UUIDF
  ): IO[CompositeViews] =
    IO
      .delay(
        CompositeViewFieldsJsonLdSourceDecoder(uuidF, contextResolution, minIntervalRebuild)
      )
      .map { sourceDecoder =>
        new CompositeViews(
          ScopedEventLog(
            definition(validate, clock),
            eventLogConfig,
            xas
          ),
          fetchContext,
          sourceDecoder
        )
      }
}
