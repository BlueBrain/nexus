package ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch

import akka.actor.typed.ActorSystem
import akka.persistence.query.Offset
import cats.effect.Clock
import cats.effect.concurrent.Deferred
import ch.epfl.bluebrain.nexus.delta.kernel.RetryStrategy
import ch.epfl.bluebrain.nexus.delta.kernel.syntax._
import ch.epfl.bluebrain.nexus.delta.kernel.utils.{IOUtils, UUIDF}
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.ElasticSearchViews._
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.config.ElasticSearchViewConfig
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.model.ElasticSearchViewCommand._
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.model.ElasticSearchViewEvent._
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.model.ElasticSearchViewRejection._
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.model.ElasticSearchViewState.{Current, Initial}
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.model.ElasticSearchViewValue._
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.model._
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.nxv
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.RemoteContextResolution
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.decoder.JsonLdDecoderError.ParsingFailure
import ch.epfl.bluebrain.nexus.delta.rdf.syntax._
import ch.epfl.bluebrain.nexus.delta.sdk.cache.{KeyValueStore, KeyValueStoreConfig}
import ch.epfl.bluebrain.nexus.delta.sdk.eventlog.EventLogUtils
import ch.epfl.bluebrain.nexus.delta.sdk.jsonld.ExpandIri
import ch.epfl.bluebrain.nexus.delta.sdk.jsonld.JsonLdSourceProcessor.JsonLdSourceParser
import ch.epfl.bluebrain.nexus.delta.sdk.model.IdSegment.{IriSegment, StringSegment}
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Identity.Subject
import ch.epfl.bluebrain.nexus.delta.sdk.model.permissions.Permission
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.{Project, ProjectRef}
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.Pagination.FromPagination
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.ResultEntry.UnscoredResultEntry
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.SearchResults.UnscoredSearchResults
import ch.epfl.bluebrain.nexus.delta.sdk.model.{Envelope, IdSegment, TagLabel}
import ch.epfl.bluebrain.nexus.delta.sdk.{Organizations, Permissions, Projects}
import ch.epfl.bluebrain.nexus.sourcing.config.AggregateConfig
import ch.epfl.bluebrain.nexus.sourcing.processor.EventSourceProcessor.persistenceId
import ch.epfl.bluebrain.nexus.sourcing.processor.ShardedAggregate
import ch.epfl.bluebrain.nexus.sourcing.projections.StatelessStreamSupervisor
import ch.epfl.bluebrain.nexus.sourcing.{Aggregate, EventLog, PersistentEventDefinition}
import com.typesafe.scalalogging.Logger
import io.circe.syntax._
import io.circe.{DecodingFailure, Json, JsonObject}
import monix.bio.{IO, Task, UIO}
import monix.execution.Scheduler

/**
  * ElasticSearchViews resource lifecycle operations.
  */
