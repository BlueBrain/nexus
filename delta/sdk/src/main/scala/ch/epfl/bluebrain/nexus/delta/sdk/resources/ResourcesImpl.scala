package ch.epfl.bluebrain.nexus.delta.sdk.resources

import cats.effect.{Clock, IO}
import cats.implicits._
import ch.epfl.bluebrain.nexus.delta.kernel.effect.migration._
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
import ch.epfl.bluebrain.nexus.delta.sdk.resolvers.ResolverContextResolution
import ch.epfl.bluebrain.nexus.delta.sdk.resources.Resources.{entityType, expandIri, expandResourceRef}
import ch.epfl.bluebrain.nexus.delta.sdk.resources.ResourcesImpl.ResourcesLog
import ch.epfl.bluebrain.nexus.delta.sdk.resources.model.ResourceCommand._
import ch.epfl.bluebrain.nexus.delta.sdk.resources.model.ResourceRejection.{IdenticalSchema, ProjectContextRejection, ResourceNotFound, RevisionNotFound, TagNotFound}
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
      projectContext <- fetchContext.onCreate(projectRef).toCatsIO
      schemeRef      <- expandResourceRef(schema, projectContext)
      jsonld         <- sourceParser(projectRef, projectContext, source).toCatsIO
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
      projectContext <- fetchContext.onCreate(projectRef).toCatsIO
      iri            <- expandIri(id, projectContext).toCatsIO
      schemeRef      <- expandResourceRef(schema, projectContext)
      jsonld         <- sourceParser(projectRef, projectContext, iri, source).toCatsIO
      res            <- eval(CreateResource(iri, projectRef, schemeRef, source, jsonld, caller, tag))
    } yield res
  }.span("createResource")

  override def update(
      id: IdSegment,
      projectRef: ProjectRef,
      schemaOpt: Option[IdSegment],
      rev: Int,
      source: Json
  )(implicit caller: Caller): IO[DataResource] = {
    for {
      projectContext <- fetchContext.onModify(projectRef).toCatsIO
      iri            <- expandIri(id, projectContext).toCatsIO
      schemeRefOpt   <- expandResourceRef(schemaOpt, projectContext)
      jsonld         <- sourceParser(projectRef, projectContext, iri, source).toCatsIO
      res            <- eval(UpdateResource(iri, projectRef, schemeRefOpt, source, jsonld, rev, caller))
    } yield res
  }.span("updateResource")

  override def updateResourceSchema(
      id: IdSegment,
      projectRef: ProjectRef,
      schema: IdSegment
  )(implicit caller: Caller): IO[DataResource] = {
    for {
      projectContext <- fetchContext.onModify(projectRef).toCatsIO
      iri            <- expandIri(id, projectContext).toCatsIO
      schemaRef      <- expandResourceRef(schema, projectContext)
      resource       <- log.stateOr(projectRef, iri, ResourceNotFound(iri, projectRef)).toCatsIO
      _              <- IO.raiseWhen(schemaRef.iri == resource.schema.iri)(IdenticalSchema())
      jsonld         <- sourceParser(projectRef, projectContext, iri, resource.source).toCatsIO
      res            <- eval(UpdateResource(iri, projectRef, schemaRef.some, resource.source, jsonld, resource.rev, caller))
    } yield res
  }.span("updateResourceSchema")

  override def refresh(
      id: IdSegment,
      projectRef: ProjectRef,
      schemaOpt: Option[IdSegment]
  )(implicit caller: Caller): IO[DataResource] = {
    for {
      projectContext <- fetchContext.onModify(projectRef).toCatsIO
      iri            <- expandIri(id, projectContext).toCatsIO
      schemaRefOpt   <- expandResourceRef(schemaOpt, projectContext)
      resource       <- log.stateOr(projectRef, iri, ResourceNotFound(iri, projectRef)).toCatsIO
      jsonld         <- sourceParser(projectRef, projectContext, iri, resource.source).toCatsIO
      res            <- eval(RefreshResource(iri, projectRef, schemaRefOpt, jsonld, resource.rev, caller))
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
      projectContext <- fetchContext.onModify(projectRef).toCatsIO
      iri            <- expandIri(id, projectContext).toCatsIO
      schemeRefOpt   <- expandResourceRef(schemaOpt, projectContext)
      res            <- eval(TagResource(iri, projectRef, schemeRefOpt, tagRev, tag, rev, caller))
    } yield res).span("tagResource")

  override def deleteTag(
      id: IdSegment,
      projectRef: ProjectRef,
      schemaOpt: Option[IdSegment],
      tag: UserTag,
      rev: Int
  )(implicit caller: Subject): IO[DataResource] =
    (for {
      projectContext <- fetchContext.onModify(projectRef).toCatsIO
      iri            <- expandIri(id, projectContext).toCatsIO
      schemeRefOpt   <- expandResourceRef(schemaOpt, projectContext)
      res            <- eval(DeleteResourceTag(iri, projectRef, schemeRefOpt, tag, rev, caller))
    } yield res).span("deleteResourceTag")

  override def deprecate(
      id: IdSegment,
      projectRef: ProjectRef,
      schemaOpt: Option[IdSegment],
      rev: Int
  )(implicit caller: Subject): IO[DataResource] =
    (for {
      projectContext <- fetchContext.onModify(projectRef).toCatsIO
      iri            <- expandIri(id, projectContext).toCatsIO
      schemeRefOpt   <- expandResourceRef(schemaOpt, projectContext)
      res            <- eval(DeprecateResource(iri, projectRef, schemeRefOpt, rev, caller))
    } yield res).span("deprecateResource")

  def fetchState(
      id: IdSegmentRef,
      projectRef: ProjectRef,
      schemaOpt: Option[IdSegment]
  ): IO[ResourceState] = {
    for {
      pc           <- fetchContext.onRead(projectRef).toCatsIO
      iri          <- expandIri(id.value, pc).toCatsIO
      schemaRefOpt <- expandResourceRef(schemaOpt, pc)
      notFound      = ResourceNotFound(iri, projectRef)
      state        <- id match {
                        case Latest(_)        => log.stateOr(projectRef, iri, notFound).toCatsIO
                        case Revision(_, rev) =>
                          log.stateOr(projectRef, iri, rev, notFound, RevisionNotFound).toCatsIO
                        case Tag(_, tag)      =>
                          log.stateOr(projectRef, iri, tag, notFound, TagNotFound(tag)).toCatsIO
                      }
      _            <- IO.raiseWhen(schemaRefOpt.exists(_.iri != state.schema.iri))(notFound)
    } yield state
  }.span("fetchResource")

  override def fetch(
      id: IdSegmentRef,
      projectRef: ProjectRef,
      schemaOpt: Option[IdSegment]
  ): IO[DataResource] = fetchState(id, projectRef, schemaOpt).map(_.toResource)

  private def eval(cmd: ResourceCommand): IO[DataResource] =
    log.evaluate(cmd.project, cmd.id, cmd).map(_._2.toResource)
}

object ResourcesImpl {

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
  )(implicit api: JsonLdApi, clock: Clock[IO], uuidF: UUIDF = UUIDF.random): Resources =
    new ResourcesImpl(
      ScopedEventLog(Resources.definition(validateResource), config.eventLog, xas),
      fetchContext,
      JsonLdSourceResolvingParser[ResourceRejection](contextResolution, uuidF)
    )

}
