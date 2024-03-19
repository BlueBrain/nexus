package ch.epfl.bluebrain.nexus.ship.resources

import cats.effect.IO
import cats.implicits.catsSyntaxOptionId
import ch.epfl.bluebrain.nexus.delta.kernel.Logger
import ch.epfl.bluebrain.nexus.delta.kernel.utils.UUIDF
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.api.JsonLdApi
import ch.epfl.bluebrain.nexus.delta.sdk.identities.model.Caller
import ch.epfl.bluebrain.nexus.delta.sdk.jsonld.JsonLdAssembly
import ch.epfl.bluebrain.nexus.delta.sdk.model.{IdSegment, IdSegmentRef, ResourceF}
import ch.epfl.bluebrain.nexus.delta.sdk.projects.FetchContext
import ch.epfl.bluebrain.nexus.delta.sdk.resolvers.ResolverContextResolution
import ch.epfl.bluebrain.nexus.delta.sdk.resources.ValidationResult.NoValidation
import ch.epfl.bluebrain.nexus.delta.sdk.resources.model.ResourceEvent
import ch.epfl.bluebrain.nexus.delta.sdk.resources.model.ResourceEvent._
import ch.epfl.bluebrain.nexus.delta.sdk.resources.model.ResourceRejection.{IncorrectRev, ResourceAlreadyExists}
import ch.epfl.bluebrain.nexus.delta.sdk.resources._
import ch.epfl.bluebrain.nexus.delta.sdk.schemas.model.Schema
import ch.epfl.bluebrain.nexus.delta.sourcing.config.EventLogConfig
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.Subject
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{EntityType, ProjectRef, ResourceRef}
import ch.epfl.bluebrain.nexus.delta.sourcing.{ScopedEventLog, Transactors}
import ch.epfl.bluebrain.nexus.ship.resources.ResourceProcessor.logger
import ch.epfl.bluebrain.nexus.ship.{EventClock, EventProcessor, FailingUUID, ImportStatus}
import io.circe.Decoder

class ResourceProcessor private (resources: Resources, clock: EventClock) extends EventProcessor[ResourceEvent] {

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

    implicit class ResourceRefOps(ref: ResourceRef) {
      def toIdSegment: IdSegment = IdSegmentRef(ref).value
    }

    event match {
      case e: ResourceCreated       =>
        resources.create(e.id, e.project, e.schema.toIdSegment, e.source, e.tag)
      case e: ResourceUpdated       =>
        resources.update(e.id, event.project, e.schema.toIdSegment.some, cRev, e.source, e.tag)
      case e: ResourceSchemaUpdated =>
        resources.updateAttachedSchema(e.id, e.project, e.schema.toIdSegment)
      case e: ResourceRefreshed     =>
        resources.refresh(e.id, e.project, e.schema.toIdSegment.some)
      case e: ResourceTagAdded      => // todo deal with schema
        resources.tag(e.id, e.project, None, e.tag, e.targetRev, cRev)
      case e: ResourceTagDeleted    => // todo deal with schema
        resources.deleteTag(e.id, e.project, None, e.tag, cRev)
      case e: ResourceDeprecated    => // todo deal with schema
        resources.deprecate(e.id, e.project, None, cRev)
      case e: ResourceUndeprecated  => // todo deal with schema
        resources.undeprecate(e.id, e.project, None, cRev)
    }
  }.redeemWith(
    {
      case a: ResourceAlreadyExists => logger.warn(a)("The resource already exists").as(ImportStatus.Dropped)
      case i: IncorrectRev          => logger.warn(i)("An incorrect revision as been provided").as(ImportStatus.Dropped)
      case other                    => IO.raiseError(other)
    },
    _ => IO.pure(ImportStatus.Success)
  )

}

object ResourceProcessor {

  private val logger = Logger[ResourceProcessor]

  def apply(
      eventLogConfig: EventLogConfig,
      fetchContext: FetchContext,
      xas: Transactors
  )(implicit jsonLdApi: JsonLdApi): IO[ResourceProcessor] =
    EventClock.init().map { clock =>
      implicit val uuidF: UUIDF = FailingUUID

      val detectChange = DetectChange(false)

      val validate = new ValidateResource {
        override def apply(jsonld: JsonLdAssembly, schema: SchemaClaim, enforceSchema: Boolean): IO[ValidationResult] =
          IO.pure(NoValidation(ProjectRef.unsafe("org", "proj")))
        override def apply(jsonld: JsonLdAssembly, schema: ResourceF[Schema]): IO[ValidationResult]                   =
          IO.pure(NoValidation(ProjectRef.unsafe("org", "proj")))
      }

      val resourceDef = Resources.definition(validate, detectChange, clock)
      val resourceLog = ScopedEventLog(resourceDef, eventLogConfig, xas)

      val resources = ResourcesImpl(
        resourceLog,
        fetchContext,
        ResolverContextResolution.never
      )
      new ResourceProcessor(resources, clock)
    }

}
