package ch.epfl.bluebrain.nexus.delta.plugins.compositeviews

import cats.effect.{Clock, ContextShift, IO, Timer}
import ch.epfl.bluebrain.nexus.delta.kernel.kamon.KamonMetricComponent
import ch.epfl.bluebrain.nexus.delta.kernel.search.Pagination.FromPagination
import ch.epfl.bluebrain.nexus.delta.kernel.syntax.kamonSyntax
import ch.epfl.bluebrain.nexus.delta.kernel.utils.{IOInstant, UUIDF}
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.CompositeViews._
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.config.CompositeViewsConfig
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
import ch.epfl.bluebrain.nexus.delta.sourcing.ScopedEntityDefinition.Tagger
import ch.epfl.bluebrain.nexus.delta.sourcing._
import ch.epfl.bluebrain.nexus.delta.sourcing.model.EntityDependency.DependsOn
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.Subject
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Tag.UserTag
import ch.epfl.bluebrain.nexus.delta.sourcing.model._
import ch.epfl.bluebrain.nexus.delta.sourcing.offset.Offset
import io.circe.Json

/**
  * Composite views resource lifecycle operations.
  */
final class CompositeViews private (
    log: CompositeViewsLog,
    fetchContext: FetchContext[CompositeViewRejection],
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
  )(implicit subject: Subject): IO[ViewResource] = {
    for {
      pc  <- fetchContext.onModify(project)
      iri <- expandIri(id, pc)
      res <- eval(TagCompositeView(iri, project, tagRev, tag, rev, subject))
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
  )(implicit subject: Subject): IO[ViewResource] = {
    for {
      pc  <- fetchContext.onModify(project)
      iri <- expandIri(id, pc)
      res <- eval(DeprecateCompositeView(iri, project, rev, subject))
    } yield res
  }.span("deprecateCompositeView")

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
                   case Revision(_, rev) =>
                     log.stateOr(project, iri, rev, notFound, RevisionNotFound)
                   case Tag(_, tag)      =>
                     log.stateOr(project, iri, tag, notFound, TagNotFound(tag))
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
  def currentViews(project: ProjectRef): ElemStream[CompositeViewDef] =
    log.currentStates(Scope.Project(project)).map(toCompositeViewDef)

  /**
    * Return all existing indexing views in a finite stream
    */
  def currentViews: ElemStream[CompositeViewDef] =
    log.currentStates(Scope.Root).map(toCompositeViewDef)

  /**
    * Return the indexing views in a non-ending stream
    */
  def views(start: Offset): ElemStream[CompositeViewDef] =
    log.states(Scope.Root, start).map(toCompositeViewDef)

  private def toCompositeViewDef(envelope: Envelope[CompositeViewState]) =
    envelope.toElem { v => Some(v.project) }.map { v =>
      CompositeViewDef(v)
    }

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
      clock: Clock[IO],
      uuidF: UUIDF
  ): IO[CompositeViewEvent] = {

    def create(c: CreateCompositeView) = state match {
      case None    =>
        for {
          t     <- IOInstant.now
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
          t     <- IOInstant.now
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
        IOInstant.now.map(
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
        IOInstant.now.map(CompositeViewDeprecated(c.id, c.project, s.uuid, s.rev + 1, _, c.subject))
    }

    cmd match {
      case c: CreateCompositeView    => create(c)
      case c: UpdateCompositeView    => update(c)
      case c: TagCompositeView       => tag(c)
      case c: DeprecateCompositeView => deprecate(c)
    }
  }

  def definition(validate: ValidateCompositeView)(implicit
      clock: Clock[IO],
      uuidF: UUIDF
  ): ScopedEntityDefinition[Iri, CompositeViewState, CompositeViewCommand, CompositeViewEvent, CompositeViewRejection] =
    ScopedEntityDefinition(
      entityType,
      StateMachine(None, evaluate(validate)(_, _), next),
      CompositeViewEvent.serializer,
      CompositeViewState.serializer,
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
          state.value.sources.value.foldLeft(Set.empty[DependsOn]) {
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
      fetchContext: FetchContext[CompositeViewRejection],
      contextResolution: ResolverContextResolution,
      validate: ValidateCompositeView,
      config: CompositeViewsConfig,
      xas: Transactors
  )(implicit
      api: JsonLdApi,
      clock: Clock[IO],
      timer: Timer[IO],
      cs: ContextShift[IO],
      uuidF: UUIDF
  ): IO[CompositeViews] =
    IO
      .delay(
        CompositeViewFieldsJsonLdSourceDecoder(uuidF, contextResolution, config.minIntervalRebuild)
      )
      .map { sourceDecoder =>
        new CompositeViews(
          ScopedEventLog(
            definition(validate),
            config.eventLog,
            xas
          ),
          fetchContext,
          sourceDecoder
        )
      }
}
