package ch.epfl.bluebrain.nexus.delta.sdk.resources

import cats.effect.Clock
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
import ch.epfl.bluebrain.nexus.delta.sdk.resources.Resources.{entityType, expandIri}
import ch.epfl.bluebrain.nexus.delta.sdk.resources.ResourcesImpl.ResourcesLog
import ch.epfl.bluebrain.nexus.delta.sdk.resources.ValidateResource.ValidationResult
import ch.epfl.bluebrain.nexus.delta.sdk.resources.model.ResourceCommand._
import ch.epfl.bluebrain.nexus.delta.sdk.resources.model.ResourceRejection.{InvalidResourceId, ProjectContextRejection, ResourceFetchRejection, ResourceNotFound, RevisionNotFound, TagNotFound}
import ch.epfl.bluebrain.nexus.delta.sdk.resources.model.{ResourceCommand, ResourceEvent, ResourceRejection, ResourceState}
import ch.epfl.bluebrain.nexus.delta.sourcing._
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.Subject
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Tag.UserTag
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{ProjectRef, ResourceRef}
import io.circe.Json
import monix.bio.{IO, UIO}

final class ResourcesImpl private (
    log: ResourcesLog,
    fetchContext: FetchContext[ProjectContextRejection],
    sourceParser: JsonLdSourceResolvingParser[ResourceRejection],
    validateResource: ValidateResource
) extends Resources {

  implicit private val kamonComponent: KamonMetricComponent = KamonMetricComponent(entityType.value)

  override def create(
      projectRef: ProjectRef,
      schema: IdSegment,
      source: Json
  )(implicit caller: Caller): IO[ResourceRejection, DataResource] = {
    for {
      projectContext <- fetchContext.onCreate(projectRef)
      schemeRef      <- expandResourceRef(schema, projectContext)
      jsonld         <- sourceParser(projectRef, projectContext, source)
      res            <- eval(CreateResource(jsonld.iri, projectRef, schemeRef, source, jsonld, caller))
    } yield res
  }.span("createResource")

  override def create(
      id: IdSegment,
      projectRef: ProjectRef,
      schema: IdSegment,
      source: Json
  )(implicit caller: Caller): IO[ResourceRejection, DataResource] = {
    for {
      projectContext <- fetchContext.onCreate(projectRef)
      iri            <- expandIri(id, projectContext)
      schemeRef      <- expandResourceRef(schema, projectContext)
      jsonld         <- sourceParser(projectRef, projectContext, iri, source)
      res            <- eval(CreateResource(iri, projectRef, schemeRef, source, jsonld, caller))
    } yield res
  }.span("createResource")

  override def update(
      id: IdSegment,
      projectRef: ProjectRef,
      schemaOpt: Option[IdSegment],
      rev: Int,
      source: Json
  )(implicit caller: Caller): IO[ResourceRejection, DataResource] = {
    for {
      projectContext <- fetchContext.onModify(projectRef)
      iri            <- expandIri(id, projectContext)
      schemeRefOpt   <- expandResourceRef(schemaOpt, projectContext)
      jsonld         <- sourceParser(projectRef, projectContext, iri, source)
      res            <- eval(UpdateResource(iri, projectRef, schemeRefOpt, source, jsonld, rev, caller))
    } yield res
  }.span("updateResource")

  override def refresh(
      id: IdSegment,
      projectRef: ProjectRef,
      schemaOpt: Option[IdSegment]
  )(implicit caller: Caller): IO[ResourceRejection, DataResource] = {
    for {
      projectContext <- fetchContext.onModify(projectRef)
      iri            <- expandIri(id, projectContext)
      schemaRefOpt   <- expandResourceRef(schemaOpt, projectContext)
      resource       <- log.stateOr(projectRef, iri, ResourceNotFound(iri, projectRef, schemaRefOpt))
      jsonld         <- sourceParser(projectRef, projectContext, iri, resource.source)
      res            <- eval(RefreshResource(iri, projectRef, schemaRefOpt, jsonld, resource.rev, caller))
    } yield res
  }.span("refreshResource")

  override def validate(id: IdSegmentRef, projectRef: ProjectRef, schemaOpt: Option[IdSegment])(implicit
      caller: Caller
  ): IO[ResourceRejection, ValidationResult] = {
    fetch(id, projectRef, schemaOpt)
    for {
      projectContext <- fetchContext.onRead(projectRef)
      resourceRef    <- expandIri(id.value, projectContext)
      schemaRefOpt   <- expandResourceRef(schemaOpt, projectContext)
      notFound        = ResourceNotFound(resourceRef, projectRef, schemaRefOpt)
      state          <- id match {
                          case Latest(_)        => log.stateOr(projectRef, resourceRef, notFound)
                          case Revision(_, rev) =>
                            log.stateOr(projectRef, resourceRef, rev, notFound, RevisionNotFound)
                          case Tag(_, tag)      =>
                            log.stateOr(projectRef, resourceRef, tag, notFound, TagNotFound(tag))
                        }
      report         <- validateResource(projectRef, schemaRefOpt.getOrElse(state.schema), caller, state.id, state.expanded)
    } yield report
  }

  override def tag(
      id: IdSegment,
      projectRef: ProjectRef,
      schemaOpt: Option[IdSegment],
      tag: UserTag,
      tagRev: Int,
      rev: Int
  )(implicit caller: Subject): IO[ResourceRejection, DataResource] =
    (for {
      projectContext <- fetchContext.onModify(projectRef)
      iri            <- expandIri(id, projectContext)
      schemeRefOpt   <- expandResourceRef(schemaOpt, projectContext)
      res            <- eval(TagResource(iri, projectRef, schemeRefOpt, tagRev, tag, rev, caller))
    } yield res).span("tagResource")

  override def deleteTag(
      id: IdSegment,
      projectRef: ProjectRef,
      schemaOpt: Option[IdSegment],
      tag: UserTag,
      rev: Int
  )(implicit caller: Subject): IO[ResourceRejection, DataResource] =
    (for {
      projectContext <- fetchContext.onModify(projectRef)
      iri            <- expandIri(id, projectContext)
      schemeRefOpt   <- expandResourceRef(schemaOpt, projectContext)
      res            <- eval(DeleteResourceTag(iri, projectRef, schemeRefOpt, tag, rev, caller))
    } yield res).span("deleteResourceTag")

  override def deprecate(
      id: IdSegment,
      projectRef: ProjectRef,
      schemaOpt: Option[IdSegment],
      rev: Int
  )(implicit caller: Subject): IO[ResourceRejection, DataResource] =
    (for {
      projectContext <- fetchContext.onModify(projectRef)
      iri            <- expandIri(id, projectContext)
      schemeRefOpt   <- expandResourceRef(schemaOpt, projectContext)
      res            <- eval(DeprecateResource(iri, projectRef, schemeRefOpt, rev, caller))
    } yield res).span("deprecateResource")

  def fetchState(
      id: IdSegmentRef,
      projectRef: ProjectRef,
      schemaOpt: Option[IdSegment]
  ): IO[ResourceFetchRejection, ResourceState] = {
    for {
      pc           <- fetchContext.onRead(projectRef)
      iri          <- expandIri(id.value, pc)
      schemaRefOpt <- expandResourceRef(schemaOpt, pc)
      notFound      = ResourceNotFound(iri, projectRef, schemaRefOpt)
      state        <- id match {
                        case Latest(_)        => log.stateOr(projectRef, iri, notFound)
                        case Revision(_, rev) =>
                          log.stateOr(projectRef, iri, rev, notFound, RevisionNotFound)
                        case Tag(_, tag)      =>
                          log.stateOr(projectRef, iri, tag, notFound, TagNotFound(tag))
                      }
      _            <- IO.raiseWhen(schemaRefOpt.exists(_.iri != state.schema.iri))(notFound)
    } yield state
  }.span("fetchResource")

  override def fetch(
      id: IdSegmentRef,
      projectRef: ProjectRef,
      schemaOpt: Option[IdSegment]
  ): IO[ResourceFetchRejection, DataResource] = fetchState(id, projectRef, schemaOpt).map(_.toResource)

  private def eval(cmd: ResourceCommand): IO[ResourceRejection, DataResource] =
    log.evaluate(cmd.project, cmd.id, cmd).map(_._2.toResource)

  private def expandResourceRef(segment: IdSegment, context: ProjectContext): IO[InvalidResourceId, ResourceRef] =
    IO.fromOption(
      segment.toIri(context.apiMappings, context.base).map(ResourceRef(_)),
      InvalidResourceId(segment.asString)
    )

  private def expandResourceRef(
      segmentOpt: Option[IdSegment],
      context: ProjectContext
  ): IO[InvalidResourceId, Option[ResourceRef]] =
    segmentOpt match {
      case None         => IO.none
      case Some(schema) => expandResourceRef(schema, context).map(Some.apply)
    }
}

object ResourcesImpl {

  type ResourcesLog =
    ScopedEventLog[Iri, ResourceState, ResourceCommand, ResourceEvent, ResourceRejection]

  /**
    * Constructs a [[Resources]] instance.
    *
    * @param resourceResolution
    *   resolutions of schemas
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
      validator: ValidateResource,
      fetchContext: FetchContext[ProjectContextRejection],
      contextResolution: ResolverContextResolution,
      config: ResourcesConfig,
      xas: Transactors
  )(implicit api: JsonLdApi, clock: Clock[UIO], uuidF: UUIDF = UUIDF.random): Resources =
    new ResourcesImpl(
      ScopedEventLog(Resources.definition(validator), config.eventLog, xas),
      fetchContext,
      JsonLdSourceResolvingParser[ResourceRejection](contextResolution, uuidF),
      validator
    )

}
