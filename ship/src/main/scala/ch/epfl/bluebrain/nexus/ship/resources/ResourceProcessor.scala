package ch.epfl.bluebrain.nexus.ship.resources

import cats.effect.IO
import cats.implicits.catsSyntaxOptionId
import ch.epfl.bluebrain.nexus.delta.kernel.Logger
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.api.JsonLdApi
import ch.epfl.bluebrain.nexus.delta.sdk.identities.model.Caller
import ch.epfl.bluebrain.nexus.delta.sdk.model.{IdSegment, IdSegmentRef}
import ch.epfl.bluebrain.nexus.delta.sdk.projects.FetchContext
import ch.epfl.bluebrain.nexus.delta.sdk.resolvers.ResolverContextResolution
import ch.epfl.bluebrain.nexus.delta.sdk.resources.Resources.ResourceLog
import ch.epfl.bluebrain.nexus.delta.sdk.resources._
import ch.epfl.bluebrain.nexus.delta.sdk.resources.model.ResourceEvent
import ch.epfl.bluebrain.nexus.delta.sdk.resources.model.ResourceEvent._
import ch.epfl.bluebrain.nexus.delta.sdk.resources.model.ResourceRejection.{IncorrectRev, ResourceAlreadyExists}
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.Subject
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{EntityType, ResourceRef}
import ch.epfl.bluebrain.nexus.ship.resources.ResourceProcessor.logger
import ch.epfl.bluebrain.nexus.ship.{EventClock, EventProcessor, ImportStatus, ProjectMapper}
import io.circe.Decoder

class ResourceProcessor private (resources: Resources, projectMapper: ProjectMapper, clock: EventClock)
    extends EventProcessor[ResourceEvent] {

  override def resourceType: EntityType = Resources.entityType

  override def decoder: Decoder[ResourceEvent] = ResourceEvent.serializer.codec

  override def evaluate(event: ResourceEvent): IO[ImportStatus] =
    for {
      _      <- clock.setInstant(event.instant)
      result <- evaluateInternal(event)
    } yield result

  private def evaluateInternal(event: ResourceEvent): IO[ImportStatus] = {
    implicit val s: Subject = event.subject
    implicit val c: Caller  = Caller(s, Set.empty)
    val cRev                = event.rev - 1
    val project             = projectMapper.map(event.project)

    implicit class ResourceRefOps(ref: ResourceRef) {
      def toIdSegment: IdSegment = IdSegmentRef(ref).value
    }

    event match {
      case e: ResourceCreated       =>
        resources.create(e.id, project, e.schema.toIdSegment, e.source, e.tag)
      case e: ResourceUpdated       =>
        resources.update(e.id, project, e.schema.toIdSegment.some, cRev, e.source, e.tag)
      case e: ResourceSchemaUpdated =>
        resources.updateAttachedSchema(e.id, project, e.schema.toIdSegment)
      case e: ResourceRefreshed     =>
        resources.refresh(e.id, project, e.schema.toIdSegment.some)
      case e: ResourceTagAdded      =>
        resources.tag(e.id, project, None, e.tag, e.targetRev, cRev)
      case e: ResourceTagDeleted    =>
        resources.deleteTag(e.id, project, None, e.tag, cRev)
      case e: ResourceDeprecated    =>
        resources.deprecate(e.id, project, None, cRev)
      case e: ResourceUndeprecated  =>
        resources.undeprecate(e.id, project, None, cRev)
    }
  }.redeemWith(
    {
      case a: ResourceAlreadyExists => logger.warn(a)("The resource already exists").as(ImportStatus.Dropped)
      case i: IncorrectRev          => logger.warn(i)("An incorrect revision has been provided").as(ImportStatus.Dropped)
      case other                    => IO.raiseError(other)
    },
    _ => IO.pure(ImportStatus.Success)
  )

}

object ResourceProcessor {

  private val logger = Logger[ResourceProcessor]

  def apply(
      log: ResourceLog,
      rcr: ResolverContextResolution,
      projectMapper: ProjectMapper,
      fetchContext: FetchContext,
      clock: EventClock
  )(implicit jsonLdApi: JsonLdApi): ResourceProcessor = {
    val resources = ResourcesImpl(log, fetchContext, rcr)
    new ResourceProcessor(resources, projectMapper, clock)
  }

}
