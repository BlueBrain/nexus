package ch.epfl.bluebrain.nexus.delta.plugins.compositeviews

import akka.actor.typed.ActorSystem
import akka.persistence.query.Offset
import cats.effect.Clock
import cats.implicits._
import ch.epfl.bluebrain.nexus.delta.kernel.syntax.kamonSyntax
import ch.epfl.bluebrain.nexus.delta.kernel.utils.{IOUtils, UUIDF}
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.BlazegraphViews
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.CompositeViews._
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.client.DeltaClient
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.config.CompositeViewsConfig
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.indexing.CompositeViewsIndexing
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.model.CompositeViewCommand._
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.model.CompositeViewEvent._
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.model.CompositeViewProjection.{ElasticSearchProjection, SparqlProjection}
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.model.CompositeViewRejection._
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.model.CompositeViewSource.{AccessToken, CrossProjectSource, ProjectSource, RemoteProjectSource}
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.model.CompositeViewState.{Current, Initial}
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.model.ProjectionType.{ElasticSearchProjectionType, SparqlProjectionType}
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.model._
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.serialization.CompositeViewFieldsJsonLdSourceDecoder
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.ElasticSearchViews
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.client.{ElasticSearchClient, IndexLabel}
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.api.JsonLdApi
import ch.epfl.bluebrain.nexus.delta.sdk.ResourceIdCheck.IdAvailability
import ch.epfl.bluebrain.nexus.delta.sdk._
import ch.epfl.bluebrain.nexus.delta.sdk.acls.AclCheck
import ch.epfl.bluebrain.nexus.delta.sdk.cache.{KeyValueStore, KeyValueStoreConfig}
import ch.epfl.bluebrain.nexus.delta.sdk.crypto.Crypto
import ch.epfl.bluebrain.nexus.delta.sdk.http.HttpClientError
import ch.epfl.bluebrain.nexus.delta.sdk.http.HttpClientError.HttpClientStatusError
import ch.epfl.bluebrain.nexus.delta.sdk.identities.model.Caller
import ch.epfl.bluebrain.nexus.delta.sdk.implicits._
import ch.epfl.bluebrain.nexus.delta.sdk.jsonld.ExpandIri
import ch.epfl.bluebrain.nexus.delta.sdk.model._
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.Pagination.{FromPagination, OnePage}
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.SearchResults
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.SearchResults.UnscoredSearchResults
import ch.epfl.bluebrain.nexus.delta.sdk.permissions.Permissions.events
import ch.epfl.bluebrain.nexus.delta.sdk.permissions.model.Permission
import ch.epfl.bluebrain.nexus.delta.sdk.projects.ProjectReferenceFinder.ProjectReferenceMap
import ch.epfl.bluebrain.nexus.delta.sdk.projects.model.{ProjectBase, ProjectContext}
import ch.epfl.bluebrain.nexus.delta.sdk.projects.{FetchContext, ProjectReferenceFinder, Projects}
import ch.epfl.bluebrain.nexus.delta.sdk.resolvers.ResolverContextResolution
import ch.epfl.bluebrain.nexus.delta.sdk.views.model.ViewRef
import ch.epfl.bluebrain.nexus.delta.sourcing.SnapshotStrategy.NoSnapshot
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.Subject
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Tag.UserTag
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{Label, ProjectRef, ResourceRef}
import ch.epfl.bluebrain.nexus.delta.sourcing.processor.EventSourceProcessor.persistenceId
import ch.epfl.bluebrain.nexus.delta.sourcing.processor.ShardedAggregate
import ch.epfl.bluebrain.nexus.delta.sourcing.projections.ProjectionId.{CompositeViewProjectionId, SourceProjectionId}
import ch.epfl.bluebrain.nexus.delta.sourcing.{Aggregate, EventLog, PersistentEventDefinition}
import fs2.Stream
import io.circe.Json
import monix.bio.{IO, Task, UIO}
import monix.execution.Scheduler

import java.util.UUID
import scala.annotation.nowarn

/**
  * Composite views resource lifecycle operations.
  */
