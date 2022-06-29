package ch.epfl.bluebrain.nexus.delta.service.schemas

import akka.actor.typed.ActorSystem
import akka.persistence.query.Offset
import cats.effect.Clock
import ch.epfl.bluebrain.nexus.delta.kernel.utils.UUIDF
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.{contexts, nxv}
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.api.JsonLdApi
import ch.epfl.bluebrain.nexus.delta.sdk.ResourceIdCheck.IdAvailability
import ch.epfl.bluebrain.nexus.delta.sdk.Schemas._
import ch.epfl.bluebrain.nexus.delta.sdk._
import ch.epfl.bluebrain.nexus.delta.sdk.cache.KeyValueStore
import ch.epfl.bluebrain.nexus.delta.sdk.identities.model.Caller
import ch.epfl.bluebrain.nexus.delta.sdk.implicits._
import ch.epfl.bluebrain.nexus.delta.sdk.jsonld.JsonLdSourceProcessor.JsonLdSourceResolvingParser
import ch.epfl.bluebrain.nexus.delta.sdk.model._
import ch.epfl.bluebrain.nexus.delta.sdk.model.resolvers.ResolverContextResolution
import ch.epfl.bluebrain.nexus.delta.sdk.model.schemas.SchemaCommand._
import ch.epfl.bluebrain.nexus.delta.sdk.model.schemas.SchemaRejection._
import ch.epfl.bluebrain.nexus.delta.sdk.model.schemas.SchemaState.Initial
import ch.epfl.bluebrain.nexus.delta.sdk.model.schemas._
import ch.epfl.bluebrain.nexus.delta.sdk.organizations.Organizations
import ch.epfl.bluebrain.nexus.delta.sdk.projects.Projects
import ch.epfl.bluebrain.nexus.delta.sdk.projects.model.Project
import ch.epfl.bluebrain.nexus.delta.sdk.projects.model.ProjectFetchOptions._
import ch.epfl.bluebrain.nexus.delta.service.schemas.SchemasImpl.{SchemasAggregate, SchemasCache}
import ch.epfl.bluebrain.nexus.delta.sourcing._
import ch.epfl.bluebrain.nexus.delta.sourcing.config.AggregateConfig
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.Subject
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{Label, ProjectRef}
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Tag.UserTag
import ch.epfl.bluebrain.nexus.delta.sourcing.processor.EventSourceProcessor._
import ch.epfl.bluebrain.nexus.delta.sourcing.processor.ShardedAggregate
import fs2.Stream
import io.circe.Json
import monix.bio.{IO, Task, UIO}

