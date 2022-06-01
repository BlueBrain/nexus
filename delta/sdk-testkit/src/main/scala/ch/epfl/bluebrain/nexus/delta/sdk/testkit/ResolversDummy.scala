package ch.epfl.bluebrain.nexus.delta.sdk.testkit

import akka.persistence.query.Offset
import cats.effect.Clock
import ch.epfl.bluebrain.nexus.delta.kernel.Lens
import ch.epfl.bluebrain.nexus.delta.kernel.utils.UUIDF
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.contexts
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.api.JsonLdApi
import ch.epfl.bluebrain.nexus.delta.sdk.Resolvers._
import ch.epfl.bluebrain.nexus.delta.sdk.ResourceIdCheck.IdAvailability
import ch.epfl.bluebrain.nexus.delta.sdk._
import ch.epfl.bluebrain.nexus.delta.sdk.jsonld.JsonLdSourceProcessor.JsonLdSourceResolvingDecoder
import ch.epfl.bluebrain.nexus.delta.sdk.model._
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Caller
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Identity.Subject
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.ProjectFetchOptions._
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.{Project, ProjectRef}
import ch.epfl.bluebrain.nexus.delta.sdk.model.resolvers.ResolverCommand.{CreateResolver, DeprecateResolver, TagResolver, UpdateResolver}
import ch.epfl.bluebrain.nexus.delta.sdk.model.resolvers.ResolverRejection._
import ch.epfl.bluebrain.nexus.delta.sdk.model.resolvers.ResolverState.Initial
import ch.epfl.bluebrain.nexus.delta.sdk.model.resolvers._
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.Pagination.FromPagination
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.SearchParams.ResolverSearchParams
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.SearchResults.UnscoredSearchResults
import ch.epfl.bluebrain.nexus.delta.sdk.testkit.ResolversDummy.{ResolverCache, ResolverJournal}
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Tag.UserTag
import ch.epfl.bluebrain.nexus.testkit.IOSemaphore
import fs2.Stream
import io.circe.Json
import monix.bio.{IO, Task, UIO}

import scala.annotation.nowarn

/**
  * A dummy Resolvers implementation
  *
  * @param journal
  *   the journal to store events
  * @param cache
  *   the cache to store resolvers
  * @param orgs
  *   the organizations operations bundle
  * @param projects
  *   the projects operations bundle
  * @param semaphore
  *   a semaphore for serializing write operations on the journal
  */
