package ch.epfl.bluebrain.nexus.delta.service.resources

import akka.actor.typed.ActorSystem
import akka.persistence.query.Offset
import cats.effect.Clock
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.RemoteContextResolution
import ch.epfl.bluebrain.nexus.delta.sdk.Resources.{moduleType, sourceAsJsonLD}
import ch.epfl.bluebrain.nexus.delta.sdk._
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Identity.Subject
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.{Project, ProjectRef}
import ch.epfl.bluebrain.nexus.delta.sdk.model.resources.ResourceCommand._
import ch.epfl.bluebrain.nexus.delta.sdk.model.resources.ResourceRejection._
import ch.epfl.bluebrain.nexus.delta.sdk.model.resources.ResourceState.Initial
import ch.epfl.bluebrain.nexus.delta.sdk.model.resources.{ResourceCommand, ResourceEvent, ResourceRejection, ResourceState}
import ch.epfl.bluebrain.nexus.delta.sdk.model.{Envelope, Label, ResourceRef}
import ch.epfl.bluebrain.nexus.delta.sdk.utils.UUIDF
import ch.epfl.bluebrain.nexus.delta.service.config.AggregateConfig
import ch.epfl.bluebrain.nexus.delta.service.resources.ResourcesImpl.ResourcesAggregate
import ch.epfl.bluebrain.nexus.delta.service.syntax._
import ch.epfl.bluebrain.nexus.sourcing._
import ch.epfl.bluebrain.nexus.sourcing.processor.EventSourceProcessor._
import ch.epfl.bluebrain.nexus.sourcing.processor.ShardedAggregate
import io.circe.Json
import monix.bio.{IO, Task, UIO}

final class ResourcesImpl private (
    agg: ResourcesAggregate,
    eventLog: EventLog[Envelope[ResourceEvent]]
)(implicit rcr: RemoteContextResolution, uuidF: UUIDF)
    extends Resources {

  override def create(
      project: Project,
      schema: ResourceRef,
      source: Json
  )(implicit caller: Subject): IO[ResourceRejection, DataResource] =
    Resources
      .sourceAsJsonLD(project, source)
      .flatMap { case (id, compacted, expanded) =>
        eval(CreateResource(id, project.ref, schema, source, compacted, expanded, caller))
      }
      .named("createResource", moduleType)

  override def create(
      id: Iri,
      project: ProjectRef,
      schema: ResourceRef,
      source: Json
  )(implicit caller: Subject): IO[ResourceRejection, DataResource] =
    sourceAsJsonLD(id, source)
      .flatMap { case (compacted, expanded) =>
        eval(CreateResource(id, project, schema, source, compacted, expanded, caller))
      }
      .named("createResource", moduleType)

  override def update(
      id: Iri,
      project: ProjectRef,
      schemaOpt: Option[ResourceRef],
      rev: Long,
      source: Json
  )(implicit caller: Subject): IO[ResourceRejection, DataResource] =
    sourceAsJsonLD(id, source)
      .flatMap { case (compacted, expanded) =>
        eval(UpdateResource(id, project, schemaOpt, source, compacted, expanded, rev, caller))
      }
      .named("updateResource", moduleType)

  override def tag(
      id: Iri,
      project: ProjectRef,
      schemaOpt: Option[ResourceRef],
      tag: Label,
      tagRev: Long,
      rev: Long
  )(implicit caller: Subject): IO[ResourceRejection, DataResource] =
    eval(TagResource(id, project, schemaOpt, tagRev, tag, rev, caller)).named("tagResource", moduleType)

  override def deprecate(
      id: Iri,
      project: ProjectRef,
      schemaOpt: Option[ResourceRef],
      rev: Long
  )(implicit caller: Subject): IO[ResourceRejection, DataResource] =
    eval(DeprecateResource(id, project, schemaOpt, rev, caller)).named("deprecateResource", moduleType)

  override def fetch(
      id: Iri,
      project: ProjectRef,
      schemaOpt: Option[ResourceRef]
  ): UIO[Option[DataResource]] =
    agg
      .state(identifier(project, id))
      .map(_.toResource)
      .map(validateSameSchema(_, schemaOpt))
      .named("fetchResource", moduleType)

  override def fetchAt(
      id: Iri,
      project: ProjectRef,
      schemaOpt: Option[ResourceRef],
      rev: Long
  ): IO[RevisionNotFound, Option[DataResource]] =
    eventLog
      .fetchStateAt(
        persistenceId(moduleType, identifier(project, id)),
        rev,
        Initial,
        Resources.next
      )
      .bimap(RevisionNotFound(rev, _), _.toResource)
      .map(validateSameSchema(_, schemaOpt))
      .named("fetchResourceAt", moduleType)

  override def fetchBy(
      id: Iri,
      project: ProjectRef,
      schemaOpt: Option[ResourceRef],
      tag: Label
  ): IO[TagNotFound, Option[DataResource]] =
    super.fetchBy(id, project, schemaOpt, tag).named("fetchResourceBy", moduleType)

  override def events(offset: Offset): fs2.Stream[Task, Envelope[ResourceEvent]] =
    eventLog.eventsByTag(moduleType, offset)

  override def currentEvents(offset: Offset): fs2.Stream[Task, Envelope[ResourceEvent]] =
    eventLog.currentEventsByTag(moduleType, offset)

  private def validateSameSchema(
      resourceOpt: Option[DataResource],
      schemaOpt: Option[ResourceRef]
  ): Option[DataResource] =
    resourceOpt match {
      case Some(value) if schemaOpt.forall(_ == value.schema) => Some(value)
      case _                                                  => None
    }

  private def eval(cmd: ResourceCommand): IO[ResourceRejection, DataResource] =
    for {
      evaluationResult <- agg.evaluate(identifier(cmd.project, cmd.id), cmd).mapError(_.value)
      resource         <- IO.fromOption(evaluationResult.state.toResource, UnexpectedInitialState(cmd.id))
    } yield resource

  private def identifier(projectRef: ProjectRef, id: Iri): String =
    s"${projectRef}_$id"
}

