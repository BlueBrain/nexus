package ch.epfl.bluebrain.nexus.delta.sdk.resolvers

import cats.effect.{Clock, IO, Timer}
import ch.epfl.bluebrain.nexus.delta.kernel.effect.migration._
import ch.epfl.bluebrain.nexus.delta.kernel.kamon.KamonMetricComponent
import ch.epfl.bluebrain.nexus.delta.kernel.search.Pagination.FromPagination
import ch.epfl.bluebrain.nexus.delta.kernel.utils.UUIDF
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.contexts
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.api.JsonLdApi
import ch.epfl.bluebrain.nexus.delta.sdk._
import ch.epfl.bluebrain.nexus.delta.sdk.identities.model.Caller
import ch.epfl.bluebrain.nexus.delta.sdk.implicits._
import ch.epfl.bluebrain.nexus.delta.sdk.jsonld.JsonLdSourceProcessor.JsonLdSourceResolvingDecoder
import ch.epfl.bluebrain.nexus.delta.sdk.model.IdSegmentRef.{Latest, Revision, Tag}
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.SearchParams.ResolverSearchParams
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.SearchResults
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.SearchResults.UnscoredSearchResults
import ch.epfl.bluebrain.nexus.delta.sdk.model.{IdSegment, IdSegmentRef}
import ch.epfl.bluebrain.nexus.delta.sdk.projects.FetchContext
import ch.epfl.bluebrain.nexus.delta.sdk.resolvers.Resolvers.{entityType, expandIri}
import ch.epfl.bluebrain.nexus.delta.sdk.resolvers.ResolversImpl.ResolversLog
import ch.epfl.bluebrain.nexus.delta.sdk.resolvers.model.ResolverCommand.{CreateResolver, DeprecateResolver, TagResolver, UpdateResolver}
import ch.epfl.bluebrain.nexus.delta.sdk.resolvers.model.ResolverRejection.{PriorityAlreadyExists, ResolverNotFound, RevisionNotFound, TagNotFound}
import ch.epfl.bluebrain.nexus.delta.sdk.resolvers.model._
import ch.epfl.bluebrain.nexus.delta.sourcing._
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Tag.UserTag
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{Identity, ProjectRef}
import doobie.implicits._
import io.circe.Json

