package ch.epfl.bluebrain.nexus.ship.resolvers

import cats.effect.IO
import ch.epfl.bluebrain.nexus.delta.kernel.Logger
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.api.JsonLdApi
import ch.epfl.bluebrain.nexus.delta.sdk.identities.model.Caller
import ch.epfl.bluebrain.nexus.delta.sdk.projects.FetchContext
import ch.epfl.bluebrain.nexus.delta.sdk.resolvers.Resolvers
import ch.epfl.bluebrain.nexus.delta.sdk.resolvers.model.IdentityResolution._
import ch.epfl.bluebrain.nexus.delta.sdk.resolvers.model.ResolverEvent._
import ch.epfl.bluebrain.nexus.delta.sdk.resolvers.model.ResolverRejection.{IncorrectRev, ResourceAlreadyExists}
import ch.epfl.bluebrain.nexus.delta.sdk.resolvers.model.ResolverValue.{CrossProjectValue, InProjectValue}
import ch.epfl.bluebrain.nexus.delta.sdk.resolvers.model.{ResolverEvent, ResolverValue}
import ch.epfl.bluebrain.nexus.delta.sourcing.Transactors
import ch.epfl.bluebrain.nexus.delta.sourcing.config.EventLogConfig
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.Subject
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{EntityType, Identity}
import ch.epfl.bluebrain.nexus.ship.resolvers.ResolverProcessor.logger
import ch.epfl.bluebrain.nexus.ship.{EventClock, EventProcessor, ImportStatus}
import io.circe.Decoder

class ResolverProcessor private (resolvers: Resolvers, clock: EventClock) extends EventProcessor[ResolverEvent] {
  override def resourceType: EntityType = Resolvers.entityType

  override def decoder: Decoder[ResolverEvent] = ResolverEvent.serializer.codec

  override def evaluate(event: ResolverEvent): IO[ImportStatus] = {
    for {
      _      <- clock.setInstant(event.instant)
      result <- evaluateInternal(event)
    } yield result
  }

  private def evaluateInternal(event: ResolverEvent): IO[ImportStatus] = {
    val id                  = event.id
    implicit val s: Subject = event.subject
    val projectRef          = event.project
    val cRev                = event.rev - 1
    event match {
      case ResolverCreated(_, _, value, _, _, _, _) =>
        implicit val caller: Caller = Caller(s, identities(value))
        resolvers.create(id, projectRef, value)
      case ResolverUpdated(_, _, value, _, _, _, _) =>
        implicit val caller: Caller = Caller(s, identities(value))
        resolvers.update(id, projectRef, cRev, value)
      case _: ResolverTagAdded                      =>
        // Tags have been removed
        IO.unit
      case _: ResolverDeprecated                    =>
        resolvers.deprecate(id, projectRef, cRev)
    }
  }.redeemWith(
    {
      case a: ResourceAlreadyExists => logger.warn(a)("The resolver already exists").as(ImportStatus.Dropped)
      case i: IncorrectRev          => logger.warn(i)("An incorrect revision has been provided").as(ImportStatus.Dropped)
      case other                    => IO.raiseError(other)
    },
    _ => IO.pure(ImportStatus.Success)
  )

  private def identities(value: ResolverValue) =
    value match {
      case _: InProjectValue               => Set.empty[Identity]
      case crossProject: CrossProjectValue =>
        crossProject.identityResolution match {
          case UseCurrentCaller               => Set.empty[Identity]
          case ProvidedIdentities(identities) => identities
        }
    }
}

object ResolverProcessor {

  private val logger = Logger[ResolverProcessor]

  def apply(
      fetchContext: FetchContext,
      config: EventLogConfig,
      clock: EventClock,
      xas: Transactors
  )(implicit api: JsonLdApi): ResolverProcessor = {
    val resolvers = ResolverWiring.resolvers(fetchContext, config, clock, xas)
    new ResolverProcessor(resolvers, clock)
  }
}