object ResourcesImpl {

  type ResourcesAggregate =
    Aggregate[String, ResourceState, ResourceCommand, ResourceEvent, ResourceRejection]

  private def aggregate(config: AggregateConfig, fetchSchema: ResourceRef => UIO[Option[SchemaResource]])(implicit
      as: ActorSystem[Nothing],
      clock: Clock[UIO],
      rcr: RemoteContextResolution
  ): UIO[ResourcesAggregate] = {
    val definition = PersistentEventDefinition(
      entityType = moduleType,
      initialState = Initial,
      next = Resources.next,
      evaluate = Resources.evaluate(fetchSchema),
      tagger = (ev: ResourceEvent) =>
        Set(
          moduleType,
          s"${Projects.moduleType}=${ev.project}",
          s"${Organizations.moduleType}=${ev.project.organization}"
        ),
      snapshotStrategy = config.snapshotStrategy.combinedStrategy(
        SnapshotStrategy.SnapshotPredicate((state: ResourceState, _: ResourceEvent, _: Long) => state.deprecated)
      ),
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
    * Constructs a [[Resources]] instance.
    *
    * @param config      the aggregate configuration
    * @param eventLog    the event log for [[ResourceEvent]]
    * @param fetchSchema a function to retrieve the schema based on the schema iri
    */
  final def apply(
      fetchSchema: ResourceRef => UIO[Option[SchemaResource]],
      config: AggregateConfig,
      eventLog: EventLog[Envelope[ResourceEvent]]
  )(implicit
      rcr: RemoteContextResolution,
      uuidF: UUIDF = UUIDF.random,
      as: ActorSystem[Nothing],
      clock: Clock[UIO]
  ): UIO[Resources] =
    aggregate(config, fetchSchema).map(agg => new ResourcesImpl(agg, eventLog))

}
