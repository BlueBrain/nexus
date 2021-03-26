package ch.epfl.bluebrain.nexus.delta.plugins.compositeviews

import akka.actor.typed.ActorSystem
import akka.persistence.query.Offset
import cats.effect.Clock
import cats.implicits._
import ch.epfl.bluebrain.nexus.delta.kernel.syntax.kamonSyntax
import ch.epfl.bluebrain.nexus.delta.kernel.utils.{IOUtils, UUIDF}
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.CompositeViews._
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.model.CompositeViewCommand._
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.model.CompositeViewEvent._
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.model.CompositeViewProjection.{ElasticSearchProjection, SparqlProjection}
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.model.CompositeViewRejection._
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.model.CompositeViewSource.{CrossProjectSource, ProjectSource, RemoteProjectSource}
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.model.CompositeViewState.{Current, Initial}
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.model._
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.serialization.CompositeViewFieldsJsonLdSourceDecoder
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.sdk.Permissions.resources
import ch.epfl.bluebrain.nexus.delta.sdk._
import ch.epfl.bluebrain.nexus.delta.sdk.cache.{KeyValueStore, KeyValueStoreConfig}
import ch.epfl.bluebrain.nexus.delta.sdk.eventlog.EventLogUtils
import ch.epfl.bluebrain.nexus.delta.sdk.jsonld.ExpandIri
import ch.epfl.bluebrain.nexus.delta.sdk.model._
import ch.epfl.bluebrain.nexus.delta.sdk.model.acls.AclAddress
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Caller
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Identity.Subject
import ch.epfl.bluebrain.nexus.delta.sdk.model.permissions.Permission
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.{Project, ProjectBase, ProjectRef}
import ch.epfl.bluebrain.nexus.delta.sdk.model.resolvers.ResolverContextResolution
import ch.epfl.bluebrain.nexus.delta.sdk.views.model.ViewRef
import ch.epfl.bluebrain.nexus.delta.sourcing.SnapshotStrategy.NoSnapshot
import ch.epfl.bluebrain.nexus.delta.sourcing.processor.EventSourceProcessor.persistenceId
import ch.epfl.bluebrain.nexus.delta.sourcing.processor.ShardedAggregate
import ch.epfl.bluebrain.nexus.delta.sourcing.{Aggregate, EventLog, PersistentEventDefinition}
import fs2.Stream
import io.circe.Json
import monix.bio.{IO, Task, UIO}

/**
  * Composite views resource lifecycle operations.
  */
