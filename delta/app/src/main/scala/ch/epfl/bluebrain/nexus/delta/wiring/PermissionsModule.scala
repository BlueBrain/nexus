package ch.epfl.bluebrain.nexus.delta.wiring

import akka.actor.typed.ActorSystem
import cats.effect.Clock
import ch.epfl.bluebrain.nexus.delta.Main.pluginsMaxPriority
import ch.epfl.bluebrain.nexus.delta.config.AppConfig
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.contexts
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.{ContextValue, RemoteContextResolution}
import ch.epfl.bluebrain.nexus.delta.rdf.utils.JsonKeyOrdering
import ch.epfl.bluebrain.nexus.delta.routes.PermissionsRoutes
import ch.epfl.bluebrain.nexus.delta.sdk.eventlog.EventLogUtils.databaseEventLog
import ch.epfl.bluebrain.nexus.delta.sdk.model.permissions.PermissionsEvent
import ch.epfl.bluebrain.nexus.delta.sdk.model.{BaseUri, Envelope, MetadataContextValue}
import ch.epfl.bluebrain.nexus.delta.sdk._
import ch.epfl.bluebrain.nexus.delta.service.permissions.{PermissionsEventExchange, PermissionsImpl}
import ch.epfl.bluebrain.nexus.delta.sourcing.EventLog
import izumi.distage.model.definition.{Id, ModuleDef}
import monix.bio.UIO
import monix.execution.Scheduler

/**
  * Permissions module wiring config.
  */
// $COVERAGE-OFF$
object PermissionsModule extends ModuleDef {
  implicit private val classLoader = getClass.getClassLoader

  make[EventLog[Envelope[PermissionsEvent]]].fromEffect { databaseEventLog[PermissionsEvent](_, _) }

  make[Permissions].fromEffect {
    (cfg: AppConfig, log: EventLog[Envelope[PermissionsEvent]], as: ActorSystem[Nothing], clock: Clock[UIO]) =>
      PermissionsImpl(
        cfg.permissions.minimum,
        cfg.permissions.aggregate,
        log
      )(as, clock)
  }

  make[PermissionsRoutes].from {
    (
        identities: Identities,
        permissions: Permissions,
        acls: Acls,
        baseUri: BaseUri,
        s: Scheduler,
        cr: RemoteContextResolution @Id("aggregate"),
        ordering: JsonKeyOrdering
    ) => new PermissionsRoutes(identities, permissions, acls)(baseUri, s, cr, ordering)
  }

  many[MetadataContextValue].addEffect(MetadataContextValue.fromFile("contexts/permissions-metadata.json"))

  many[RemoteContextResolution].addEffect(
    for {
      permissionsCtx     <- ContextValue.fromFile("contexts/permissions.json")
      permissionsMetaCtx <- ContextValue.fromFile("contexts/permissions-metadata.json")
    } yield RemoteContextResolution.fixed(
      contexts.permissions         -> permissionsCtx,
      contexts.permissionsMetadata -> permissionsMetaCtx
    )
  )

  many[PriorityRoute].add { (route: PermissionsRoutes) => PriorityRoute(pluginsMaxPriority + 3, route.routes) }

  make[PermissionsEventExchange]
  many[EventExchange].ref[PermissionsEventExchange]
}
// $COVERAGE-ON$
