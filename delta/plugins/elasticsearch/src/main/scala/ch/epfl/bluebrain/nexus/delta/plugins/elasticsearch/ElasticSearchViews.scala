package ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch

import akka.persistence.query.Offset
import cats.effect.Clock
import cats.implicits._
import ch.epfl.bluebrain.nexus.delta.kernel.syntax._
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.ElasticSearchViews._
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.model.ElasticSearchViewCommand._
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.model.ElasticSearchViewEvent._
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.model.ElasticSearchViewRejection._
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.model.ElasticSearchViewState.{Current, Initial}
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.model.ElasticSearchViewValue._
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.model._
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.RemoteContextResolution
import ch.epfl.bluebrain.nexus.delta.sdk.Projects
import ch.epfl.bluebrain.nexus.delta.sdk.cache.KeyValueStore
import ch.epfl.bluebrain.nexus.delta.sdk.jsonld.JsonLdSourceParser
import ch.epfl.bluebrain.nexus.delta.sdk.jsonld.JsonLdSourceParser.expandIri
import ch.epfl.bluebrain.nexus.delta.sdk.model.IdSegment.StringSegment
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Identity.Subject
import ch.epfl.bluebrain.nexus.delta.sdk.model.permissions.Permission
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.{Project, ProjectRef}
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.Pagination.FromPagination
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.ResultEntry.UnscoredResultEntry
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.SearchResults.UnscoredSearchResults
import ch.epfl.bluebrain.nexus.delta.sdk.model.{Envelope, IdSegment, Label}
import ch.epfl.bluebrain.nexus.delta.sdk.utils.{IOUtils, UUIDF}
import ch.epfl.bluebrain.nexus.sourcing.processor.EventSourceProcessor.persistenceId
import ch.epfl.bluebrain.nexus.sourcing.{Aggregate, EventLog}
import io.circe.Json
import io.circe.syntax._
import monix.bio.{IO, Task, UIO}

