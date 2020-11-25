package ch.epfl.bluebrain.nexus.delta.sdk.testkit

import akka.persistence.query.Offset
import cats.effect.Clock
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.RemoteContextResolution
import ch.epfl.bluebrain.nexus.delta.sdk.Resolvers._
import ch.epfl.bluebrain.nexus.delta.sdk._
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Caller
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.{Project, ProjectRef}
import ch.epfl.bluebrain.nexus.delta.sdk.jsonld.JsonLdSourceParser._
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Identity.Subject
import ch.epfl.bluebrain.nexus.delta.sdk.model.resolvers.ResolverCommand.{CreateResolver, DeprecateResolver, TagResolver, UpdateResolver}
import ch.epfl.bluebrain.nexus.delta.sdk.model.resolvers.ResolverRejection._
import ch.epfl.bluebrain.nexus.delta.sdk.model.resolvers.ResolverState.Initial
import ch.epfl.bluebrain.nexus.delta.sdk.model.resolvers._
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.Pagination.FromPagination
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.SearchParams.ResolverSearchParams
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.SearchResults.UnscoredSearchResults
import ch.epfl.bluebrain.nexus.delta.sdk.model.{Envelope, IdSegment, Label}
import ch.epfl.bluebrain.nexus.delta.sdk.testkit.ResolversDummy.{ResolverCache, ResolverJournal}
import ch.epfl.bluebrain.nexus.delta.sdk.utils.UUIDF
import ch.epfl.bluebrain.nexus.testkit.IOSemaphore
import monix.bio.{IO, Task, UIO}

/**
  * A dummy Resolvers implementation
  *
  * @param journal     the journal to store events
  * @param cache       the cache to store resolvers
  * @param projects    the projects operations bundle
  * @param semaphore   a semaphore for serializing write operations on the journal
  */
class ResolversDummy private (
    journal: ResolverJournal,
    cache: ResolverCache,
    projects: Projects,
    semaphore: IOSemaphore
)(implicit clock: Clock[UIO], uuidF: UUIDF, rcr: RemoteContextResolution)
    extends Resolvers {

  override def create(projectRef: ProjectRef, resolverFields: ResolverFields)(implicit
      caller: Caller
  ): IO[ResolverRejection, ResolverResource] =
    for {
      p   <- projects.fetchActiveProject(projectRef)
      iri <- computeId(p, resolverFields.source)
      res <- eval(CreateResolver(iri, projectRef, resolverFields.value, caller), p)
    } yield res

  override def create(id: IdSegment, projectRef: ProjectRef, resolverFields: ResolverFields)(implicit
      caller: Caller
  ): IO[ResolverRejection, ResolverResource] =
    for {
      p   <- projects.fetchActiveProject(projectRef)
      iri <- computeId(id, p, resolverFields.source)
      res <- eval(CreateResolver(iri, projectRef, resolverFields.value, caller), p)
    } yield res

  override def update(id: IdSegment, projectRef: ProjectRef, rev: Long, resolverFields: ResolverFields)(implicit
      caller: Caller
  ): IO[ResolverRejection, ResolverResource] =
    for {
      p   <- projects.fetchActiveProject(projectRef)
      iri <- computeId(id, p, resolverFields.source)
      res <- eval(UpdateResolver(iri, projectRef, resolverFields.value, rev, caller), p)
    } yield res

  override def tag(id: IdSegment, projectRef: ProjectRef, tag: Label, tagRev: Long, rev: Long)(implicit
      subject: Subject
  ): IO[ResolverRejection, ResolverResource] =
    for {
      p   <- projects.fetchActiveProject(projectRef)
      iri <- expandIri(id, p)
      res <- eval(TagResolver(iri, projectRef, tagRev, tag, rev, subject), p)
    } yield res

  override def deprecate(id: IdSegment, projectRef: ProjectRef, rev: Long)(implicit
      subject: Subject
  ): IO[ResolverRejection, ResolverResource] =
    for {
      p   <- projects.fetchActiveProject(projectRef)
      iri <- expandIri(id, p)
      res <- eval(DeprecateResolver(iri, projectRef, rev, subject), p)
    } yield res

  override def fetch(id: IdSegment, projectRef: ProjectRef): IO[ResolverRejection, ResolverResource] =
    fetch(id, projectRef, None)

  override def fetchAt(id: IdSegment, projectRef: ProjectRef, rev: Long): IO[ResolverRejection, ResolverResource] =
    fetch(id, projectRef, Some(rev))

  private def fetch(id: IdSegment, projectRef: ProjectRef, rev: Option[Long]) =
    for {
      p     <- projects.fetchProject(projectRef)
      iri   <- expandIri(id, p)
      state <- rev.fold(currentState(projectRef, iri))(stateAt(projectRef, iri, _))
      res   <- IO.fromOption(state.toResource(p.apiMappings, p.base), ResolverNotFound(iri, projectRef))
    } yield res

  def list(pagination: FromPagination, params: ResolverSearchParams): UIO[UnscoredSearchResults[ResolverResource]] =
    cache.list(pagination, params)

  override def events(offset: Offset): fs2.Stream[Task, Envelope[ResolverEvent]] = journal.events(offset)

  private def currentState(projectRef: ProjectRef, iri: Iri): IO[ResolverRejection, ResolverState] =
    journal.currentState((projectRef, iri), Initial, Resolvers.next).map(_.getOrElse(Initial))

  private def stateAt(projectRef: ProjectRef, iri: Iri, rev: Long): IO[RevisionNotFound, ResolverState] =
    journal.stateAt((projectRef, iri), rev, Initial, Resolvers.next, RevisionNotFound.apply).map(_.getOrElse(Initial))

  private def eval(command: ResolverCommand, project: Project): IO[ResolverRejection, ResolverResource] =
    semaphore.withPermit {
      for {
        state      <- currentState(command.project, command.id)
        event      <- Resolvers.evaluate(state, command)
        _          <- journal.add(event)
        resourceOpt = Resolvers.next(state, event).toResource(project.apiMappings, project.base)
        res        <- IO.fromOption(resourceOpt, UnexpectedInitialState(command.id, project.ref))
        _          <- cache.setToCache(res)
      } yield res
    }
}

object ResolversDummy {
  type ResolverIdentifier = (ProjectRef, Iri)
  type ResolverJournal    = Journal[ResolverIdentifier, ResolverEvent]
  type ResolverCache      = ResourceCache[ResolverIdentifier, Resolver]

  implicit private val eventLens: Lens[ResolverEvent, ResolverIdentifier] =
    (event: ResolverEvent) => (event.project, event.id)

  implicit private val lens: Lens[Resolver, ResolverIdentifier]                                  =
    (resolver: Resolver) => resolver.project -> resolver.id

  /**
    * Creates a resolvers dummy instance
    * @param projects the projects operations bundle
    */
  def apply(
      projects: Projects
  )(implicit clock: Clock[UIO], uuidF: UUIDF, rcr: RemoteContextResolution): UIO[ResolversDummy] =
    for {
      journal <- Journal(moduleType)
      cache   <- ResourceCache[ResolverIdentifier, Resolver]
      sem     <- IOSemaphore(1L)
    } yield new ResolversDummy(journal, cache, projects, sem)
}
