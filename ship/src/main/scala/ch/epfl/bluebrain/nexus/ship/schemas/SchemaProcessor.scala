package ch.epfl.bluebrain.nexus.ship.schemas

import cats.effect.{Clock, IO}
import ch.epfl.bluebrain.nexus.delta.kernel.Logger
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.api.JsonLdApi
import ch.epfl.bluebrain.nexus.delta.sdk.identities.model.Caller
import ch.epfl.bluebrain.nexus.delta.sdk.projects.FetchContext
import ch.epfl.bluebrain.nexus.delta.sdk.resolvers.ResolverContextResolution
import ch.epfl.bluebrain.nexus.delta.sdk.schemas.Schemas.SchemaLog
import ch.epfl.bluebrain.nexus.delta.sdk.schemas.model.SchemaEvent
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

  // TODO: Provide a correct implementation
  private def evaluateInternal(event: SchemaEvent): IO[ImportStatus] = {
    implicit val s: Subject = event.subject
    implicit val c: Caller  = Caller(s, Set.empty)

    val id         = event.id
    val projectRef = event.project
    val cRev       = event.rev - 1
    event match {
      case SchemaEvent.SchemaCreated(_, _, value, _, _, _, _, _) =>
        schemas.create(id, projectRef, value)
      case SchemaEvent.SchemaUpdated(_, _, value, _, _, _, _, _) =>
        schemas.update(id, projectRef, cRev, value)
      case SchemaEvent.SchemaRefreshed(_, _, _, _, _, _, _)      =>
        // Refreshed events are not supported
        IO.unit
      case SchemaEvent.SchemaTagDeleted(_, _, _, _, _, _)        =>
        // Tags have been removed
        IO.unit
      case _: SchemaEvent.SchemaTagAdded                         =>
        // Tags have been removed
        IO.unit
      case _: SchemaEvent.SchemaDeprecated                       =>
        schemas.deprecate(id, projectRef, cRev)
      case _: SchemaEvent.SchemaUndeprecated                     =>
        schemas.undeprecate(id, projectRef, cRev)
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
      log: Clock[IO] => IO[SchemaLog],
      fetchContext: FetchContext,
      schemaImports: Clock[IO] => IO[SchemaImports]
  )(implicit jsonLdApi: JsonLdApi): IO[SchemaProcessor] = EventClock.init().flatMap { clock =>
    val rcr = ResolverContextResolution.never
    for {
      schemaLog <- log(clock)
      imports   <- schemaImports(clock)
      schemas    = SchemasImpl(schemaLog, fetchContext, imports, rcr)(jsonLdApi, FailingUUID)
    } yield new SchemaProcessor(schemas, clock)
  }

}
