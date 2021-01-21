package ch.epfl.bluebrain.nexus.delta.service.schemas

import akka.actor.typed.ActorSystem
import akka.persistence.query.Offset
import cats.effect.Clock
import cats.syntax.all._
import ch.epfl.bluebrain.nexus.delta.kernel.RetryStrategy
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.{contexts, nxv}
import ch.epfl.bluebrain.nexus.delta.sdk.Schemas._
import ch.epfl.bluebrain.nexus.delta.sdk._
import ch.epfl.bluebrain.nexus.delta.sdk.jsonld.JsonLdSourceProcessor.JsonLdSourceResolvingParser
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Caller
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Identity.Subject
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.{Project, ProjectRef}
import ch.epfl.bluebrain.nexus.delta.sdk.model.resolvers.ResolverContextResolution
import ch.epfl.bluebrain.nexus.delta.sdk.model.schemas.SchemaCommand._
import ch.epfl.bluebrain.nexus.delta.sdk.model.schemas.SchemaRejection._
import ch.epfl.bluebrain.nexus.delta.sdk.model.schemas.SchemaState.Initial
import ch.epfl.bluebrain.nexus.delta.sdk.model.schemas.{SchemaCommand, SchemaEvent, SchemaRejection, SchemaState}
import ch.epfl.bluebrain.nexus.delta.sdk.model.{Envelope, IdSegment, Label, TagLabel}
import ch.epfl.bluebrain.nexus.delta.sdk.utils.UUIDF
import ch.epfl.bluebrain.nexus.delta.service.schemas.SchemasImpl.SchemasAggregate
import ch.epfl.bluebrain.nexus.delta.service.syntax._
import ch.epfl.bluebrain.nexus.sourcing._
import ch.epfl.bluebrain.nexus.sourcing.config.AggregateConfig
import ch.epfl.bluebrain.nexus.sourcing.processor.EventSourceProcessor._
import ch.epfl.bluebrain.nexus.sourcing.processor.ShardedAggregate
import fs2.Stream
import io.circe.Json
import monix.bio.{IO, Task, UIO}

final class SchemasImpl private (
    agg: SchemasAggregate,
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
      project                    <- projects.fetchActiveProject(projectRef)
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
      project               <- projects.fetchActiveProject(projectRef)
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
      project               <- projects.fetchActiveProject(projectRef)
      iri                   <- expandIri(id, project)
      (compacted, expanded) <- sourceParser(project, iri, source)
      expandedResolved      <- schemaImports.resolve(iri, projectRef, expanded.addType(nxv.Schema))
      res                   <- eval(UpdateSchema(iri, projectRef, source, compacted, expandedResolved, rev, caller.subject), project)
    } yield res
  }.named("updateSchema", moduleType)

  override def tag(
      id: IdSegment,
      projectRef: ProjectRef,
      tag: TagLabel,
      tagRev: Long,
      rev: Long
  )(implicit caller: Subject): IO[SchemaRejection, SchemaResource] =
    (for {
      project <- projects.fetchActiveProject(projectRef)
      iri     <- expandIri(id, project)
      res     <- eval(TagSchema(iri, projectRef, tagRev, tag, rev, caller), project)
    } yield res).named("tagSchema", moduleType)

  override def deprecate(
      id: IdSegment,
      projectRef: ProjectRef,
      rev: Long
  )(implicit caller: Subject): IO[SchemaRejection, SchemaResource] =
    (for {
      project <- projects.fetchActiveProject(projectRef)
      iri     <- expandIri(id, project)
      res     <- eval(DeprecateSchema(iri, projectRef, rev, caller), project)
    } yield res).named("deprecateSchema", moduleType)

  override def fetch(id: IdSegment, projectRef: ProjectRef): IO[SchemaFetchRejection, SchemaResource] =
    fetch(id, projectRef, None).named("fetchSchema", moduleType)

  override def fetchAt(id: IdSegment, projectRef: ProjectRef, rev: Long): IO[SchemaFetchRejection, SchemaResource] =
    fetch(id, projectRef, Some(rev)).named("fetchSchemaAt", moduleType)

  private def fetch(
      id: IdSegment,
      projectRef: ProjectRef,
      rev: Option[Long]
  ): IO[SchemaFetchRejection, SchemaResource] =
    for {
      project <- projects.fetchProject(projectRef)
      iri     <- expandIri(id, project)
      state   <- rev.fold(currentState(projectRef, iri))(stateAt(projectRef, iri, _))
      res     <- IO.fromOption(state.toResource(project.apiMappings, project.base), SchemaNotFound(iri, projectRef))
    } yield res

  override def fetchBy(id: IdSegment, projectRef: ProjectRef, tag: TagLabel): IO[SchemaFetchRejection, SchemaResource] =
    super.fetchBy(id, projectRef, tag).named("fetchSchemaBy", moduleType)

  override def events(
      projectRef: ProjectRef,
      offset: Offset
  ): IO[SchemaRejection, Stream[Task, Envelope[SchemaEvent]]] =
    projects
      .fetchProject(projectRef)
      .as(eventLog.eventsByTag(s"${Projects.moduleType}=$projectRef", offset))

  override def events(
      organization: Label,
      offset: Offset
  ): IO[WrappedOrganizationRejection, Stream[Task, Envelope[SchemaEvent]]] =
    orgs
      .fetchOrganization(organization)
      .as(eventLog.eventsByTag(s"${Organizations.moduleType}=$organization", offset))

  override def events(offset: Offset): Stream[Task, Envelope[SchemaEvent]] =
    eventLog.eventsByTag(moduleType, offset)

  private def currentState(projectRef: ProjectRef, iri: Iri): IO[SchemaFetchRejection, SchemaState] =
    agg.state(identifier(projectRef, iri))

  private def stateAt(projectRef: ProjectRef, iri: Iri, rev: Long) =
    eventLog
      .fetchStateAt(persistenceId(moduleType, identifier(projectRef, iri)), rev, Initial, Schemas.next)
      .leftMap(RevisionNotFound(rev, _))

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

  private def aggregate(config: AggregateConfig)(implicit
      as: ActorSystem[Nothing],
      clock: Clock[UIO]
  ): UIO[SchemasAggregate] = {
    val definition = PersistentEventDefinition(
      entityType = moduleType,
      initialState = Initial,
      next = Schemas.next,
      evaluate = Schemas.evaluate,
      tagger = (ev: SchemaEvent) =>
        Set(
          moduleType,
          s"${Projects.moduleType}=${ev.project}",
          s"${Organizations.moduleType}=${ev.project.organization}"
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

  /**
    * Constructs a [[Schemas]] instance.
    *
    * @param orgs          the organizations operations bundle
    * @param projects      the project operations bundle
    * @param schemaImports resolves the OWL imports from a Schema
    * @param contextResolution the context resolver
    * @param config        the aggregate configuration
    * @param eventLog      the event log for [[SchemaEvent]]
    */
  final def apply(
      orgs: Organizations,
      projects: Projects,
      schemaImports: SchemaImports,
      contextResolution: ResolverContextResolution,
      config: AggregateConfig,
      eventLog: EventLog[Envelope[SchemaEvent]]
  )(implicit
      uuidF: UUIDF = UUIDF.random,
      as: ActorSystem[Nothing],
      clock: Clock[UIO]
  ): UIO[Schemas] =
    aggregate(config).map(agg =>
      new SchemasImpl(
        agg,
        orgs,
        projects,
        schemaImports,
        eventLog,
        new JsonLdSourceResolvingParser[SchemaRejection](Some(contexts.shacl), contextResolution, uuidF)
      )
    )

}
