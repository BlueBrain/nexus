package ch.epfl.bluebrain.nexus.ship.schemas

import cats.effect.IO
import ch.epfl.bluebrain.nexus.delta.kernel.Logger
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.api.JsonLdApi
import ch.epfl.bluebrain.nexus.delta.sdk.identities.model.Caller
import ch.epfl.bluebrain.nexus.delta.sdk.projects.FetchContext
import ch.epfl.bluebrain.nexus.delta.sdk.resolvers.ResolverContextResolution
import ch.epfl.bluebrain.nexus.delta.sdk.schemas.Schemas.SchemaLog
import ch.epfl.bluebrain.nexus.delta.sdk.schemas.model.SchemaEvent
import ch.epfl.bluebrain.nexus.delta.sdk.schemas.model.SchemaEvent._
import ch.epfl.bluebrain.nexus.delta.sdk.schemas.model.SchemaRejection.{IncorrectRev, ResourceAlreadyExists}
import ch.epfl.bluebrain.nexus.delta.sdk.schemas.{SchemaImports, Schemas, SchemasImpl}
import ch.epfl.bluebrain.nexus.delta.sourcing.model.EntityType
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.Subject
import ch.epfl.bluebrain.nexus.ship.schemas.SchemaProcessor.logger
import ch.epfl.bluebrain.nexus.ship.{EventClock, EventProcessor, FailingUUID, ImportStatus}
import io.circe.Decoder

class SchemaProcessor private (schemas: Schemas, clock: EventClock) extends EventProcessor[SchemaEvent] {

  override def resourceType: EntityType = Schemas.entityType

  override def decoder: Decoder[SchemaEvent] = SchemaEvent.serializer.codec

  override def evaluate(event: SchemaEvent): IO[ImportStatus] = {
    for {
      _      <- clock.setInstant(event.instant)
      result <- evaluateInternal(event)
    } yield result
  }

  private def evaluateInternal(event: SchemaEvent): IO[ImportStatus] = {
    implicit val s: Subject = event.subject
    implicit val c: Caller  = Caller(s, Set.empty)
    val cRev                = event.rev - 1

    event match {
      case e: SchemaCreated      => schemas.create(e.id, e.project, e.source)
      case e: SchemaUpdated      => schemas.update(e.id, e.project, cRev, e.source)
      case e: SchemaRefreshed    => schemas.refresh(e.id, e.project)
      case e: SchemaTagAdded     => schemas.tag(e.id, e.project, e.tag, e.targetRev, cRev)
      case e: SchemaTagDeleted   => schemas.deleteTag(e.id, e.project, e.tag, cRev)
      case e: SchemaDeprecated   => schemas.deprecate(e.id, e.project, cRev)
      case e: SchemaUndeprecated => schemas.undeprecate(e.id, e.project, cRev)
    }
  }.redeemWith(
    {
      case a: ResourceAlreadyExists => logger.warn(a)("The schema already exists").as(ImportStatus.Dropped)
      case i: IncorrectRev          => logger.warn(i)("An incorrect revision has been provided").as(ImportStatus.Dropped)
      case other                    => IO.raiseError(other)
    },
    _ => IO.pure(ImportStatus.Success)
  )

}

object SchemaProcessor {

  private val logger = Logger[SchemaProcessor]

  def apply(
      log: SchemaLog,
      fetchContext: FetchContext,
      schemaImports: SchemaImports,
      resolverContextResolution: IO[ResolverContextResolution],
      clock: EventClock
  )(implicit jsonLdApi: JsonLdApi): IO[SchemaProcessor] =
    for {
      rcr    <- resolverContextResolution
      schemas = SchemasImpl(log, fetchContext, schemaImports, rcr)(jsonLdApi, FailingUUID)
    } yield new SchemaProcessor(schemas, clock)

}
