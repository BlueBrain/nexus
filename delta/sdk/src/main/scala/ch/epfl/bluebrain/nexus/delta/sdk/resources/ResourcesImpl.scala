package ch.epfl.bluebrain.nexus.delta.sdk.resources

import cats.effect.{Clock, ContextShift, IO, Timer}
import cats.syntax.all._
import ch.epfl.bluebrain.nexus.delta.kernel.Logger
import ch.epfl.bluebrain.nexus.delta.kernel.kamon.KamonMetricComponent
import ch.epfl.bluebrain.nexus.delta.kernel.utils.UUIDF
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.api.JsonLdApi
import ch.epfl.bluebrain.nexus.delta.sdk._
import ch.epfl.bluebrain.nexus.delta.sdk.identities.model.Caller
import ch.epfl.bluebrain.nexus.delta.sdk.implicits._
import ch.epfl.bluebrain.nexus.delta.sdk.jsonld.JsonLdSourceProcessor.JsonLdSourceResolvingParser
import ch.epfl.bluebrain.nexus.delta.sdk.model.IdSegmentRef.{Latest, Revision, Tag}
import ch.epfl.bluebrain.nexus.delta.sdk.model._
import ch.epfl.bluebrain.nexus.delta.sdk.projects.FetchContext
import ch.epfl.bluebrain.nexus.delta.sdk.projects.model.ProjectContext
import ch.epfl.bluebrain.nexus.delta.sdk.resolvers.ResolverContextResolution
import ch.epfl.bluebrain.nexus.delta.sdk.resources.Resources.{entityType, expandIri, expandResourceRef}
import ch.epfl.bluebrain.nexus.delta.sdk.resources.ResourcesImpl.{logger, ResourcesLog}
import ch.epfl.bluebrain.nexus.delta.sdk.resources.model.ResourceCommand._
import ch.epfl.bluebrain.nexus.delta.sdk.resources.model.ResourceRejection.{NoChangeDetected, ProjectContextRejection, ResourceNotFound, RevisionNotFound, TagNotFound}
import ch.epfl.bluebrain.nexus.delta.sdk.resources.model.{ResourceCommand, ResourceEvent, ResourceRejection, ResourceState}
import ch.epfl.bluebrain.nexus.delta.sourcing._
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.Subject
import ch.epfl.bluebrain.nexus.delta.sourcing.model.ProjectRef
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Tag.UserTag
import io.circe.Json