final class SchemasImpl private (
    agg: SchemasAggregate,
    cache: SchemasCache,
    orgs: Organizations,
    projects: Projects,
    schemaImports: SchemaImports,
    eventLog: EventLog[Envelope[SchemaEvent]],
    sourceParser: JsonLdSourceResolvingParser[SchemaRejection]
) extends Schemas {

  override def create(
      projectRef: ProjectRef,
      source: Json
  )(implicit caller: Caller): IO[SchemaRejection, SchemaResource] = {
    for {
      project                    <- projects.fetchProject(projectRef, notDeprecatedOrDeletedWithQuotas)
      (iri, compacted, expanded) <- sourceParser(project, source)
      expandedResolved           <- schemaImports.resolve(iri, projectRef, expanded.addType(nxv.Schema))
      res                        <- eval(CreateSchema(iri, projectRef, source, compacted, expandedResolved, caller.subject), project)
    } yield res
  }.named("createSchema", moduleType)

  override def create(
      id: IdSegment,
      projectRef: ProjectRef,
      source: Json
  )(implicit caller: Caller): IO[SchemaRejection, SchemaResource] = {
    for {
      project               <- projects.fetchProject(projectRef, notDeprecatedOrDeletedWithQuotas)
      iri                   <- expandIri(id, project)
      (compacted, expanded) <- sourceParser(project, iri, source)
      expandedResolved      <- schemaImports.resolve(iri, projectRef, expanded.addType(nxv.Schema))
      res                   <- eval(CreateSchema(iri, projectRef, source, compacted, expandedResolved, caller.subject), project)
    } yield res
  }.named("createSchema", moduleType)

  override def update(
      id: IdSegment,
      projectRef: ProjectRef,
      rev: Long,
      source: Json
  )(implicit caller: Caller): IO[SchemaRejection, SchemaResource] = {
    for {
      project               <- projects.fetchProject(projectRef, notDeprecatedOrDeletedWithEventQuotas)
      iri                   <- expandIri(id, project)
      (compacted, expanded) <- sourceParser(project, iri, source)
      expandedResolved      <- schemaImports.resolve(iri, projectRef, expanded.addType(nxv.Schema))
      res                   <- eval(UpdateSchema(iri, projectRef, source, compacted, expandedResolved, rev, caller.subject), project)
    } yield res
  }.named("updateSchema", moduleType)

  override def tag(
      id: IdSegment,
      projectRef: ProjectRef,
      tag: UserTag,
      tagRev: Long,
      rev: Long
  )(implicit caller: Subject): IO[SchemaRejection, SchemaResource] =
    (for {
      project <- projects.fetchProject(projectRef, notDeprecatedOrDeletedWithEventQuotas)
      iri     <- expandIri(id, project)
      res     <- eval(TagSchema(iri, projectRef, tagRev, tag, rev, caller), project)
    } yield res).named("tagSchema", moduleType)

  override def deleteTag(
      id: IdSegment,
      projectRef: ProjectRef,
      tag: UserTag,
      rev: Long
  )(implicit caller: Subject): IO[SchemaRejection, SchemaResource] =
    (for {
      project <- projects.fetchProject(projectRef, notDeprecatedOrDeletedWithEventQuotas)
      iri     <- expandIri(id, project)
      res     <- eval(DeleteSchemaTag(iri, projectRef, tag, rev, caller), project)
    } yield res).named("deleteSchemaTag", moduleType)

  override def deprecate(
      id: IdSegment,
      projectRef: ProjectRef,
      rev: Long
  )(implicit caller: Subject): IO[SchemaRejection, SchemaResource] =
    (for {
      project <- projects.fetchProject(projectRef, notDeprecatedOrDeletedWithEventQuotas)
      iri     <- expandIri(id, project)
      res     <- eval(DeprecateSchema(iri, projectRef, rev, caller), project)
    } yield res).named("deprecateSchema", moduleType)

  override def fetch(id: IdSegmentRef, projectRef: ProjectRef): IO[SchemaFetchRejection, SchemaResource] =
    id.asTag
      .fold(
        for {
          project <- projects.fetchProject(projectRef)
          iri     <- expandIri(id.value, project)
          state   <- id.asRev.fold(currentState(projectRef, iri))(id => stateAt(projectRef, iri, id.rev))
          cached  <-
            cache.getOrElseUpdate(
              (projectRef, iri, state.rev),
              IO.fromOption(state.toResource(project.apiMappings, project.base), SchemaNotFound(iri, projectRef))
            )
        } yield cached
      )(fetchBy(_, projectRef))
      .named("fetchSchema", moduleType)

  override def currentEvents(
      projectRef: ProjectRef,
      offset: Offset
  ): IO[SchemaRejection, Stream[Task, Envelope[SchemaEvent]]] =
    eventLog.currentProjectEvents(projects, projectRef, moduleType, offset)

  override def events(
      projectRef: ProjectRef,
      offset: Offset
  ): IO[SchemaRejection, Stream[Task, Envelope[SchemaEvent]]] =
    eventLog.projectEvents(projects, projectRef, moduleType, offset)

  override def events(
      organization: Label,
      offset: Offset
  ): IO[WrappedOrganizationRejection, Stream[Task, Envelope[SchemaEvent]]] =
    eventLog.orgEvents(orgs, organization, moduleType, offset)

  override def events(offset: Offset): Stream[Task, Envelope[SchemaEvent]] =
    eventLog.eventsByTag(moduleType, offset)

  private def currentState(projectRef: ProjectRef, iri: Iri): IO[SchemaFetchRejection, SchemaState] =
    agg.state(identifier(projectRef, iri))

  private def stateAt(projectRef: ProjectRef, iri: Iri, rev: Long) =
    eventLog
      .fetchStateAt(persistenceId(moduleType, identifier(projectRef, iri)), rev, Initial, Schemas.next)
      .mapError(RevisionNotFound(rev, _))

  private def eval(cmd: SchemaCommand, project: Project): IO[SchemaRejection, SchemaResource] =
    for {
      evaluationResult <- agg.evaluate(identifier(cmd.project, cmd.id), cmd).mapError(_.value)
      (am, base)        = project.apiMappings -> project.base
      resource         <- IO.fromOption(evaluationResult.state.toResource(am, base), UnexpectedInitialState(cmd.id))
    } yield resource

  private def identifier(projectRef: ProjectRef, id: Iri): String =
    s"${projectRef}_$id"

}

object SchemasImpl {

  type SchemasAggregate =
    Aggregate[String, SchemaState, SchemaCommand, SchemaEvent, SchemaRejection]

  type SchemasCache = KeyValueStore[(ProjectRef, Iri, Long), SchemaResource]

  def aggregate(
      config: AggregateConfig,
      resourceIdCheck: ResourceIdCheck
  )(implicit api: JsonLdApi, as: ActorSystem[Nothing], clock: Clock[UIO]): UIO[SchemasAggregate] =
    aggregate(config, (project, id) => resourceIdCheck.isAvailableOr(project, id)(ResourceAlreadyExists(id, project)))

  private def aggregate(
      config: AggregateConfig,
      idAvailability: IdAvailability[ResourceAlreadyExists]
  )(implicit api: JsonLdApi, as: ActorSystem[Nothing], clock: Clock[UIO]): UIO[SchemasAggregate] = {
    val definition = PersistentEventDefinition(
      entityType = moduleType,
      initialState = Initial,
      next = Schemas.next,
      evaluate = Schemas.evaluate(idAvailability),
      tagger = (_: SchemaEvent) => Set.empty,
      snapshotStrategy = config.snapshotStrategy.strategy,
      stopStrategy = config.stopStrategy.persistentStrategy
    )

    ShardedAggregate.persistentSharded(
      definition = definition,
      config = config.processor
    )
  }

  def cache(config: SchemasConfig): UIO[SchemasCache] =
    KeyValueStore.localLRU(config.maxCacheSize)

  /**
    * Constructs a [[Schemas]] instance.
    */
  final def apply(
      orgs: Organizations,
      projects: Projects,
      schemaImports: SchemaImports,
      contextResolution: ResolverContextResolution,
      eventLog: EventLog[Envelope[SchemaEvent]],
      agg: SchemasAggregate,
      cache: SchemasCache
  )(implicit api: JsonLdApi, uuidF: UUIDF = UUIDF.random): Schemas = {
    val parser =
      new JsonLdSourceResolvingParser[SchemaRejection](
        List(contexts.shacl, contexts.schemasMetadata),
        contextResolution,
        uuidF
      )
    new SchemasImpl(
      agg,
      cache,
      orgs,
      projects,
      schemaImports,
      eventLog,
      parser
    )
  }

}
