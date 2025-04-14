package ch.epfl.bluebrain.nexus.delta.sdk.resolvers

import cats.effect.{Clock, IO}
import ch.epfl.bluebrain.nexus.delta.kernel.kamon.KamonMetricComponent
import ch.epfl.bluebrain.nexus.delta.kernel.utils.UUIDF
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.contexts
import ch.epfl.bluebrain.nexus.delta.sdk.*
import ch.epfl.bluebrain.nexus.delta.sdk.identities.model.Caller
import ch.epfl.bluebrain.nexus.delta.sdk.implicits.*
import ch.epfl.bluebrain.nexus.delta.sdk.jsonld.JsonLdSourceProcessor.JsonLdSourceResolvingDecoder
import ch.epfl.bluebrain.nexus.delta.sdk.model.IdSegmentRef.{Latest, Revision, Tag}
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.SearchResults
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.SearchResults.UnscoredSearchResults
import ch.epfl.bluebrain.nexus.delta.sdk.model.{IdSegment, IdSegmentRef}
import ch.epfl.bluebrain.nexus.delta.sdk.projects.FetchContext
import ch.epfl.bluebrain.nexus.delta.sdk.resolvers.Resolvers.{entityType, expandIri}
import ch.epfl.bluebrain.nexus.delta.sdk.resolvers.ResolversImpl.ResolversLog
import ch.epfl.bluebrain.nexus.delta.sdk.resolvers.model.ResolverCommand.{CreateResolver, DeprecateResolver, UpdateResolver}
import ch.epfl.bluebrain.nexus.delta.sdk.resolvers.model.ResolverRejection.{FetchByTagNotSupported, ResolverNotFound, RevisionNotFound}
import ch.epfl.bluebrain.nexus.delta.sdk.resolvers.model.*
import ch.epfl.bluebrain.nexus.delta.sourcing.*
import ch.epfl.bluebrain.nexus.delta.sourcing.config.EventLogConfig
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{Identity, ProjectRef}
import io.circe.Json

final class ResolversImpl private (
    log: ResolversLog,
    fetchContext: FetchContext,
    sourceDecoder: JsonLdSourceResolvingDecoder[ResolverValue]
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
                     log.stateOr(projectRef, iri, tag, notFound, FetchByTagNotSupported(tag))
                 }
    } yield state.toResource
  }.span("fetchResolver")

  def list(project: ProjectRef): IO[UnscoredSearchResults[ResolverResource]] =
    log
      .currentStates(Scope.Project(project), _.toResource)
      .compile
      .toList
      .map { results =>
        SearchResults(results.size.toLong, results)
      }
      .span("listResolvers")

  private def eval(cmd: ResolverCommand): IO[ResolverResource] =
    log.evaluate(cmd.project, cmd.id, cmd).map(_._2.toResource)
}

object ResolversImpl {

  type ResolversLog = ScopedEventLog[Iri, ResolverState, ResolverCommand, ResolverEvent, ResolverRejection]

  /**
    * Constructs a Resolver instance
    */
  def apply(
      fetchContext: FetchContext,
      contextResolution: ResolverContextResolution,
      validatePriority: ValidatePriority,
      config: EventLogConfig,
      xas: Transactors,
      clock: Clock[IO]
  )(implicit uuidF: UUIDF): Resolvers = {
    new ResolversImpl(
      ScopedEventLog(Resolvers.definition(validatePriority, clock), config, xas),
      fetchContext,
      new JsonLdSourceResolvingDecoder[ResolverValue](
        contexts.resolvers,
        contextResolution,
        uuidF
      )
    )
  }
}
