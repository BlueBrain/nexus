package ch.epfl.bluebrain.nexus.ship.resources

import cats.effect.IO
import cats.implicits.catsSyntaxOptionId
import ch.epfl.bluebrain.nexus.delta.kernel.Logger
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.sdk.identities.model.Caller
import ch.epfl.bluebrain.nexus.delta.sdk.model.IdSegment
import ch.epfl.bluebrain.nexus.delta.sdk.projects.FetchContext
import ch.epfl.bluebrain.nexus.delta.sdk.resolvers.ResolverContextResolution
import ch.epfl.bluebrain.nexus.delta.sdk.resources.Resources.ResourceLog
import ch.epfl.bluebrain.nexus.delta.sdk.resources._
import ch.epfl.bluebrain.nexus.delta.sdk.resources.model.ResourceEvent
import ch.epfl.bluebrain.nexus.delta.sdk.resources.model.ResourceEvent._
import ch.epfl.bluebrain.nexus.delta.sdk.resources.model.ResourceRejection.{IncorrectRev, ResourceAlreadyExists}
import ch.epfl.bluebrain.nexus.delta.sourcing.model.EntityType
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.Subject
import ch.epfl.bluebrain.nexus.delta.sourcing.model.ResourceRef.Revision
import ch.epfl.bluebrain.nexus.ship._
import ch.epfl.bluebrain.nexus.ship.resources.ResourceProcessor.logger
import io.circe.Decoder

class ResourceProcessor private (
    resources: Resources,
    projectMapper: ProjectMapper,
    sourcePatcher: SourcePatcher,
    iriPatcher: IriPatcher,
    resourceTypesToIgnore: Set[Iri],
    clock: EventClock
) extends EventProcessor[ResourceEvent] {

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

    def patchSchema(resourceRef: Revision) = {
      val patched = iriPatcher(resourceRef.iri)
      Revision(patched, resourceRef.rev)
    }

    val skip = resourceTypesToIgnore.nonEmpty && event.types.intersect(resourceTypesToIgnore).nonEmpty
    if (!skip) {
      event match {
        case e: ResourceCreated       =>
          sourcePatcher(e.source).flatMap { patched =>
            val patchedSchema = patchSchema(e.schema)
            resources.create(e.id, project, patchedSchema, patched, e.tag)
          }
        case e: ResourceUpdated       =>
          sourcePatcher(e.source).flatMap { patched =>
            val patchedSchema = IdSegment.refToIriSegment(patchSchema(e.schema))
            resources.update(e.id, project, patchedSchema.some, cRev, patched, e.tag)
          }
        case e: ResourceSchemaUpdated =>
          val patchedSchema = patchSchema(e.schema)
          resources.updateAttachedSchema(e.id, project, patchedSchema)
        case e: ResourceRefreshed     =>
          val patchedSchema = IdSegment.refToIriSegment(patchSchema(e.schema))
          resources.refresh(e.id, project, patchedSchema.some)
        case e: ResourceTagAdded      =>
          resources.tag(e.id, project, None, e.tag, e.targetRev, cRev)
        case e: ResourceTagDeleted    =>
          resources.deleteTag(e.id, project, None, e.tag, cRev)
        case e: ResourceDeprecated    =>
          resources.deprecate(e.id, project, None, cRev)
        case e: ResourceUndeprecated  =>
          resources.undeprecate(e.id, project, None, cRev)
      }
    } else IO.unit
  }.redeemWith(
    {
      case a: ResourceAlreadyExists => logger.warn(a)("The resource already exists").as(ImportStatus.Dropped)
      case i: IncorrectRev          =>
        logger
          .warn(i)(s"An incorrect revision has been provided for '${event.id}' in project '${event.project}'")
          .as(ImportStatus.Dropped)
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
      sourcePatcher: SourcePatcher,
      iriPatcher: IriPatcher,
      resourceTypesToIgnore: Set[Iri],
      clock: EventClock
  ): ResourceProcessor = {
    val resources = ResourcesImpl(log, fetchContext, rcr)
    new ResourceProcessor(resources, projectMapper, sourcePatcher, iriPatcher, resourceTypesToIgnore, clock)
  }

}
