package ch.epfl.bluebrain.nexus.delta.sdk.testkit

import akka.persistence.query.Offset
import cats.effect.Clock
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.contexts
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.{ContextValue, RemoteContextResolution}
import ch.epfl.bluebrain.nexus.delta.sdk.Resolvers._
import ch.epfl.bluebrain.nexus.delta.sdk._
import ch.epfl.bluebrain.nexus.delta.sdk.syntax._
import ch.epfl.bluebrain.nexus.delta.sdk.jsonld.JsonLdSourceParser
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Caller
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Identity.Subject
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.{Project, ProjectRef}
import ch.epfl.bluebrain.nexus.delta.sdk.model.resolvers.ResolverCommand.{CreateResolver, DeprecateResolver, TagResolver, UpdateResolver}
import ch.epfl.bluebrain.nexus.delta.sdk.model.resolvers.ResolverRejection._
import ch.epfl.bluebrain.nexus.delta.sdk.model.resolvers.ResolverState.{Current, Initial}
import ch.epfl.bluebrain.nexus.delta.sdk.model.resolvers._
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.Pagination.FromPagination
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.SearchParams.ResolverSearchParams
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.SearchResults.UnscoredSearchResults
import ch.epfl.bluebrain.nexus.delta.sdk.model.{Envelope, IdSegment, Label}
import ch.epfl.bluebrain.nexus.delta.sdk.testkit.ResolversDummy.{ResolverCache, ResolverJournal}
import ch.epfl.bluebrain.nexus.delta.sdk.utils.UUIDF
import ch.epfl.bluebrain.nexus.testkit.IOSemaphore
import io.circe.Json
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
    contextResolution: ResolverContextResolution,
    semaphore: IOSemaphore
)(implicit clock: Clock[UIO], uuidF: UUIDF)
    extends Resolvers {

  implicit val resolverContext: ContextValue = Resolvers.context

  override def create(projectRef: ProjectRef, source: Json)(implicit
      caller: Caller
  ): IO[ResolverRejection, ResolverResource] = {
    implicit val rcr: RemoteContextResolution = contextResolution(projectRef)
    for {
      p                    <- projects.fetchActiveProject(projectRef)
      (iri, resolverValue) <-
        JsonLdSourceParser.decode[ResolverValue, ResolverRejection](p, source.addContext(contexts.resolvers))
      res                  <- eval(CreateResolver(iri, projectRef, resolverValue, source, caller), p)
    } yield res
  }

  override def create(id: IdSegment, projectRef: ProjectRef, source: Json)(implicit
      caller: Caller
  ): IO[ResolverRejection, ResolverResource] = {
    implicit val rcr: RemoteContextResolution = contextResolution(projectRef)
    for {
      p             <- projects.fetchActiveProject(projectRef)
      iri           <- expandIri(id, p)
      resolverValue <-
        JsonLdSourceParser.decode[ResolverValue, ResolverRejection](p, iri, source.addContext(contexts.resolvers))
      res           <- eval(CreateResolver(iri, projectRef, resolverValue, source, caller), p)
    } yield res
  }

  override def create(id: IdSegment, projectRef: ProjectRef, resolverValue: ResolverValue)(implicit
      caller: Caller
  ): IO[ResolverRejection, ResolverResource] =
    for {
      p     <- projects.fetchActiveProject(projectRef)
      iri   <- expandIri(id, p)
      source = ResolverValue.generateSource(iri, resolverValue)
      res   <- eval(CreateResolver(iri, projectRef, resolverValue, source, caller), p)
    } yield res

  override def update(id: IdSegment, projectRef: ProjectRef, rev: Long, source: Json)(implicit
      caller: Caller
  ): IO[ResolverRejection, ResolverResource] = {
    implicit val rcr: RemoteContextResolution = contextResolution(projectRef)
    for {
      p             <- projects.fetchActiveProject(projectRef)
      iri           <- expandIri(id, p)
      resolverValue <-
        JsonLdSourceParser.decode[ResolverValue, ResolverRejection](p, iri, source.addContext(contexts.resolvers))
      res           <- eval(UpdateResolver(iri, projectRef, resolverValue, source, rev, caller), p)
    } yield res
  }

  override def update(id: IdSegment, projectRef: ProjectRef, rev: Long, resolverValue: ResolverValue)(implicit
      caller: Caller
  ): IO[ResolverRejection, ResolverResource] =
    for {
      p     <- projects.fetchActiveProject(projectRef)
      iri   <- expandIri(id, p)
      source = ResolverValue.generateSource(iri, resolverValue)
      res   <- eval(UpdateResolver(iri, projectRef, resolverValue, source, rev, caller), p)
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

  override def fetchActiveResolver(id: Iri, projectRef: ProjectRef): IO[ResolverRejection, Resolver] =
    currentState(projectRef, id).flatMap {
      case Initial                    => IO.raiseError(ResolverNotFound(id, projectRef))
      case c: Current if c.deprecated => IO.raiseError(ResolverIsDeprecated(id))
      case c: Current                 => IO.pure(c.resolver)
    }

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

  private def expandIri(segment: IdSegment, project: Project): IO[InvalidResolverId, Iri] =
    JsonLdSourceParser.expandIri(segment, project, InvalidResolverId.apply)
}

object ResolversDummy {
  type ResolverIdentifier = (ProjectRef, Iri)
  type ResolverJournal    = Journal[ResolverIdentifier, ResolverEvent]
  type ResolverCache      = ResourceCache[ResolverIdentifier, Resolver]

  implicit private val eventLens: Lens[ResolverEvent, ResolverIdentifier] =
    (event: ResolverEvent) => (event.project, event.id)

  implicit private val lens: Lens[Resolver, ResolverIdentifier]    =
    (resolver: Resolver) => resolver.project -> resolver.id

  /**
    * Creates a resolvers dummy instance
    * @param projects the projects operations bundle
    * @param contextResolution the context resolver
    */
  def apply(
      projects: Projects,
      contextResolution: ResolverContextResolution
  )(implicit clock: Clock[UIO], uuidF: UUIDF): UIO[ResolversDummy] =
    for {
      journal <- Journal(moduleType)
      cache   <- ResourceCache[ResolverIdentifier, Resolver]
      sem     <- IOSemaphore(1L)
    } yield new ResolversDummy(journal, cache, projects, contextResolution, sem)
}