final class ResourcesImpl private (
    log: ResourcesLog,
    fetchContext: FetchContext[ProjectContextRejection],
    sourceParser: JsonLdSourceResolvingParser[ResourceRejection]
) extends Resources {

  implicit private val kamonComponent: KamonMetricComponent = KamonMetricComponent(entityType.value)

  override def create(
      projectRef: ProjectRef,
      schema: IdSegment,
      source: Json,
      tag: Option[UserTag]
  )(implicit caller: Caller): IO[DataResource] = {
    for {
      projectContext <- fetchContext.onCreate(projectRef)
      schemeRef      <- IO.fromEither(expandResourceRef(schema, projectContext))
      jsonld         <- sourceParser(projectRef, projectContext, source)
      res            <- eval(CreateResource(jsonld.iri, projectRef, schemeRef, source, jsonld, caller, tag))
    } yield res
  }.span("createResource")

  override def create(
      id: IdSegment,
      projectRef: ProjectRef,
      schema: IdSegment,
      source: Json,
      tag: Option[UserTag]
  )(implicit caller: Caller): IO[DataResource] = {
    for {
      (iri, projectContext) <- expandWithContext(fetchContext.onCreate, projectRef, id)
      schemeRef             <- IO.fromEither(expandResourceRef(schema, projectContext))
      jsonld                <- sourceParser(projectRef, projectContext, iri, source)
      res                   <- eval(CreateResource(iri, projectRef, schemeRef, source, jsonld, caller, tag))
    } yield res
  }.span("createResource")

  override def update(
      id: IdSegment,
      projectRef: ProjectRef,
      schemaOpt: Option[IdSegment],
      rev: Int,
      source: Json,
      tag: Option[UserTag]
  )(implicit caller: Caller): IO[DataResource] = {
    for {
      (iri, projectContext) <- expandWithContext(fetchContext.onModify, projectRef, id)
      schemeRefOpt          <- IO.fromEither(expandResourceRef(schemaOpt, projectContext))
      jsonld                <- sourceParser(projectRef, projectContext, iri, source)
      res                   <- eval(UpdateResource(iri, projectRef, schemeRefOpt, source, jsonld, rev, caller, tag))
    } yield res
  }.span("updateResource")

  override def updateAttachedSchema(
      id: IdSegment,
      projectRef: ProjectRef,
      schema: IdSegment
  )(implicit caller: Caller): IO[DataResource] = {
    for {
      (iri, projectContext) <- expandWithContext(fetchContext.onModify, projectRef, id)
      schemaRef             <- IO.fromEither(expandResourceRef(schema, projectContext))
      resource              <- log.stateOr(projectRef, iri, ResourceNotFound(iri, projectRef))
      res                   <- eval(UpdateResourceSchema(iri, projectRef, schemaRef, resource.rev, caller, None))
    } yield res
  }.span("updateResourceSchema")

  override def refresh(
      id: IdSegment,
      projectRef: ProjectRef,
      schemaOpt: Option[IdSegment]
  )(implicit caller: Caller): IO[DataResource] = {
    for {
      (iri, projectContext) <- expandWithContext(fetchContext.onModify, projectRef, id)
      schemaRefOpt          <- IO.fromEither(expandResourceRef(schemaOpt, projectContext))
      resource              <- log.stateOr(projectRef, iri, ResourceNotFound(iri, projectRef))
      jsonld                <- sourceParser(projectRef, projectContext, iri, resource.source)
      res                   <- eval(RefreshResource(iri, projectRef, schemaRefOpt, jsonld, resource.rev, caller))
    } yield res
  }.span("refreshResource")

  override def tag(
      id: IdSegment,
      projectRef: ProjectRef,
      schemaOpt: Option[IdSegment],
      tag: UserTag,
      tagRev: Int,
      rev: Int
  )(implicit caller: Subject): IO[DataResource] =
    (for {
      (iri, projectContext) <- expandWithContext(fetchContext.onModify, projectRef, id)
      schemeRefOpt          <- IO.fromEither(expandResourceRef(schemaOpt, projectContext))
      res                   <- eval(TagResource(iri, projectRef, schemeRefOpt, tagRev, tag, rev, caller))
    } yield res).span("tagResource")

  override def deleteTag(
      id: IdSegment,
      projectRef: ProjectRef,
      schemaOpt: Option[IdSegment],
      tag: UserTag,
      rev: Int
  )(implicit caller: Subject): IO[DataResource] =
    (for {
      (iri, projectContext) <- expandWithContext(fetchContext.onModify, projectRef, id)
      schemeRefOpt          <- IO.fromEither(expandResourceRef(schemaOpt, projectContext))
      res                   <- eval(DeleteResourceTag(iri, projectRef, schemeRefOpt, tag, rev, caller))
    } yield res).span("deleteResourceTag")

  override def deprecate(
      id: IdSegment,
      projectRef: ProjectRef,
      schemaOpt: Option[IdSegment],
      rev: Int
  )(implicit caller: Subject): IO[DataResource] =
    (for {
      (iri, projectContext) <- expandWithContext(fetchContext.onModify, projectRef, id)
      schemeRefOpt          <- IO.fromEither(expandResourceRef(schemaOpt, projectContext))
      res                   <- eval(DeprecateResource(iri, projectRef, schemeRefOpt, rev, caller))
    } yield res).span("deprecateResource")

  override def undeprecate(
      id: IdSegment,
      projectRef: ProjectRef,
      schemaOpt: Option[IdSegment],
      rev: Int
  )(implicit caller: Subject): IO[DataResource] =
    (for {
      (iri, projectContext) <- expandWithContext(fetchContext.onModify, projectRef, id)
      schemaRefOpt          <- IO.fromEither(expandResourceRef(schemaOpt, projectContext))
      res                   <- eval(UndeprecateResource(iri, projectRef, schemaRefOpt, rev, caller))
    } yield res).span("undeprecateResource")

  def fetchState(
      id: IdSegmentRef,
      projectRef: ProjectRef,
      schemaOpt: Option[IdSegment]
  ): IO[ResourceState] = {
    for {
      (iri, pc)    <- expandWithContext(fetchContext.onRead, projectRef, id.value)
      schemaRefOpt <- IO.fromEither(expandResourceRef(schemaOpt, pc))
      state        <- stateOrNotFound(id, iri, projectRef)
      _            <- IO.raiseWhen(schemaRefOpt.exists(_.iri != state.schema.iri))(notFound(iri, projectRef))
    } yield state
  }.span("fetchResource")

  private def stateOrNotFound(id: IdSegmentRef, iri: Iri, ref: ProjectRef): IO[ResourceState] = id match {
    case Latest(_)        => log.stateOr(ref, iri, notFound(iri, ref))
    case Revision(_, rev) => log.stateOr(ref, iri, rev, notFound(iri, ref), RevisionNotFound)
    case Tag(_, tag)      => log.stateOr(ref, iri, tag, notFound(iri, ref), TagNotFound(tag))
  }

  private def notFound(iri: Iri, ref: ProjectRef) = ResourceNotFound(iri, ref)

  override def fetch(
      id: IdSegmentRef,
      projectRef: ProjectRef,
      schemaOpt: Option[IdSegment]
  ): IO[DataResource] = fetchState(id, projectRef, schemaOpt).map(_.toResource)

  private def expandWithContext(
      fetchCtx: ProjectRef => IO[ProjectContext],
      ref: ProjectRef,
      id: IdSegment
  ): IO[(Iri, ProjectContext)]                             =
    fetchCtx(ref).flatMap(pc => expandIri(id, pc).map(_ -> pc))

  private def eval(cmd: ResourceCommand): IO[DataResource] =
    log.evaluate(cmd.project, cmd.id, cmd).map(_._2.toResource).recoverWith { case NoChangeDetected(currentState) =>
      val message =
        s"""Command ${cmd.getClass.getSimpleName} from '${cmd.subject}' did not result in any change on resource '${cmd.id}'
           |in project '${cmd.project}', returning the original value.""".stripMargin
      logger.info(message).as(currentState.toResource)
    }
}

object ResourcesImpl {

  private val logger = Logger[ResourcesImpl]

  type ResourcesLog =
    ScopedEventLog[Iri, ResourceState, ResourceCommand, ResourceEvent, ResourceRejection]

  /**
    * Constructs a [[Resources]] instance.
    *
    * @param validateResource
    *   how to validate resource
    * @param fetchContext
    *   to fetch the project context
    * @param contextResolution
    *   the context resolver
    * @param config
    *   the resources config
    * @param xas
    *   the database context
    */
  final def apply(
      validateResource: ValidateResource,
      fetchContext: FetchContext[ProjectContextRejection],
      contextResolution: ResolverContextResolution,
      config: ResourcesConfig,
      xas: Transactors
  )(implicit
      api: JsonLdApi,
      clock: Clock[IO],
      contextShift: ContextShift[IO],
      timer: Timer[IO],
      uuidF: UUIDF = UUIDF.random
  ): Resources =
    new ResourcesImpl(
      ScopedEventLog(Resources.definition(validateResource), config.eventLog, xas),
      fetchContext,
      JsonLdSourceResolvingParser[ResourceRejection](contextResolution, uuidF)
    )

}
