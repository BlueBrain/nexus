package ch.epfl.bluebrain.nexus.ship.schemas

import cats.effect.IO
import ch.epfl.bluebrain.nexus.delta.kernel.Logger
import ch.epfl.bluebrain.nexus.delta.sdk.identities.model.Caller
import ch.epfl.bluebrain.nexus.delta.sdk.projects.FetchContext
import ch.epfl.bluebrain.nexus.delta.sdk.resolvers.ResolverContextResolution
import ch.epfl.bluebrain.nexus.delta.sdk.schemas.Schemas.SchemaLog
import ch.epfl.bluebrain.nexus.delta.sdk.schemas.model.SchemaEvent
import ch.epfl.bluebrain.nexus.delta.sdk.schemas.model.SchemaEvent._
import ch.epfl.bluebrain.nexus.delta.sdk.schemas.model.SchemaRejection.{IncorrectRev, InvalidSchema, ResourceAlreadyExists}
import ch.epfl.bluebrain.nexus.delta.sdk.schemas.{SchemaImports, Schemas, SchemasImpl}
import ch.epfl.bluebrain.nexus.delta.sourcing.model.EntityType
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.Subject
import ch.epfl.bluebrain.nexus.ship._
import ch.epfl.bluebrain.nexus.ship.resources.SourcePatcher
import ch.epfl.bluebrain.nexus.ship.schemas.SchemaProcessor.logger
import io.circe.Decoder

class SchemaProcessor private (
    schemas: Schemas,
    projectMapper: ProjectMapper,
    sourcePatcher: SourcePatcher,
    clock: EventClock
) extends EventProcessor[SchemaEvent] {

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
    val project             = projectMapper.map(event.project)

    event match {
      case e: SchemaCreated      =>
        sourcePatcher(e.source).flatMap { patched =>
          schemas.create(e.id, project, patched)
        }
      case e: SchemaUpdated      =>
        sourcePatcher(e.source).flatMap { patched =>
          schemas.update(e.id, project, cRev, patched)
        }
      case e: SchemaRefreshed    => schemas.refresh(e.id, project)
      case e: SchemaTagAdded     => schemas.tag(e.id, project, e.tag, e.targetRev, cRev)
      case e: SchemaTagDeleted   => schemas.deleteTag(e.id, project, e.tag, cRev)
      case e: SchemaDeprecated   => schemas.deprecate(e.id, project, cRev)
      case e: SchemaUndeprecated => schemas.undeprecate(e.id, project, cRev)
    }
  }.redeemWith(
    {
      case a: ResourceAlreadyExists => logger.warn(a)("The schema already exists").as(ImportStatus.Dropped)
      case i: IncorrectRev          =>
        logger
          .warn(i)(s"An incorrect revision has been provided for '${event.id}' in project '${event.project}'")
          .as(ImportStatus.Dropped)
      case i: InvalidSchema         =>
        val message = s"The schema '${i.id}' is invalid. Report: ${i.report}"
        logger.error(message) >> IO.raiseError(i)
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
      rcr: ResolverContextResolution,
      projectMapper: ProjectMapper,
      sourcePatcher: SourcePatcher,
      clock: EventClock
  ): SchemaProcessor = {
    val schemas = SchemasImpl(log, fetchContext, schemaImports, rcr)(FailingUUID)
    new SchemaProcessor(schemas, projectMapper, sourcePatcher, clock)
  }

}