final class CompositeViews private (
    aggregate: CompositeViewsAggregate,
    eventLog: EventLog[Envelope[CompositeViewEvent]],
    cache: CompositeViewsCache,
    fetchContext: FetchContext[CompositeViewRejection],
    sourceDecoder: CompositeViewFieldsJsonLdSourceDecoder
)(implicit uuidF: UUIDF) {

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
  }.named("createCompositeView", moduleType)

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
  }.named("createCompositeView", moduleType)

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
  }.named("createCompositeView", moduleType)

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
      rev: Long,
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
  }.named("updateCompositeView", moduleType)

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
  def update(id: IdSegment, project: ProjectRef, rev: Long, source: Json)(implicit
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
  }.named("updateCompositeView", moduleType)

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
      tagRev: Long,
      rev: Long
  )(implicit subject: Subject): IO[CompositeViewRejection, ViewResource] = {
    for {
      pc  <- fetchContext.onModify(project)
      iri <- expandIri(id, pc)
      res <- eval(TagCompositeView(iri, project, tagRev, tag, rev, subject), pc)
    } yield res
  }.named("tagCompositeView", moduleType)

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
      rev: Long
  )(implicit subject: Subject): IO[CompositeViewRejection, ViewResource] = {
    for {
      pc  <- fetchContext.onModify(project)
      iri <- expandIri(id, pc)
      res <- eval(DeprecateCompositeView(iri, project, rev, subject), pc)
    } yield res
  }.named("deprecateCompositeView", moduleType)

  /**
    * Retrieves a current composite view resource.
    *
    * @param id
    *   the identifier that will be expanded to the Iri of the view with its optional rev/tag
    * @param project
    *   the view parent project
    */
  def fetch(id: IdSegmentRef, project: ProjectRef): IO[CompositeViewRejection, ViewResource] =
    id.asTag.fold(fetchRevOrLatest(id, project).map(_._2))(fetchBy(_, project)).named("fetchCompositeView", moduleType)

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
      (p, view)     <- fetchRevOrLatest(id, project)
      projectionIri <- expandIri(projectionId, p)
      projection    <- IO.fromOption(
                         view.value.projections.value.find(_.id == projectionIri),
                         ProjectionNotFound(view.id, projectionIri, project)
                       )
    } yield view.map(_ -> projection)

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
      (p, view) <- fetchRevOrLatest(id, project)
      sourceIri <- expandIri(sourceId, p)
      source    <- IO.fromOption(
                     view.value.sources.value.find(_.id == sourceIri),
                     SourceNotFound(view.id, sourceIri, project)
                   )
    } yield view.map(_ -> source)

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

  private def fetchBy(
      id: IdSegmentRef.Tag,
      project: ProjectRef
  ): IO[CompositeViewRejection, ViewResource] =
    fetch(id.toLatest, project).flatMap { view =>
      view.value.tags.get(id.tag) match {
        case Some(rev) => fetch(id.toRev(rev), project).mapError(_ => TagNotFound(id.tag))
        case None      => IO.raiseError(TagNotFound(id.tag))
      }
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
  ): UIO[UnscoredSearchResults[ViewResource]] =
    cache.values
      .flatMap {
        _.toList.filterA(params.matches).map { results =>
          SearchResults(
            results.size.toLong,
            results.sorted(ordering).slice(pagination.from, pagination.from + pagination.size)
          )
        }
      }
      .named("listCompositeViews", moduleType)

  /**
    * A terminating stream of events for views. It finishes the stream after emitting all known events.
    *
    * @param projectRef
    *   the project reference where the elasticsearch view belongs
    * @param offset
    *   the last seen event offset; it will not be emitted by the stream
    */
  @nowarn("cat=unused")
  def currentEvents(
      projectRef: ProjectRef,
      offset: Offset
  ): IO[CompositeViewRejection, Stream[Task, Envelope[CompositeViewEvent]]] =
    IO.pure(Stream.empty)

  /**
    * Retrieves the ordered collection of events for all composite views starting from the last known offset. The event
    * corresponding to the provided offset will not be included in the results. The use of NoOffset implies the
    * retrieval of all events.
    *
    * @param offset
    *   the starting offset for the event log
    */
  def events(
      offset: Offset
  ): Stream[Task, Envelope[CompositeViewEvent]] = eventLog.eventsByTag(moduleType, offset)

  /**
    * A non terminating stream of events for composite views. After emitting all known events it sleeps until new events
    * are recorded.
    *
    * @param projectRef
    *   the project reference where the elasticsearch view belongs
    * @param offset
    *   the last seen event offset; it will not be emitted by the stream
    */
  @nowarn("cat=unused")
  def events(
      projectRef: ProjectRef,
      offset: Offset
  ): IO[CompositeViewRejection, Stream[Task, Envelope[CompositeViewEvent]]] =
    IO.pure(Stream.empty)

  /**
    * A non terminating stream of events for composite views. After emitting all known events it sleeps until new events
    * are recorded.
    *
    * @param organization
    *   the organization label reference where the elasticsearch view belongs
    * @param offset
    *   the last seen event offset; it will not be emitted by the stream
    */
  @nowarn("cat=unused")
  def events(
      organization: Label,
      offset: Offset
  ): IO[CompositeViewRejection, Stream[Task, Envelope[CompositeViewEvent]]] =
    IO.pure(Stream.empty)

  private def stateAt(project: ProjectRef, iri: Iri, rev: Long) =
    eventLog
      .fetchStateAt(persistenceId(moduleType, identifier(project, iri)), rev, Initial, next)
      .mapError(RevisionNotFound(rev, _))

  private def currentState(project: ProjectRef, iri: Iri): IO[CompositeViewRejection, CompositeViewState] =
    aggregate.state(identifier(project, iri))

  private def fetchRevOrLatest(
      id: IdSegmentRef,
      project: ProjectRef
  ): IO[CompositeViewRejection, (ProjectContext, ViewResource)] =
    for {
      pc    <- fetchContext.onRead(project)
      iri   <- expandIri(id.value, pc)
      state <- id.asRev.fold(currentState(project, iri))(id => stateAt(project, iri, id.rev))
      res   <- IO.fromOption(state.toResource(pc.apiMappings, pc.base), ViewNotFound(iri, project))
    } yield (pc, res)

  private def eval(
      cmd: CompositeViewCommand,
      pc: ProjectContext
  ): IO[CompositeViewRejection, ViewResource] =
    for {
      result    <- aggregate.evaluate(identifier(cmd.project, cmd.id), cmd).mapError(_.value)
      (am, base) = pc.apiMappings -> pc.base
      resource  <- IO.fromOption(result.state.toResource(am, base), UnexpectedInitialState(cmd.id, cmd.project))
      _         <- cache.put(ViewRef(cmd.project, cmd.id), resource)
    } yield resource

  private def identifier(project: ProjectRef, id: Iri): String =
    s"${project}_$id"
}

