package ch.epfl.bluebrain.nexus.ship.resolvers

import cats.effect.IO
import ch.epfl.bluebrain.nexus.delta.kernel.Logger
import ch.epfl.bluebrain.nexus.delta.kernel.utils.UUIDF
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.api.JsonLdApi
import ch.epfl.bluebrain.nexus.delta.sdk.identities.model.Caller
import ch.epfl.bluebrain.nexus.delta.sdk.projects.FetchContext
import ch.epfl.bluebrain.nexus.delta.sdk.resolvers.{ResolverContextResolution, Resolvers, ResolversImpl}
import ch.epfl.bluebrain.nexus.delta.sdk.resolvers.model.IdentityResolution._
import ch.epfl.bluebrain.nexus.delta.sdk.resolvers.model.ResolverEvent._
import ch.epfl.bluebrain.nexus.delta.sdk.resolvers.model.ResolverRejection.IncorrectRev
import ch.epfl.bluebrain.nexus.delta.sdk.resolvers.model.ResolverValue.{CrossProjectValue, InProjectValue}
import ch.epfl.bluebrain.nexus.delta.sdk.resolvers.model.{ResolverEvent, ResolverValue}
import ch.epfl.bluebrain.nexus.delta.sourcing.Transactors
import ch.epfl.bluebrain.nexus.delta.sourcing.config.EventLogConfig
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.Subject
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{EntityType, Identity}
import ch.epfl.bluebrain.nexus.ship.resolvers.ResolverProcessor.logger
import ch.epfl.bluebrain.nexus.ship.{EventClock, EventProcessor, FailingUUID}
import io.circe.Decoder

class ResolverProcessor private (resolvers: Resolvers, clock: EventClock) extends EventProcessor[ResolverEvent] {
  override def resourceType: EntityType = Resolvers.entityType

  override def decoder: Decoder[ResolverEvent] = ResolverEvent.serializer.codec

  override def evaluate(event: ResolverEvent): IO[Unit] = {
    for {
      _ <- clock.setInstant(event.instant)
      _ <- evaluateInternal(event)
    } yield ()
  }

  private def evaluateInternal(event: ResolverEvent): IO[Unit] = {
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
  }.recoverWith { case i: IncorrectRev =>
    logger.warn(i)("An incorrect revision as been provided")
  }.void

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
      xas: Transactors
  )(implicit api: JsonLdApi): IO[ResolverProcessor] =
    EventClock.init().map { clock =>
      implicit val uuidF: UUIDF = FailingUUID
      val resolvers             = ResolversImpl(
        fetchContext,
        // We rely on the parsed values and not on the original value
        ResolverContextResolution.never,
        config,
        xas,
        clock
      )
      new ResolverProcessor(resolvers, clock)
    }
}