final class CompositeViews private (
    aggregate: CompositeViewsAggregate,
    eventLog: EventLog[Envelope[CompositeViewEvent]],
    cache: CompositeViewsCache,
    orgs: Organizations,
    projects: Projects,
    sourceDecoder: CompositeViewFieldsJsonLdSourceDecoder
)(implicit uuidF: UUIDF) {

  /**
    * Create a new composite view with a generate id.
    *
    * @param project  the parent project of the view
    * @param value    the view configuration
    */
  def create(project: ProjectRef, value: CompositeViewFields)(implicit
      subject: Subject,
      baseUri: BaseUri
  ): IO[CompositeViewRejection, ViewResource] =
    uuidF().flatMap(uuid => create(uuid.toString, project, value))

  /**
    * Create a new composite view with a provided id
    *
    * @param id      the id of the view either in Iri or aliased form
    * @param project  the parent project of the view
    * @param value    the view configuration
    */
  def create(id: IdSegment, project: ProjectRef, value: CompositeViewFields)(implicit
      subject: Subject,
      baseUri: BaseUri
  ): IO[CompositeViewRejection, ViewResource] = {
    for {
      p   <- projects.fetchActiveProject(project)
      iri <- expandIri(id, p)
      res <- eval(CreateCompositeView(iri, project, value, value.toJson(iri), subject, p.base), p)
    } yield res
  }.named("createCompositeView", moduleType)

  /**
    * Creates a new composite from a json representation. If an identifier exists in the provided json it will
    * be used; otherwise a new identifier will be generated.
    *
    * @param project the parent project of the view
    * @param source  the json representation of the view
    * @param caller  the caller that initiated the action
    */
  def create(project: ProjectRef, source: Json)(implicit caller: Caller): IO[CompositeViewRejection, ViewResource] = {
    for {
      p            <- projects.fetchActiveProject[CompositeViewRejection](project)
      (iri, value) <- sourceDecoder(p, source)
      res          <- eval(CreateCompositeView(iri, project, value, source, caller.subject, p.base), p)
    } yield res
  }.named("createCompositeView", moduleType)

  /**
    * Creates a new composite from a json representation. If an identifier exists in the provided json it will
    * be used as long as it matches the provided id in Iri form or as an alias; otherwise the action will be rejected.
    *
    * @param project the parent project of the view
    * @param source  the json representation of the view
    * @param caller  the caller that initiated the action
    */
  def create(id: IdSegment, project: ProjectRef, source: Json)(implicit
      caller: Caller
  ): IO[CompositeViewRejection, ViewResource] = {
    for {
      p         <- projects.fetchActiveProject(project)
      iri       <- expandIri(id, p)
      viewValue <- sourceDecoder(p, iri, source)
      res       <- eval(CreateCompositeView(iri, project, viewValue, source, caller.subject, p.base), p)
    } yield res
  }.named("createCompositeView", moduleType)

  /**
    * Updates an existing composite view.
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
      value: CompositeViewFields
  )(implicit
      subject: Subject,
      baseUri: BaseUri
  ): IO[CompositeViewRejection, ViewResource] = {
    for {
      p     <- projects.fetchActiveProject(project)
      iri   <- expandIri(id, p)
      source = value.toJson(iri)
      res   <- eval(UpdateCompositeView(iri, project, rev, value, source, subject, p.base), p)
    } yield res
  }.named("updateCompositeView", moduleType)

  /**
    * Updates an existing composite view.
    *
    * @param id      the view identifier
    * @param project the view parent project
    * @param rev     the current view revision
    * @param source  the new view configuration in json representation
    * @param caller  the caller that initiated the action
    */
  def update(id: IdSegment, project: ProjectRef, rev: Long, source: Json)(implicit
      caller: Caller
  ): IO[CompositeViewRejection, ViewResource] = {
    for {
      p         <- projects.fetchActiveProject(project)
      iri       <- expandIri(id, p)
      viewValue <- sourceDecoder(p, iri, source)
      res       <- eval(UpdateCompositeView(iri, project, rev, viewValue, source, caller.subject, p.base), p)
    } yield res
  }.named("updateCompositeView", moduleType)

  /**
    * Applies a tag to an existing composite revision.
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
  )(implicit subject: Subject): IO[CompositeViewRejection, ViewResource] = {
    for {
      p   <- projects.fetchActiveProject(project)
      iri <- expandIri(id, p)
      res <- eval(TagCompositeView(iri, project, tagRev, tag, rev, subject), p)
    } yield res
  }.named("tagCompositeView", moduleType)

  /**
    * Deprecates an existing composite view.
    *
    * @param id      the view identifier
    * @param project the view parent project
    * @param rev     the current view revision
    * @param subject the subject that initiated the action
    */
  def deprecate(id: IdSegment, project: ProjectRef, rev: Long)(implicit
      subject: Subject
  ): IO[CompositeViewRejection, ViewResource] = {
    for {
      p   <- projects.fetchActiveProject(project)
      iri <- expandIri(id, p)
      res <- eval(DeprecateCompositeView(iri, project, rev, subject), p)
    } yield res
  }.named("deprecateCompositeView", moduleType)

  /**
    * Retrieves a current composite view resource.
    *
    * @param id      the view identifier
    * @param project the view parent project
    */
  def fetch(id: IdSegment, project: ProjectRef): IO[CompositeViewRejection, ViewResource] =
    fetch(id, project, None).named("fetchCompositeView", moduleType)

  /**
    * Retrieves a composite view resource at a specific revision.
    *
    * @param id      the view identifier
    * @param project the view parent project
    * @param rev     the specific view revision
    */
  def fetchAt(
      id: IdSegment,
      project: ProjectRef,
      rev: Long
  ): IO[CompositeViewRejection, ViewResource] = fetch(id, project, Some(rev)).named("fetchCompositeViewAt", moduleType)

  /**
    * Retrieves a composite view resource at a specific revision using a tag as a reference.
    *
    * @param id      the view identifier
    * @param project the view parent project
    * @param tag     the tag reference
    */
  def fetchBy(
      id: IdSegment,
      project: ProjectRef,
      tag: TagLabel
  ): IO[CompositeViewRejection, ViewResource] = fetch(id, project, None)
    .flatMap { resource =>
      resource.value.tags.get(tag) match {
        case Some(rev) => fetchAt(id, project, rev).mapError(_ => TagNotFound(tag))
        case None      => IO.raiseError(TagNotFound(tag))
      }
    }
    .named("fetchCompositeViewBy", moduleType)

  /**
    * Retrieves the ordered collection of events for all composite views starting from the last known offset. The
    * event corresponding to the provided offset will not be included in the results. The use of NoOffset implies the
    * retrieval of all events.
    *
    * @param offset the starting offset for the event log
    */
  def events(
      offset: Offset
  ): Stream[Task, Envelope[CompositeViewEvent]] = eventLog.eventsByTag(moduleType, offset)

  /**
    * A non terminating stream of events for composite views. After emitting all known events it sleeps until new events
    * are recorded.
    *
    * @param projectRef the project reference where the elasticsearch view belongs
    * @param offset     the last seen event offset; it will not be emitted by the stream
    */
  def events(
      projectRef: ProjectRef,
      offset: Offset
  ): IO[CompositeViewRejection, Stream[Task, Envelope[CompositeViewEvent]]] = projects
    .fetchProject(projectRef)
    .as(eventLog.eventsByTag(Projects.projectTag(moduleType, projectRef), offset))

  /**
    * A non terminating stream of events for composite views. After emitting all known events it sleeps until new events
    * are recorded.
    *
    * @param organization the organization label reference where the elasticsearch view belongs
    * @param offset       the last seen event offset; it will not be emitted by the stream
    */
  def events(
      organization: Label,
      offset: Offset
  ): IO[CompositeViewRejection, Stream[Task, Envelope[CompositeViewEvent]]] = orgs
    .fetchOrganization(organization)
    .as(eventLog.eventsByTag(Organizations.orgTag(moduleType, organization), offset))

  private def stateAt(project: ProjectRef, iri: Iri, rev: Long) =
    EventLogUtils
      .fetchStateAt(eventLog, persistenceId(moduleType, identifier(project, iri)), rev, Initial, next)
      .mapError(RevisionNotFound(rev, _))

  private def currentState(project: ProjectRef, iri: Iri): IO[CompositeViewRejection, CompositeViewState] =
    aggregate.state(identifier(project, iri))

  private def fetch(
      id: IdSegment,
      project: ProjectRef,
      rev: Option[Long]
  ): IO[CompositeViewRejection, ViewResource] = for {
    p     <- projects.fetchProject(project)
    iri   <- expandIri(id, p)
    state <- rev.fold(currentState(project, iri))(stateAt(project, iri, _))
    res   <- IO.fromOption(state.toResource(p.apiMappings, p.base), ViewNotFound(iri, project))
  } yield res

  private def eval(
      cmd: CompositeViewCommand,
      project: Project
  ): IO[CompositeViewRejection, ViewResource] =
    for {
      result    <- aggregate.evaluate(identifier(cmd.project, cmd.id), cmd).mapError(_.value)
      (am, base) = project.apiMappings -> project.base
      resource  <- IO.fromOption(result.state.toResource(am, base), UnexpectedInitialState(cmd.id, project.ref))
      _         <- cache.put(ViewRef(cmd.project, cmd.id), resource)
    } yield resource

  private def identifier(project: ProjectRef, id: Iri): String =
    s"${project}_$id"
}