final class ElasticSearchViews private (
    aggregate: ElasticSearchViewAggregate,
    eventLog: EventLog[Envelope[ElasticSearchViewEvent]],
    cache: ElasticSearchViewCache,
    projects: Projects
)(implicit rcr: RemoteContextResolution, uuidF: UUIDF) {

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
  )(implicit subject: Subject): IO[ElasticSearchViewRejection, ElasticSearchViewResource] =
    uuidF().flatMap(uuid => create(StringSegment(uuid.toString), project, value))

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
  )(implicit subject: Subject): IO[ElasticSearchViewRejection, ElasticSearchViewResource] =
    create(id, project, value, value.asJson)

  /**
    * Creates a new ElasticSearchView from a json representation. If an identifier exists in the provided json it will
    * be used; otherwise a new identifier will be generated.
    *
    * @param project the parent project of the view
    * @param source  the json representation of the view
    * @param subject the subject that initiated the action
    */
  def create(
      project: ProjectRef,
      source: Json
  )(implicit subject: Subject): IO[ElasticSearchViewRejection, ElasticSearchViewResource] = {
    for {
      p            <- projects.fetchActiveProject[ElasticSearchViewRejection](project)
      (iri, value) <- decode(p, None, source)
      res          <- eval(CreateElasticSearchView(iri, project, value, source, subject), p)
    } yield res
  }.named("createElasticSearchView", moduleType)

  /**
    * Creates a new ElasticSearchView from a json representation. If an identifier exists in the provided json it will
    * be used as long as it matches the provided id in Iri form or as an alias; otherwise the action will be rejected.
    *
    * @param project the parent project of the view
    * @param source  the json representation of the view
    * @param subject the subject that initiated the action
    */
  def create(
      id: IdSegment,
      project: ProjectRef,
      source: Json
  )(implicit subject: Subject): IO[ElasticSearchViewRejection, ElasticSearchViewResource] = {
    for {
      p          <- projects.fetchActiveProject(project)
      iri        <- expandIri(id, p)
      (_, value) <- decode(p, Some(iri), source)
      res        <- eval(CreateElasticSearchView(iri, project, value, source, subject), p)
    } yield res
  }.named("createElasticSearchView", moduleType)

  private def create(
      id: IdSegment,
      project: ProjectRef,
      value: ElasticSearchViewValue,
      source: Json
  )(implicit subject: Subject): IO[ElasticSearchViewRejection, ElasticSearchViewResource] = {
    for {
      p   <- projects.fetchActiveProject(project)
      iri <- expandIri(id, p)
      res <- eval(CreateElasticSearchView(iri, project, value, source, subject), p)
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
  )(implicit subject: Subject): IO[ElasticSearchViewRejection, ElasticSearchViewResource] = {
    for {
      p   <- projects.fetchActiveProject(project)
      iri <- expandIri(id, p)
      res <- eval(UpdateElasticSearchView(iri, project, rev, value, value.asJson, subject), p)
    } yield res
  }.named("updateElasticSearchView", moduleType)

  /**
    * Updates an existing ElasticSearchView.
    *
    * @param id      the view identifier
    * @param project the view parent project
    * @param rev     the current view revision
    * @param source  the new view configuration in json representation
    * @param subject the subject that initiated the action
    */
  def update(
      id: IdSegment,
      project: ProjectRef,
      rev: Long,
      source: Json
  )(implicit subject: Subject): IO[ElasticSearchViewRejection, ElasticSearchViewResource] = {
    for {
      p          <- projects.fetchActiveProject(project)
      iri        <- expandIri(id, p)
      (_, value) <- decode(p, Some(iri), source)
      res        <- eval(UpdateElasticSearchView(iri, project, rev, value, source, subject), p)
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
  )(implicit subject: Subject): IO[ElasticSearchViewRejection, ElasticSearchViewResource] = {
    for {
      p   <- projects.fetchActiveProject(project)
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
  )(implicit subject: Subject): IO[ElasticSearchViewRejection, ElasticSearchViewResource] = {
    for {
      p   <- projects.fetchActiveProject(project)
      iri <- expandIri(id, p)
      res <- eval(DeprecateElasticSearchView(iri, project, rev, subject), p)
    } yield res
  }.named("deprecateElasticSearchView", moduleType)

  /**
    * Retrieves a current ElasticSearchView resource.
    *
    * @param id      the view identifier
    * @param project the view parent project
    */
  def fetch(
      id: IdSegment,
      project: ProjectRef
  ): IO[ElasticSearchViewRejection, ElasticSearchViewResource]        =
    fetch(id, project, None).map({ case (res, _) => res }).named("fetchElasticSearchView", moduleType)

  /**
    * Retrieves an ElasticSearchView resource at a specific revision.
    *
    * @param id      the view identifier
    * @param project the view parent project
    * @param rev     the specific view revision
    */
  def fetchAt(
      id: IdSegment,
      project: ProjectRef,
      rev: Long
  ): IO[ElasticSearchViewRejection, ElasticSearchViewResource]        =
    fetch(id, project, Some(rev)).map({ case (res, _) => res }).named("fetchElasticSearchViewAt", moduleType)

  private def fetch(
      id: IdSegment,
      project: ProjectRef,
      rev: Option[Long]
  ): IO[ElasticSearchViewRejection, (ElasticSearchViewResource, Iri)] =
    for {
      p     <- projects.fetchProject(project)
      iri   <- expandIri(id, p)
      state <- rev.fold(currentState(project, iri))(stateAt(project, iri, _))
      res   <- IO.fromOption(state.toResource(p.apiMappings, p.base), ViewNotFound(iri, project))
    } yield (res, iri)

  /**
    * Retrieves an ElasticSearchView resource at a specific revision using a tag as a reference.
    *
    * @param id      the view identifier
    * @param project the view parent project
    * @param tag     the tag reference
    */
  def fetchBy(
      id: IdSegment,
      project: ProjectRef,
      tag: TagLabel
  ): IO[ElasticSearchViewRejection, ElasticSearchViewResource] =
    fetch(id, project, None)
      .flatMap { case (resource, _) =>
        resource.value.tags.get(tag) match {
          case Some(rev) => fetchAt(id, project, rev).mapError(_ => TagNotFound(tag))
          case None      => IO.raiseError(TagNotFound(tag))
        }
      }
      .named("fetchElasticSearchViewByTag", moduleType)

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
      ordering: Ordering[ElasticSearchViewResource]
  ): UIO[UnscoredSearchResults[ElasticSearchViewResource]] =
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
    * Retrives the ordered collection of events for all ElasticSearchViews starting from the last known offset. The
    * event corresponding to the provided offset will not be included in the results. The use of NoOffset implies the
    * retrieval of all events.
    *
    * @param offset the starting offset for the event log
    */
  def events(offset: Offset): fs2.Stream[Task, Envelope[ElasticSearchViewEvent]] =
    eventLog.eventsByTag(moduleType, offset)

  private def currentState(project: ProjectRef, iri: Iri): IO[ElasticSearchViewRejection, ElasticSearchViewState] =
    aggregate.state(identifier(project, iri)).named("currentState", moduleType)

  private def stateAt(project: ProjectRef, iri: Iri, rev: Long): IO[RevisionNotFound, ElasticSearchViewState] =
    EventLogUtils
      .fetchStateAt(eventLog, persistenceId(moduleType, identifier(project, iri)), rev, Initial, next)
      .mapError(RevisionNotFound(rev, _))
      .named("stateAt", moduleType)

  private def eval(
      cmd: ElasticSearchViewCommand,
      project: Project
  ): IO[ElasticSearchViewRejection, ElasticSearchViewResource] = {
    for {
      result    <- aggregate.evaluate(identifier(cmd.project, cmd.id), cmd).mapError(_.value)
      (am, base) = project.apiMappings -> project.base
      resource  <- IO.fromOption(result.state.toResource(am, base), UnexpectedInitialState(cmd.id, project.ref))
      _         <- cache.put(ViewRef(cmd.project, cmd.id), resource).named("updateElasticSearchViewCache", moduleType)
    } yield resource
  }.named("evaluateElasticSearchViewCommand", moduleType)

  private def identifier(project: ProjectRef, id: Iri): String =
    s"${project}_$id"

}

object ElasticSearchViews {

  /**
    * Constructs a new [[ElasticSearchViews]] instance.
    *
    * @param aggregate the backing view aggregate
    * @param eventLog  the [[EventLog]] instance for [[ElasticSearchViewEvent]]
    * @param cache     a cache instance for ElasticSearchView resources
    * @param projects  the projects module
    */
  final def apply(
      aggregate: ElasticSearchViewAggregate,
      eventLog: EventLog[Envelope[ElasticSearchViewEvent]],
      cache: ElasticSearchViewCache,
      projects: Projects
  )(implicit rcr: RemoteContextResolution, uuidF: UUIDF): ElasticSearchViews =
    new ElasticSearchViews(aggregate, eventLog, cache, projects)

  /**
    * Constructs a new [[ElasticSearchViews]] instance.
    *
    * @param config      the module configuration
    * @param eventLog    the [[EventLog]] instance for [[ElasticSearchViewEvent]]
    * @param projects    the projects module
    * @param permissions the permissions module
    */
  final def apply(
      config: ElasticSearchViewConfig,
      eventLog: EventLog[Envelope[ElasticSearchViewEvent]],
      projects: Projects,
      permissions: Permissions
  )(implicit
      uuidF: UUIDF,
      clock: Clock[UIO],
      scheduler: Scheduler,
      as: ActorSystem[Nothing],
      rcr: RemoteContextResolution
  ): UIO[ElasticSearchViews] = {

    val validatePermission: ValidatePermission = { permission =>
      permissions.fetchPermissionSet.flatMap { set =>
        if (set.contains(permission)) IO.unit
        else IO.raiseError(PermissionIsNotDefined(permission))
      }
    }

    // TODO: replace this with a correct implementation after porting the ES client
    val validateMapping: ValidateMapping = _ => IO.unit

    def validateRef(deferred: Deferred[Task, ElasticSearchViews]): ValidateRef = { viewRef =>
      deferred.get.hideErrors.flatMap { views =>
        views
          .fetch(IriSegment(viewRef.viewId), viewRef.project)
          .redeemWith(
            _ => IO.raiseError(InvalidViewReference(viewRef)),
            { resource =>
              if (resource.deprecated) IO.raiseError(InvalidViewReference(viewRef))
              else IO.unit
            }
          )
      }
    }

    for {
      deferred <- Deferred[Task, ElasticSearchViews].hideErrors
      agg      <- aggregate(config.aggregate, validatePermission, validateMapping, validateRef(deferred))
      index    <- cache(config)
      views     = apply(agg, eventLog, index, projects)
      _        <- deferred.complete(views).hideErrors
      _        <- startIndexing(config, eventLog, index, views).hideErrors
    } yield views
  }

  /**
    * Iri expansion logic for ElasticSearchViews.
    */
  final val expandIri: ExpandIri[InvalidElasticSearchViewId] = new ExpandIri(InvalidElasticSearchViewId.apply)

  type ElasticSearchViewAggregate = Aggregate[
    String,
    ElasticSearchViewState,
    ElasticSearchViewCommand,
    ElasticSearchViewEvent,
    ElasticSearchViewRejection
  ]

  type ElasticSearchViewCache = KeyValueStore[ViewRef, ElasticSearchViewResource]

  /**
    * Creates a new distributed ElasticSearchViewCache.
    */
  private def cache(config: ElasticSearchViewConfig)(implicit as: ActorSystem[Nothing]): UIO[ElasticSearchViewCache] =
    UIO.delay {
      implicit val cfg: KeyValueStoreConfig                = config.keyValueStore
      val clock: (Long, ElasticSearchViewResource) => Long = (_, resource) => resource.rev
      KeyValueStore.distributed(moduleType, clock)
    }

  private def aggregate(
      config: AggregateConfig,
      validatePermission: ValidatePermission,
      validateMapping: ValidateMapping,
      validateRef: ValidateRef
  )(implicit as: ActorSystem[Nothing], uuidF: UUIDF, clock: Clock[UIO]): UIO[ElasticSearchViewAggregate] = {
    val definition = PersistentEventDefinition(
      entityType = moduleType,
      initialState = Initial,
      next = next,
      evaluate = evaluate(validatePermission, validateMapping, validateRef),
      tagger = (event: ElasticSearchViewEvent) =>
        Set(
          moduleType,
          s"${Projects.moduleType}=${event.project}",
          s"${Organizations.moduleType}=${event.project.organization}"
        ),
      snapshotStrategy = config.snapshotStrategy.strategy,
      stopStrategy = config.stopStrategy.persistentStrategy
    )

    ShardedAggregate.persistentSharded(
      definition = definition,
      config = config.processor,
      retryStrategy = RetryStrategy.alwaysGiveUp
      // TODO: configure the number of shards
    )
  }

  private def startIndexing(
      config: ElasticSearchViewConfig,
      eventLog: EventLog[Envelope[ElasticSearchViewEvent]],
      index: ElasticSearchViewCache,
      views: ElasticSearchViews
  )(implicit as: ActorSystem[Nothing], sc: Scheduler) = {
    val logger: Logger = Logger[ElasticSearchViews]
    StatelessStreamSupervisor(
      "ElasticSearchViewsIndex",
      streamTask = Task.delay(
        eventLog
          .eventsByTag(moduleType, Offset.noOffset)
          .mapAsync(config.indexing.concurrency)(envelope =>
            views
              .fetch(IriSegment(envelope.event.id), envelope.event.project)
              .redeemCauseWith(_ => IO.unit, res => index.put(ViewRef(res.value.project, res.value.id), res))
          )
      ),
      retryStrategy = RetryStrategy(
        config.indexing.retry,
        _ => true,
        RetryStrategy.logError(logger, "resolvers indexing")
      )
    )
  }

  /**
    * The context Iri.
    */
  final val contextIri: Iri = iri"https://bluebrain.github.io/nexus/contexts/elasticsearchviews.json"

  /**
    * The elasticsearch module type.
    */
  final val moduleType: String = "elasticsearchview"

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
  type ValidateMapping    = Json => IO[InvalidElasticSearchMapping, Unit]
  type ValidateRef        = ViewRef => IO[InvalidViewReference, Unit]

  private[elasticsearch] def evaluate(
      validatePermission: ValidatePermission,
      validateMapping: ValidateMapping,
      validateRef: ValidateRef
  )(state: ElasticSearchViewState, cmd: ElasticSearchViewCommand)(implicit
      clock: Clock[UIO] = IO.clock,
      uuidF: UUIDF = UUIDF.random
  ): IO[ElasticSearchViewRejection, ElasticSearchViewEvent] = {

    def validate(value: ElasticSearchViewValue): IO[ElasticSearchViewRejection, Unit] =
      value match {
        case v: AggregateElasticSearchViewValue =>
          IO.parTraverseUnordered(v.views.toSortedSet)(validateRef).void
        case v: IndexingElasticSearchViewValue  =>
          for {
            _ <- validateMapping(v.mapping)
            _ <- validatePermission(v.permission)
          } yield ()
      }

    def create(c: CreateElasticSearchView) = state match {
      case Initial =>
        for {
          _ <- validate(c.value)
          t <- IOUtils.instant
          u <- uuidF()
        } yield ElasticSearchViewCreated(c.id, c.project, u, c.value, c.source, 1L, t, c.subject)
      case _       => IO.raiseError(ViewAlreadyExists(c.id, c.project))
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
          _ <- validate(c.value)
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
          ElasticSearchViewTagAdded(c.id, c.project, s.uuid, c.targetRev, c.tag, s.rev + 1L, _, c.subject)
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
        IOUtils.instant.map(ElasticSearchViewDeprecated(c.id, c.project, s.uuid, s.rev + 1L, _, c.subject))
    }

    cmd match {
      case c: CreateElasticSearchView    => create(c)
      case c: UpdateElasticSearchView    => update(c)
      case c: TagElasticSearchView       => tag(c)
      case c: DeprecateElasticSearchView => deprecate(c)
    }
  }

  // TODO: replace this with JsonLdSourceParser.decode when `@type: json` is supported by the json-ld lib
  private[elasticsearch] def decode(
      project: Project,
      iriOpt: Option[Iri],
      source: Json
  )(implicit
      uuidF: UUIDF,
      rcr: RemoteContextResolution
  ): IO[ElasticSearchViewRejection, (Iri, ElasticSearchViewValue)] = {
    // extract the mapping as a string if it exists
    val mappingAsStringOpt: Option[Either[DecodingFailure, String]] = {
      val mappingCursor = source.hcursor.downField("mapping")
      if (mappingCursor.succeeded)
        Some(mappingCursor.as[JsonObject].map(_.asJson.spaces2) orElse mappingCursor.as[String])
      else None
    }

    // drop the mapping field if it exists
    val noMappingSource = source.mapObject(_.remove("mapping")).addContext(contextIri)

    // get a jsonLd representation with the provided id or generated one disregarding the mapping
    val jsonLdIO = iriOpt match {
      case Some(iri) =>
        new JsonLdSourceParser(Some(contextIri), uuidF)
          .apply(project, iri, noMappingSource)
          .map({ case (c, e) => (iri, c, e) })
      case None      =>
        new JsonLdSourceParser(Some(contextIri), uuidF)
          .apply(project, noMappingSource)
    }

    // inject the mapping as a string in the expanded form if it exists and attempt decoding as an ElasticSearchViewValue
    jsonLdIO
      .flatMap { case (iri, _, expanded) =>
        IO.fromEither(
          mappingAsStringOpt match {
            case Some(Left(df))     => Left(ParsingFailure("json or string", df.history))
            case Some(Right(value)) => expanded.add(nxv + "mapping", value).to[ElasticSearchViewValue].map(iri -> _)
            case None               => expanded.to[ElasticSearchViewValue].map(iri -> _)
          }
        ).mapError(err => DecodingFailed(err))
      }
      .named("decodeJsonLd", moduleType)
  }
}