object CompositeViews {

  type ValidateProjection = (CompositeViewProjection, UUID, Long) => IO[CompositeViewProjectionRejection, Unit]
  type ValidateSource     = CompositeViewSource => IO[CompositeViewSourceRejection, Unit]

  type CompositeViewsAggregate =
    Aggregate[String, CompositeViewState, CompositeViewCommand, CompositeViewEvent, CompositeViewRejection]

  type CompositeViewsCache = KeyValueStore[ViewRef, ViewResource]

  val expandIri: ExpandIri[InvalidCompositeViewId] = new ExpandIri(InvalidCompositeViewId.apply)

  val moduleType: String = "compositeviews"

  val moduleTag = "view"

  /**
    * Create a reference exchange from a [[CompositeViews]] instance
    */
  def referenceExchange(views: CompositeViews)(implicit base: BaseUri): ReferenceExchange = {
    val fetch = (ref: ResourceRef, projectRef: ProjectRef) => views.fetch(IdSegmentRef(ref), projectRef)
    ReferenceExchange[CompositeView](fetch(_, _), _.source)
  }

  /**
    * Create a project reference finder for composite views
    */
  def projectReferenceFinder(views: CompositeViews): ProjectReferenceFinder =
    (project: ProjectRef) => {
      val params = CompositeViewSearchParams(
        deprecated = Some(false),
        filter = { c =>
          UIO.pure(
            c.project != project && c.sources.value.exists {
              case crossProjectSource: CrossProjectSource =>
                crossProjectSource.project == project
              case _                                      => false
            }
          )
        }
      )
      views.list(OnePage, params, ProjectReferenceFinder.ordering).map {
        _.results.foldMap { r =>
          ProjectReferenceMap.single(r.source.value.project, r.source.id)
        }
      }
    }