final class ResolversImpl private (
    log: ResolversLog,
    fetchContext: FetchContext[ResolverRejection],
    sourceDecoder: JsonLdSourceResolvingDecoder[ResolverRejection, ResolverValue]
) extends Resolvers {

  implicit private val kamonComponent: KamonMetricComponent = KamonMetricComponent(entityType.value)

  override def create(
      projectRef: ProjectRef,
      source: Json
  )(implicit caller: Caller): IO[ResolverResource] = {
    for {
      pc                   <- fetchContext.onCreate(projectRef)
      (iri, resolverValue) <- sourceDecoder(projectRef, pc, source)
      res                  <- eval(CreateResolver(iri, projectRef, resolverValue, source, caller))
    } yield res
  }.span("createResolver")

  override def create(
      id: IdSegment,
      projectRef: ProjectRef,
      source: Json
  )(implicit caller: Caller): IO[ResolverResource] = {
    for {
      pc            <- fetchContext.onCreate(projectRef)
      iri           <- expandIri(id, pc)
      resolverValue <- sourceDecoder(projectRef, pc, iri, source)
      res           <- eval(CreateResolver(iri, projectRef, resolverValue, source, caller))
    } yield res
  }.span("createResolver")

  override def create(
      id: IdSegment,
      projectRef: ProjectRef,
      resolverValue: ResolverValue
  )(implicit caller: Caller): IO[ResolverResource] = {
    for {
      pc    <- fetchContext.onCreate(projectRef)
      iri   <- expandIri(id, pc)
      source = ResolverValue.generateSource(iri, resolverValue)
      res   <- eval(CreateResolver(iri, projectRef, resolverValue, source, caller))
    } yield res
  }.span("createResolver")

  override def update(
      id: IdSegment,
      projectRef: ProjectRef,
      rev: Int,
      source: Json
  )(implicit caller: Caller): IO[ResolverResource] = {
    for {
      pc            <- fetchContext.onModify(projectRef)
      iri           <- expandIri(id, pc)
      resolverValue <- sourceDecoder(projectRef, pc, iri, source)
      res           <- eval(UpdateResolver(iri, projectRef, resolverValue, source, rev, caller))
    } yield res
  }.span("updateResolver")

  override def update(
      id: IdSegment,
      projectRef: ProjectRef,
      rev: Int,
      resolverValue: ResolverValue
  )(implicit
      caller: Caller
  ): IO[ResolverResource] = {
    for {
      pc    <- fetchContext.onModify(projectRef)
      iri   <- expandIri(id, pc)
      source = ResolverValue.generateSource(iri, resolverValue)
      res   <- eval(UpdateResolver(iri, projectRef, resolverValue, source, rev, caller))
    } yield res
  }.span("updateResolver")

  override def tag(
      id: IdSegment,
      projectRef: ProjectRef,
      tag: UserTag,
      tagRev: Int,
      rev: Int
  )(implicit
      subject: Identity.Subject
  ): IO[ResolverResource] = {
    for {
      pc  <- fetchContext.onModify(projectRef)
      iri <- expandIri(id, pc)
      res <- eval(TagResolver(iri, projectRef, tagRev, tag, rev, subject))
    } yield res
  }.span("tagResolver")

  override def deprecate(
      id: IdSegment,
      projectRef: ProjectRef,
      rev: Int
  )(implicit subject: Identity.Subject): IO[ResolverResource] = {
    for {
      pc  <- fetchContext.onModify(projectRef)
      iri <- expandIri(id, pc)
      res <- eval(DeprecateResolver(iri, projectRef, rev, subject))
    } yield res
  }.span("deprecateResolver")

  override def fetch(id: IdSegmentRef, projectRef: ProjectRef): IO[ResolverResource] = {
    for {
      pc      <- fetchContext.onRead(projectRef)
      iri     <- expandIri(id.value, pc)
      notFound = ResolverNotFound(iri, projectRef)
      state   <- id match {
                   case Latest(_)        => log.stateOr(projectRef, iri, notFound)
                   case Revision(_, rev) =>
                     log.stateOr(projectRef, iri, rev, notFound, RevisionNotFound)
                   case Tag(_, tag)      =>
                     log.stateOr(projectRef, iri, tag, notFound, TagNotFound(tag))
                 }
    } yield state.toResource
  }.span("fetchResolver")

  def list(
      pagination: FromPagination,
      params: ResolverSearchParams,
      ordering: Ordering[ResolverResource]
  ): IO[UnscoredSearchResults[ResolverResource]] = {
    val scope = params.project.fold[Scope](Scope.Root)(ref => Scope.Project(ref))
    SearchResults(
      log.currentStates(scope, _.toResource).evalFilter(params.matches(_).toUIO),
      pagination,
      ordering
    ).span("listResolvers")
  }

  private def eval(cmd: ResolverCommand) =
    log.evaluate(cmd.project, cmd.id, cmd).map(_._2.toResource)
}

object ResolversImpl {

  type ResolversLog = ScopedEventLog[Iri, ResolverState, ResolverCommand, ResolverEvent, ResolverRejection]

  /**
    * Constructs a Resolver instance
    */
  def apply(
      fetchContext: FetchContext[ResolverRejection],
      contextResolution: ResolverContextResolution,
      config: ResolversConfig,
      xas: Transactors
  )(implicit api: JsonLdApi, clock: Clock[IO], uuidF: UUIDF, timer: Timer[IO]): Resolvers = {
    def priorityAlreadyExists(ref: ProjectRef, self: Iri, priority: Priority): IO[Unit] = {
      sql"SELECT id FROM scoped_states WHERE type = ${Resolvers.entityType} AND org = ${ref.organization} AND project = ${ref.project}  AND id != $self AND (value->'value'->'priority')::int = ${priority.value} "
        .query[Iri]
        .option
        .transact(xas.readCE)
        .flatMap {
          case Some(other) => IO.raiseError(PriorityAlreadyExists(ref, other, priority))
          case None        => IO.unit
        }
    }

    new ResolversImpl(
      ScopedEventLog(Resolvers.definition(priorityAlreadyExists), config.eventLog, xas),
      fetchContext,
      new JsonLdSourceResolvingDecoder[ResolverRejection, ResolverValue](
        contexts.resolvers,
        contextResolution,
        uuidF
      )
    )
  }
}