class ResolversDummy private (
    journal: ResolverJournal,
    cache: ResolverCache,
    orgs: Organizations,
    projects: Projects,
    semaphore: IOSemaphore,
    sourceDecoder: JsonLdSourceResolvingDecoder[ResolverRejection, ResolverValue],
    idAvailability: IdAvailability[ResourceAlreadyExists]
)(implicit clock: Clock[UIO])
    extends Resolvers {

  override def create(
      projectRef: ProjectRef,
      source: Json
  )(implicit caller: Caller): IO[ResolverRejection, ResolverResource] =
    for {
      p                    <- projects.fetchProject(projectRef, notDeprecatedOrDeletedWithQuotas)
      (iri, resolverValue) <- sourceDecoder(p, source)
      res                  <- eval(CreateResolver(iri, projectRef, resolverValue, source, caller), p)
    } yield res

  override def create(
      id: IdSegment,
      projectRef: ProjectRef,
      source: Json
  )(implicit caller: Caller): IO[ResolverRejection, ResolverResource] =
    for {
      p             <- projects.fetchProject(projectRef, notDeprecatedOrDeletedWithQuotas)
      iri           <- expandIri(id, p)
      resolverValue <- sourceDecoder(p, iri, source)
      res           <- eval(CreateResolver(iri, projectRef, resolverValue, source, caller), p)
    } yield res

  override def create(
      id: IdSegment,
      projectRef: ProjectRef,
      resolverValue: ResolverValue
  )(implicit caller: Caller): IO[ResolverRejection, ResolverResource] =
    for {
      p     <- projects.fetchProject(projectRef, notDeprecatedOrDeletedWithQuotas)
      iri   <- expandIri(id, p)
      source = ResolverValue.generateSource(iri, resolverValue)
      res   <- eval(CreateResolver(iri, projectRef, resolverValue, source, caller), p)
    } yield res

  override def update(id: IdSegment, projectRef: ProjectRef, rev: Long, source: Json)(implicit
      caller: Caller
  ): IO[ResolverRejection, ResolverResource] =
    for {
      p             <- projects.fetchProject(projectRef, notDeprecatedOrDeletedWithEventQuotas)
      iri           <- expandIri(id, p)
      resolverValue <- sourceDecoder(p, iri, source)
      res           <- eval(UpdateResolver(iri, projectRef, resolverValue, source, rev, caller), p)

    } yield res

  override def update(
      id: IdSegment,
      projectRef: ProjectRef,
      rev: Long,
      resolverValue: ResolverValue
  )(implicit
      caller: Caller
  ): IO[ResolverRejection, ResolverResource] =
    for {
      p     <- projects.fetchProject(projectRef, notDeprecatedOrDeletedWithEventQuotas)
      iri   <- expandIri(id, p)
      source = ResolverValue.generateSource(iri, resolverValue)
      res   <- eval(UpdateResolver(iri, projectRef, resolverValue, source, rev, caller), p)
    } yield res

  override def tag(
      id: IdSegment,
      projectRef: ProjectRef,
      tag: UserTag,
      tagRev: Long,
      rev: Long
  )(implicit
      subject: Subject
  ): IO[ResolverRejection, ResolverResource] =
    for {
      p   <- projects.fetchProject(projectRef, notDeprecatedOrDeletedWithEventQuotas)
      iri <- expandIri(id, p)
      res <- eval(TagResolver(iri, projectRef, tagRev, tag, rev, subject), p)
    } yield res

  override def deprecate(id: IdSegment, projectRef: ProjectRef, rev: Long)(implicit
      subject: Subject
  ): IO[ResolverRejection, ResolverResource] =
    for {
      p   <- projects.fetchProject(projectRef, notDeprecatedOrDeletedWithEventQuotas)
      iri <- expandIri(id, p)
      res <- eval(DeprecateResolver(iri, projectRef, rev, subject), p)
    } yield res

  override def fetch(id: IdSegmentRef, projectRef: ProjectRef): IO[ResolverRejection, ResolverResource] =
    id.asTag.fold(
      for {
        project <- projects.fetchProject(projectRef)
        iri     <- Resolvers.expandIri(id.value, project)
        state   <- id.asRev.fold(currentState(projectRef, iri))(id => stateAt(projectRef, iri, id.rev))
        res     <- IO.fromOption(state.toResource(project.apiMappings, project.base), ResolverNotFound(iri, projectRef))
      } yield res
    )(fetchBy(_, projectRef))

  def list(
      pagination: FromPagination,
      params: ResolverSearchParams,
      ordering: Ordering[ResolverResource]
  ): UIO[UnscoredSearchResults[ResolverResource]] =
    cache.list(pagination, params, ordering)

  override def currentEvents(
      projectRef: ProjectRef,
      offset: Offset
  ): IO[ResolverRejection, Stream[Task, Envelope[ResolverEvent]]] =
    projects
      .fetchProject(projectRef)
      .as(journal.currentEvents(offset).filter(e => e.event.project == projectRef))

  override def events(
      projectRef: ProjectRef,
      offset: Offset
  ): IO[ResolverRejection, Stream[Task, Envelope[ResolverEvent]]] =
    projects
      .fetchProject(projectRef)
      .as(journal.events(offset).filter(e => e.event.project == projectRef))

  override def events(
      organization: Label,
      offset: Offset
  ): IO[WrappedOrganizationRejection, Stream[Task, Envelope[ResolverEvent]]] =
    orgs
      .fetchOrganization(organization)
      .as(journal.events(offset).filter(e => e.event.project.organization == organization))

  override def events(offset: Offset): fs2.Stream[Task, Envelope[ResolverEvent]] = journal.events(offset)

  private def currentState(projectRef: ProjectRef, iri: Iri): IO[ResolverRejection, ResolverState] =
    journal.currentState((projectRef, iri), Initial, Resolvers.next).map(_.getOrElse(Initial))

  private def stateAt(projectRef: ProjectRef, iri: Iri, rev: Long): IO[RevisionNotFound, ResolverState] =
    journal.stateAt((projectRef, iri), rev, Initial, Resolvers.next, RevisionNotFound.apply).map(_.getOrElse(Initial))

  private def eval(command: ResolverCommand, project: Project): IO[ResolverRejection, ResolverResource] =
    semaphore.withPermit {
      for {
        state      <- currentState(command.project, command.id)
        event      <- Resolvers.evaluate(findResolver, idAvailability)(state, command)
        _          <- journal.add(event)
        resourceOpt = Resolvers.next(state, event).toResource(project.apiMappings, project.base)
        res        <- IO.fromOption(resourceOpt, UnexpectedInitialState(command.id, project.ref))
        _          <- cache.setToCache(res)
      } yield res
    }

  @SuppressWarnings(Array("UnusedMethodParameter"))
  private def findResolver(@nowarn("cat=unused") project: ProjectRef, params: ResolverSearchParams): UIO[Option[Iri]] =
    cache.find(params).map(_.map(_.id))
}

object ResolversDummy {
  type ResolverIdentifier = (ProjectRef, Iri)
  type ResolverJournal    = Journal[ResolverIdentifier, ResolverEvent]
  type ResolverCache      = ResourceCache[ResolverIdentifier, Resolver]

  implicit private val eventLens: Lens[ResolverEvent, ResolverIdentifier] =
    (event: ResolverEvent) => (event.project, event.id)

  implicit private val lens: Lens[Resolver, ResolverIdentifier]                    =
    (resolver: Resolver) => resolver.project -> resolver.id

  /**
    * Creates a resolvers dummy instance
    *
    * @param orgs
    *   the organizations operations bundle
    * @param projects
    *   the projects operations bundle
    * @param contextResolution
    *   the context resolver
    * @param idAvailability
    *   checks if an id is available upon creation
    */
  def apply(
      orgs: Organizations,
      projects: Projects,
      contextResolution: ResolverContextResolution,
      idAvailability: IdAvailability[ResourceAlreadyExists]
  )(implicit api: JsonLdApi, clock: Clock[UIO], uuidF: UUIDF): UIO[ResolversDummy] =
    for {
      journal      <- Journal(moduleType, 1L, EventTags.forProjectScopedEvent[ResolverEvent](moduleType))
      cache        <- ResourceCache[ResolverIdentifier, Resolver]
      sem          <- IOSemaphore(1L)
      sourceDecoder =
        new JsonLdSourceResolvingDecoder[ResolverRejection, ResolverValue](contexts.resolvers, contextResolution, uuidF)
    } yield new ResolversDummy(journal, cache, orgs, projects, sem, sourceDecoder, idAvailability)
}