  private[compositeviews] def next(
      state: CompositeViewState,
      event: CompositeViewEvent
  ): CompositeViewState = {
    // format: off
    def created(e: CompositeViewCreated): CompositeViewState = state match {
      case Initial     => Current(e.id, e.project, e.uuid, e.value, e.source, Map.empty, e.rev, deprecated = false,  e.instant, e.subject, e.instant, e.subject)
      case s: Current  => s
    }

    def updated(e: CompositeViewUpdated): CompositeViewState = state match {
      case Initial    => Initial
      case s: Current => s.copy(rev = e.rev, value = e.value, source = e.source, updatedAt = e.instant, updatedBy = e.subject)
    }

    def tagAdded(e: CompositeViewTagAdded): CompositeViewState = state match {
      case Initial    => Initial
      case s: Current => s.copy(rev = e.rev, tags = s.tags + (e.tag -> e.targetRev), updatedAt = e.instant, updatedBy = e.subject)
    }
    // format: on

    def deprecated(e: CompositeViewDeprecated): CompositeViewState = state match {
      case Initial    => Initial
      case s: Current => s.copy(rev = e.rev, deprecated = true, updatedAt = e.instant, updatedBy = e.subject)
    }

    event match {
      case e: CompositeViewCreated    => created(e)
      case e: CompositeViewUpdated    => updated(e)
      case e: CompositeViewTagAdded   => tagAdded(e)
      case e: CompositeViewDeprecated => deprecated(e)
    }
  }

  private[compositeviews] def evaluate(
      validateSource: ValidateSource,
      validateProjection: ValidateProjection,
      idAvailability: IdAvailability[ResourceAlreadyExists],
      maxSources: Int,
      maxProjections: Int
  )(
      state: CompositeViewState,
      cmd: CompositeViewCommand
  )(implicit
      clock: Clock[UIO],
      uuidF: UUIDF
  ): IO[CompositeViewRejection, CompositeViewEvent] = {

    def validate(value: CompositeViewValue, uuid: UUID, rev: Long): IO[CompositeViewRejection, Unit] = for {
      _          <- IO.raiseWhen(value.sources.value.size > maxSources)(TooManySources(value.sources.value.size, maxSources))
      _          <- IO.raiseWhen(value.projections.value.size > maxProjections)(
                      TooManyProjections(value.projections.value.size, maxProjections)
                    )
      allIds      = value.sources.value.toList.map(_.id) ++ value.projections.value.toList.map(_.id)
      distinctIds = allIds.distinct
      _          <- IO.raiseWhen(allIds.size != distinctIds.size)(DuplicateIds(allIds))
      _          <- value.sources.value.toList.foldLeftM(())((_, s) => validateSource(s))
      _          <- value.projections.value.toList.foldLeftM(())((_, s) => validateProjection(s, uuid, rev))

    } yield ()

    def fieldsToValue(
        state: CompositeViewState,
        fields: CompositeViewFields,
        projectBase: ProjectBase
    ): UIO[CompositeViewValue] = state match {
      case Initial    =>
        CompositeViewValue(fields, Map.empty, Map.empty, projectBase)
      case s: Current =>
        CompositeViewValue(
          fields,
          s.value.sources.value.map(s => s.id -> s.uuid).toMap,
          s.value.projections.value.map(p => p.id -> p.uuid).toMap,
          projectBase
        )
    }

    def create(c: CreateCompositeView) = state match {
      case Initial =>
        for {
          t     <- IOUtils.instant
          u     <- uuidF()
          value <- fieldsToValue(Initial, c.value, c.projectBase)
          _     <- validate(value, u, 1)
          _     <- idAvailability(c.project, c.id)
        } yield CompositeViewCreated(c.id, c.project, u, value, c.source, 1L, t, c.subject)
      case _       => IO.raiseError(ViewAlreadyExists(c.id, c.project))
    }

    def update(c: UpdateCompositeView) = state match {
      case Initial                      =>
        IO.raiseError(ViewNotFound(c.id, c.project))
      case s: Current if s.rev != c.rev =>
        IO.raiseError(IncorrectRev(c.rev, s.rev))
      case s: Current if s.deprecated   =>
        IO.raiseError(ViewIsDeprecated(c.id))
      case s: Current                   =>
        for {
          value <- fieldsToValue(s, c.value, c.projectBase)
          newRev = s.rev + 1L
          _     <- validate(value, s.uuid, newRev)
          t     <- IOUtils.instant
        } yield CompositeViewUpdated(c.id, c.project, s.uuid, value, c.source, newRev, t, c.subject)
    }

    def tag(c: TagCompositeView) = state match {
      case Initial                                                =>
        IO.raiseError(ViewNotFound(c.id, c.project))
      case s: Current if s.rev != c.rev                           =>
        IO.raiseError(IncorrectRev(c.rev, s.rev))
      case s: Current if c.targetRev <= 0L || c.targetRev > s.rev =>
        IO.raiseError(RevisionNotFound(c.targetRev, s.rev))
      case s: Current                                             =>
        IOUtils.instant.map(
          CompositeViewTagAdded(c.id, c.project, s.uuid, c.targetRev, c.tag, s.rev + 1L, _, c.subject)
        )
    }

    def deprecate(c: DeprecateCompositeView) = state match {
      case Initial                      =>
        IO.raiseError(ViewNotFound(c.id, c.project))
      case s: Current if s.rev != c.rev =>
        IO.raiseError(IncorrectRev(c.rev, s.rev))
      case s: Current if s.deprecated   =>
        IO.raiseError(ViewIsDeprecated(c.id))
      case s: Current                   =>
        IOUtils.instant.map(CompositeViewDeprecated(c.id, c.project, s.uuid, s.rev + 1L, _, c.subject))
    }

    cmd match {
      case c: CreateCompositeView    => create(c)
      case c: UpdateCompositeView    => update(c)
      case c: TagCompositeView       => tag(c)
      case c: DeprecateCompositeView => deprecate(c)
    }
  }