object CompositeViews {

  type ValidateProjection = CompositeViewProjection => IO[CompositeViewProjectionRejection, Unit]
  type ValidateSource     = CompositeViewSource => IO[CompositeViewSourceRejection, Unit]

  type CompositeViewsAggregate =
    Aggregate[String, CompositeViewState, CompositeViewCommand, CompositeViewEvent, CompositeViewRejection]

  type CompositeViewsCache = KeyValueStore[ViewRef, ViewResource]

  val expandIri: ExpandIri[InvalidCompositeViewId] = new ExpandIri(InvalidCompositeViewId.apply)

  val moduleType: String = "compositeviews"

  val moduleTag = "view"

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
      maxSources: Int,
      maxProjections: Int
  )(
      state: CompositeViewState,
      cmd: CompositeViewCommand
  )(implicit
      clock: Clock[UIO],
      uuidF: UUIDF
  ): IO[CompositeViewRejection, CompositeViewEvent] = {

    def validate(value: CompositeViewValue): IO[CompositeViewRejection, Unit] = for {
      _          <- IO.raiseWhen(value.sources.value.size > maxSources)(TooManySources(value.sources.value.size, maxSources))
      _          <- IO.raiseWhen(value.projections.value.size > maxProjections)(
                      TooManyProjections(value.projections.value.size, maxProjections)
                    )
      allIds      = value.sources.value.toList.map(_.id) ++ value.projections.value.toList.map(_.id)
      distinctIds = allIds.distinct
      _          <- IO.raiseWhen(allIds.size != distinctIds.size)(DuplicateIds(allIds))
      _          <- value.sources.value.toList.foldLeftM(())((_, s) => validateSource(s))
      _          <- value.projections.value.toList.foldLeftM(())((_, s) => validateProjection(s))

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
          _     <- validate(value)
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
          _     <- validate(value)
          t     <- IOUtils.instant
        } yield CompositeViewUpdated(c.id, c.project, s.uuid, value, c.source, s.rev + 1L, t, c.subject)
    }

    def tag(c: TagCompositeView) = state match {
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
      permissions: Permissions,
      orgs: Organizations,
      projects: Projects,
      acls: Acls,
      contextResolution: ResolverContextResolution
  )(implicit
      uuidF: UUIDF,
      clock: Clock[UIO],
      as: ActorSystem[Nothing],
      baseUri: BaseUri,
  ): Task[CompositeViews] = {

    def validateAcls(cpSource: CrossProjectSource) = {
      val aclAddress = AclAddress.Project(cpSource.project)
      acls
        .fetchWithAncestors(aclAddress)
        .map(_.exists(cpSource.identities, resources.read, aclAddress))
        .flatMap(IO.unless(_)(IO.raiseError(CrossProjectSourceForbidden(cpSource))))
    }

    def validateProject(cpSource: CrossProjectSource) = {
      projects.fetch(cpSource.project).mapError(_ => CrossProjectSourceProjectNotFound(cpSource)).void
    }

    def validateSource: ValidateSource = {
      case _: ProjectSource             => IO.unit
      case cpSource: CrossProjectSource => validateAcls(cpSource) >> validateProject(cpSource)
      //TODO add proper validation when we'll have delta client for indexing
      case _: RemoteProjectSource       => IO.unit
    }

    def validatePermission(permission: Permission) =
      permissions.fetchPermissionSet.flatMap { perms =>
        IO.when(!perms.contains(permission))(IO.raiseError(PermissionIsNotDefined(permission)))
      }

    def validateProjection: ValidateProjection = {
      case sparql: SparqlProjection    => validatePermission(sparql.permission)
      //TODO add validation of Es index
      case es: ElasticSearchProjection => validatePermission(es.permission)
    }

    apply(config, eventLog, orgs, projects, validateSource, validateProjection, contextResolution)
  }

  private[compositeviews] def apply(
      config: CompositeViewsConfig,
      eventLog: EventLog[Envelope[CompositeViewEvent]],
      orgs: Organizations,
      projects: Projects,
      validateSource: ValidateSource,
      validateProjection: ValidateProjection,
      contextResolution: ResolverContextResolution
  )(implicit
      uuidF: UUIDF,
      clock: Clock[UIO],
      as: ActorSystem[Nothing],
  ): Task[CompositeViews] = for {
    agg          <- aggregate(config, validateSource, validateProjection)
    index        <- UIO.delay(cache(config))
    sourceDecoder = CompositeViewFieldsJsonLdSourceDecoder(uuidF, contextResolution)
    views         = new CompositeViews(agg, eventLog, index, orgs, projects, sourceDecoder)

  } yield views

  private def aggregate(config: CompositeViewsConfig, validateS: ValidateSource, validateP: ValidateProjection)(implicit
      as: ActorSystem[Nothing],
      uuidF: UUIDF,
      clock: Clock[UIO]
  ) = {

    val definition = PersistentEventDefinition(
      entityType = moduleType,
      initialState = Initial,
      next = next,
      evaluate = evaluate(validateS, validateP, config.maxSources, config.maxProjections),
      tagger = EventTags.forProjectScopedEvent(moduleTag, moduleType),
      snapshotStrategy = NoSnapshot,
      stopStrategy = config.aggregate.stopStrategy.persistentStrategy
    )

    ShardedAggregate.persistentSharded(
      definition = definition,
      config = config.aggregate.processor
      // TODO: configure the number of shards
    )
  }

  private def cache(config: CompositeViewsConfig)(implicit as: ActorSystem[Nothing]): CompositeViewsCache = {
    implicit val cfg: KeyValueStoreConfig   = config.keyValueStore
    val clock: (Long, ViewResource) => Long = (_, resource) => resource.rev
    KeyValueStore.distributed(moduleType, clock)
  }
}
