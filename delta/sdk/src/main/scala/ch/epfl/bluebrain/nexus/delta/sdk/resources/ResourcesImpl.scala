package ch.epfl.bluebrain.nexus.delta.sdk.resources

import cats.effect.Clock
import ch.epfl.bluebrain.nexus.delta.kernel.Transactors
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
import ch.epfl.bluebrain.nexus.delta.sdk.resolvers.ResolverResolution.ResourceResolution
import ch.epfl.bluebrain.nexus.delta.sdk.resources.Resources.{entityType, expandIri}
import ch.epfl.bluebrain.nexus.delta.sdk.resources.ResourcesImpl.ResourcesLog
import ch.epfl.bluebrain.nexus.delta.sdk.resources.model.ResourceCommand._
import ch.epfl.bluebrain.nexus.delta.sdk.resources.model.ResourceRejection.{InvalidResourceId, ProjectContextRejection, ResourceFetchRejection, ResourceNotFound, RevisionNotFound, TagNotFound}
import ch.epfl.bluebrain.nexus.delta.sdk.resources.model.{ResourceCommand, ResourceEvent, ResourceRejection, ResourceState}
import ch.epfl.bluebrain.nexus.delta.sdk.schemas.model.Schema
import ch.epfl.bluebrain.nexus.delta.sourcing._
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.Subject
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Tag.UserTag
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{EnvelopeStream, Label, ProjectRef, ResourceRef}
import ch.epfl.bluebrain.nexus.delta.sourcing.offset.Offset
import fs2.Stream
import io.circe.Json
import monix.bio.{IO, UIO}

final class ResourcesImpl private (
    log: ResourcesLog,
    fetchContext: FetchContext[ProjectContextRejection],
    sourceParser: JsonLdSourceResolvingParser[ResourceRejection]
) extends Resources {

  implicit private val kamonComponent: KamonMetricComponent = KamonMetricComponent(entityType.value)

  override def create(
      projectRef: ProjectRef,
      schema: IdSegment,
      source: Json
  )(implicit caller: Caller): IO[ResourceRejection, DataResource] = {
    for {
      projectContext             <- fetchContext.onCreate(projectRef)
      schemeRef                  <- expandResourceRef(schema, projectContext)
      (iri, compacted, expanded) <- sourceParser(projectRef, projectContext, source)
      res                        <- eval(CreateResource(iri, projectRef, schemeRef, source, compacted, expanded, caller), projectContext)
    } yield res
  }.span("createResource")

  override def create(
      id: IdSegment,
      projectRef: ProjectRef,
      schema: IdSegment,
      source: Json
  )(implicit caller: Caller): IO[ResourceRejection, DataResource] = {
    for {
      projectContext        <- fetchContext.onCreate(projectRef)
      iri                   <- expandIri(id, projectContext)
      schemeRef             <- expandResourceRef(schema, projectContext)
      (compacted, expanded) <- sourceParser(projectRef, projectContext, iri, source)
      res                   <- eval(CreateResource(iri, projectRef, schemeRef, source, compacted, expanded, caller), projectContext)
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
      projectContext        <- fetchContext.onModify(projectRef)
      iri                   <- expandIri(id, projectContext)
      schemeRefOpt          <- expandResourceRef(schemaOpt, projectContext)
      (compacted, expanded) <- sourceParser(projectRef, projectContext, iri, source)
      res                   <-
        eval(UpdateResource(iri, projectRef, schemeRefOpt, source, compacted, expanded, rev, caller), projectContext)
    } yield res
  }.span("updateResource")

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
      res            <- eval(TagResource(iri, projectRef, schemeRefOpt, tagRev, tag, rev, caller), projectContext)
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
      res            <- eval(DeleteResourceTag(iri, projectRef, schemeRefOpt, tag, rev, caller), projectContext)
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
      res            <- eval(DeprecateResource(iri, projectRef, schemeRefOpt, rev, caller), projectContext)
    } yield res).span("deprecateResource")

  override def fetch(
      id: IdSegmentRef,
      projectRef: ProjectRef,
      schemaOpt: Option[IdSegment]
  ): IO[ResourceFetchRejection, DataResource] = {
    for {
      pc           <- fetchContext.onRead(projectRef)
      iri          <- expandIri(id.value, pc)
      schemaRefOpt <- expandResourceRef(schemaOpt, pc)
      notFound      = ResourceNotFound(iri, projectRef, schemaRefOpt)
      state        <- id match {
                        case Latest(_)        => log.stateOr(projectRef, iri, notFound)
                        case Revision(_, rev) =>
                          log.stateOr(projectRef, iri, rev.toInt, notFound, RevisionNotFound)
                        case Tag(_, tag)      =>
                          log.stateOr(projectRef, iri, tag, notFound, TagNotFound(tag))
                      }
      _            <- IO.raiseWhen(schemaRefOpt.exists(_.iri != state.schema.iri))(notFound)
    } yield state.toResource(pc.apiMappings, pc.base)
  }.span("fetchResource")

  override def events(
      projectRef: ProjectRef,
      offset: Offset
  ): IO[ResourceRejection, EnvelopeStream[Iri, ResourceEvent]] =
    IO.pure(Stream.empty)

  override def currentEvents(
      projectRef: ProjectRef,
      offset: Offset
  ): IO[ResourceRejection, EnvelopeStream[Iri, ResourceEvent]] =
    IO.pure(Stream.empty)

  override def events(
      organization: Label,
      offset: Offset
  ): IO[ResourceRejection, EnvelopeStream[Iri, ResourceEvent]] =
    IO.pure(Stream.empty)

  override def events(offset: Offset): EnvelopeStream[Iri, ResourceEvent] =
    log.events(Predicate.root, offset)

  private def eval(cmd: ResourceCommand, pc: ProjectContext): IO[ResourceRejection, DataResource] =
    log.evaluate(cmd.project, cmd.id, cmd).map(_._2.toResource(pc.apiMappings, pc.base))

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
      resourceResolution: ResourceResolution[Schema],
      fetchContext: FetchContext[ProjectContextRejection],
      contextResolution: ResolverContextResolution,
      config: ResourcesConfig,
      xas: Transactors
  )(implicit api: JsonLdApi, clock: Clock[UIO], uuidF: UUIDF = UUIDF.random): Resources =
    new ResourcesImpl(
      ScopedEventLog(Resources.definition(resourceResolution), config.eventLog, xas),
      fetchContext,
      JsonLdSourceResolvingParser[ResourceRejection](contextResolution, uuidF)
    )

}