  /**
    * Constructs a new [[CompositeViews]] instance.
    */
  def apply(
      config: CompositeViewsConfig,
      eventLog: EventLog[Envelope[CompositeViewEvent]],
      fetchContext: FetchContext[CompositeViewRejection],
      cache: CompositeViewsCache,
      agg: CompositeViewsAggregate,
      contextResolution: ResolverContextResolution
  )(implicit api: JsonLdApi, uuidF: UUIDF, as: ActorSystem[Nothing], sc: Scheduler): Task[CompositeViews] =
    for {
      sourceDecoder <- Task.delay(CompositeViewFieldsJsonLdSourceDecoder(uuidF, contextResolution)(api, config))
      views          = new CompositeViews(agg, eventLog, cache, fetchContext, sourceDecoder)
      _             <- CompositeViewsIndexing.populateCache(config.cacheIndexing.retry, views, cache)
    } yield views

  def aggregate(
      config: CompositeViewsConfig,
      projects: Projects,
      aclCheck: AclCheck,
      fetchPermissions: UIO[Set[Permission]],
      resourceIdCheck: ResourceIdCheck,
      client: ElasticSearchClient,
      deltaClient: DeltaClient,
      crypto: Crypto
  )(implicit
      as: ActorSystem[Nothing],
      baseUri: BaseUri,
      uuidF: UUIDF,
      clock: Clock[UIO]
  ): UIO[CompositeViewsAggregate] = {

    def validateAcls(cpSource: CrossProjectSource): IO[CrossProjectSourceForbidden, Unit] =
      aclCheck.authorizeForOr(cpSource.project, events.read, cpSource.identities)(CrossProjectSourceForbidden(cpSource))

    def validateProject(cpSource: CrossProjectSource) = {
      projects.fetch(cpSource.project).mapError(_ => CrossProjectSourceProjectNotFound(cpSource)).void
    }

    def validateCrypto(token: Option[AccessToken]): IO[InvalidEncryptionSecrets.type, Unit] = token match {
      case Some(AccessToken(value)) =>
        IO.fromEither(crypto.encrypt(value.value).flatMap(crypto.decrypt).toEither.void)
          .mapError(_ => InvalidEncryptionSecrets)
      case None                     => IO.unit
    }

    def validatePermission(permission: Permission) =
      fetchPermissions.flatMap { perms =>
        IO.when(!perms.contains(permission))(IO.raiseError(PermissionIsNotDefined(permission)))
      }

    def validateIndex(es: ElasticSearchProjection, index: IndexLabel) =
      client
        .createIndex(index, Some(es.mapping), es.settings)
        .mapError {
          case err: HttpClientStatusError => InvalidElasticSearchProjectionPayload(err.jsonBody)
          case err                        => WrappedElasticSearchClientError(err)
        }
        .void

    val checkRemoteEvent: RemoteProjectSource => IO[HttpClientError, Unit] = deltaClient.checkEvents

    val validateSource: ValidateSource = {
      case _: ProjectSource             => IO.unit
      case cpSource: CrossProjectSource => validateAcls(cpSource) >> validateProject(cpSource)
      case rs: RemoteProjectSource      =>
        checkRemoteEvent(rs).mapError(InvalidRemoteProjectSource(rs, _)) >> validateCrypto(rs.token)
    }

    val validateProjection: ValidateProjection = {
      case (sparql: SparqlProjection, _, _)         => validatePermission(sparql.permission)
      case (es: ElasticSearchProjection, uuid, rev) =>
        validatePermission(es.permission) >>
          validateIndex(es, index(es, uuid, rev, config.elasticSearchIndexing.prefix))
    }

    val idAvailability: IdAvailability[ResourceAlreadyExists] = (project, id) =>
      resourceIdCheck.isAvailableOr(project, id)(ResourceAlreadyExists(id, project))

    aggregate(config, validateSource, validateProjection, idAvailability)
  }