final class ElasticSearchViews private (
    aggregate: ElasticSearchViewAggregate,
    eventLog: EventLog[Envelope[ElasticSearchViewEvent]],
    cache: ElasticSearchViewCache,
    projects: Projects
)(implicit rcr: RemoteContextResolution, uuidF: UUIDF) {

  def create(
      project: ProjectRef,
      value: ElasticSearchViewValue
  )(implicit subject: Subject): IO[ElasticSearchViewRejection, ElasticSearchViewResource] =
    uuidF().flatMap(uuid => create(StringSegment(uuid.toString), project, value))

  def create(
      id: IdSegment,
      project: ProjectRef,
      value: ElasticSearchViewValue
  )(implicit subject: Subject): IO[ElasticSearchViewRejection, ElasticSearchViewResource] =
    create(id, project, value, value.asJson)

  def create(
      project: ProjectRef,
      source: Json
  )(implicit subject: Subject): IO[ElasticSearchViewRejection, ElasticSearchViewResource] = {
    for {
      p          <- projects.fetchActiveProject[ElasticSearchViewRejection](project)
      result     <- JsonLdSourceParser.decode[ElasticSearchViewValue, ElasticSearchViewRejection](p, source)
      (id, value) = result
      res        <- eval(CreateElasticSearchView(id, project, value, source, subject), p)
    } yield res
  }.named("createElasticSearchView", moduleType)

  def create(
      id: IdSegment,
      project: ProjectRef,
      source: Json
  )(implicit subject: Subject): IO[ElasticSearchViewRejection, ElasticSearchViewResource] = {
    for {
      p     <- projects.fetchActiveProject(project)
      iri   <- expandIri(id, p)
      value <- JsonLdSourceParser.decode[ElasticSearchViewValue, ElasticSearchViewRejection](p, iri, source)
      res   <- eval(CreateElasticSearchView(iri, project, value, source, subject), p)
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

  def update(
      id: IdSegment,
      project: ProjectRef,
      rev: Long,
      source: Json
  )(implicit subject: Subject): IO[ElasticSearchViewRejection, ElasticSearchViewResource] = {
    for {
      p     <- projects.fetchActiveProject(project)
      iri   <- expandIri(id, p)
      value <- JsonLdSourceParser.decode[ElasticSearchViewValue, ElasticSearchViewRejection](p, iri, source)
      res   <- eval(UpdateElasticSearchView(iri, project, rev, value, source, subject), p)
    } yield res
  }.named("updateElasticSearchView", moduleType)

  def tag(
      id: IdSegment,
      project: ProjectRef,
      tag: Label,
      tagRev: Long,
      rev: Long
  )(implicit subject: Subject): IO[ElasticSearchViewRejection, ElasticSearchViewResource] = {
    for {
      p   <- projects.fetchActiveProject(project)
      iri <- expandIri(id, p)
      res <- eval(TagElasticSearchView(iri, project, tagRev, tag, rev, subject), p)
    } yield res
  }.named("tagElasticSearchView", moduleType)

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

  def fetch(id: IdSegment, project: ProjectRef): IO[ElasticSearchViewRejection, ElasticSearchViewResource] =
    fetch(id, project, None).map(_._1).named("fetchElasticSearchView", moduleType)

  def fetchAt(
      id: IdSegment,
      project: ProjectRef,
      rev: Long
  ): IO[ElasticSearchViewRejection, ElasticSearchViewResource] =
    fetch(id, project, Some(rev)).map(_._1).named("fetchElasticSearchViewAt", moduleType)

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

  def fetchBy(
      id: IdSegment,
      project: ProjectRef,
      tag: Label
  ): IO[ElasticSearchViewRejection, ElasticSearchViewResource] =
    fetch(id, project, None)
      .flatMap { case (resource, iri) =>
        resource.value.tags.get(tag) match {
          case Some(rev) => fetchAt(id, project, rev).leftMap(_ => TagNotFound(tag, iri, project))
          case None      => IO.raiseError(TagNotFound(tag, iri, project))
        }
      }
      .named("fetchElasticSearchViewByTag", moduleType)

  def list(
      pagination: FromPagination,
      params: ElasticSearchViewSearchParams
  ): UIO[UnscoredSearchResults[ElasticSearchViewResource]] =
    cache.values
      .map { resources =>
        val results = resources.filter(params.matches).toVector.sortBy(_.createdAt)
        UnscoredSearchResults(
          results.size.toLong,
          results.map(UnscoredResultEntry(_)).slice(pagination.from, pagination.from + pagination.size)
        )
      }
      .named("listElasticSearchViews", moduleType)

  def events(offset: Offset): fs2.Stream[Task, Envelope[ElasticSearchViewEvent]] =
    eventLog.eventsByTag(moduleType, offset)

  private def currentState(project: ProjectRef, iri: Iri): IO[ElasticSearchViewRejection, ElasticSearchViewState] =
    aggregate.state(identifier(project, iri))

  private def stateAt(project: ProjectRef, iri: Iri, rev: Long) = {
    if (rev == 0L) UIO.pure(Initial)
    else
      eventLog
        .currentEventsByPersistenceId(
          persistenceId(moduleType, identifier(project, iri)),
          Long.MinValue,
          Long.MaxValue
        )
        .takeWhile(_.event.rev <= rev)
        .fold[ElasticSearchViewState](Initial) { case (state, envelope) =>
          next(state, envelope.event)
        }
        .compile
        .last
        .hideErrors
        .flatMap {
          case Some(state) if state.rev == rev => UIO.pure(state)
          case Some(state)                     => IO.raiseError(RevisionNotFound(rev, state.rev))
          case None                            => IO.raiseError(RevisionNotFound(rev, 0L))
        }
  }

  private def eval(
      cmd: ElasticSearchViewCommand,
      project: Project
  ): IO[ElasticSearchViewRejection, ElasticSearchViewResource] =
    for {
      result    <- aggregate.evaluate(identifier(cmd.project, cmd.id), cmd).mapError(_.value)
      (am, base) = project.apiMappings -> project.base
      resource  <- IO.fromOption(result.state.toResource(am, base), UnexpectedInitialState(cmd.id, project.ref))
      _         <- cache.put(cmd.project -> cmd.id, resource)
    } yield resource

  private def identifier(project: ProjectRef, id: Iri): String =
    s"${project}_$id"
}

object ElasticSearchViews {

  type ElasticSearchViewAggregate = Aggregate[
    String,
    ElasticSearchViewState,
    ElasticSearchViewCommand,
    ElasticSearchViewEvent,
    ElasticSearchViewRejection
  ]

  type ElasticSearchViewCache = KeyValueStore[(ProjectRef, Iri), ElasticSearchViewResource]

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
}