  private[compositeviews] def aggregate(
      config: CompositeViewsConfig,
      validateS: ValidateSource,
      validateP: ValidateProjection,
      idAvailability: IdAvailability[ResourceAlreadyExists]
  )(implicit as: ActorSystem[Nothing], uuidF: UUIDF, clock: Clock[UIO]) = {

    val definition = PersistentEventDefinition(
      entityType = moduleType,
      initialState = Initial,
      next = next,
      evaluate = evaluate(validateS, validateP, idAvailability, config.sources.maxSources, config.maxProjections),
      tagger = (_: CompositeViewEvent) => Set.empty,
      snapshotStrategy = NoSnapshot,
      stopStrategy = config.aggregate.stopStrategy.persistentStrategy
    )

    ShardedAggregate.persistentSharded(
      definition = definition,
      config = config.aggregate.processor
    )
  }

  def cache(config: CompositeViewsConfig)(implicit as: ActorSystem[Nothing]): CompositeViewsCache = {
    implicit val cfg: KeyValueStoreConfig   = config.keyValueStore
    val clock: (Long, ViewResource) => Long = (_, resource) => resource.rev
    KeyValueStore.distributed(moduleType, clock)
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
  def sourceProjection(view: CompositeView, rev: Long, sourceId: Iri): Option[SourceProjectionId] =
    view.sources.value.find(_.id == sourceId).map(sourceProjection(_, rev))

  /**
    * The [[SourceProjectionId]] of a view source
    *
    * @param source
    *   the view source
    * @param rev
    *   the revision of the view
    */
  def sourceProjection(source: CompositeViewSource, rev: Long): SourceProjectionId =
    SourceProjectionId(s"${source.uuid}_$rev")

  /**
    * All projection ids
    *
    * @param view
    *   the view
    * @param rev
    *   the revision of the view
    */
  def projectionIds(view: CompositeView, rev: Long): Set[(Iri, Iri, CompositeViewProjectionId)] =
    for {
      s <- view.sources.value
      p <- view.projections.value
    } yield (s.id, p.id, projectionId(sourceProjection(s, rev), p, rev))

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
      rev: Long
  ): Set[(Iri, CompositeViewProjectionId)] =
    view.projections.value.map(projection => projection.id -> projectionId(source, projection, rev))

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
      rev: Long
  ): Set[(Iri, CompositeViewProjectionId)] =
    view.sources.value.map(source => source.id -> projectionId(source, projection, rev))

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
      rev: Long
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
      rev: Long
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
  def index(projection: ElasticSearchProjection, view: CompositeView, rev: Long, prefix: String): IndexLabel =
    index(projection, view.uuid, rev, prefix)

  private def index(projection: ElasticSearchProjection, uuid: UUID, rev: Long, prefix: String): IndexLabel = {
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
  def namespace(projection: SparqlProjection, view: CompositeView, rev: Long, prefix: String): String =
    s"${prefix}_${view.uuid}_${projection.uuid}_$rev"
}
