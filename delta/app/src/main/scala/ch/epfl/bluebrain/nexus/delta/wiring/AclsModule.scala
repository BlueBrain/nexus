package ch.epfl.bluebrain.nexus.delta.wiring

import akka.actor.typed.ActorSystem
import cats.effect.Clock
import ch.epfl.bluebrain.nexus.delta.Main.pluginsMaxPriority
import ch.epfl.bluebrain.nexus.delta.config.AppConfig
import ch.epfl.bluebrain.nexus.delta.kernel.utils.UUIDF
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.contexts
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.{ContextValue, RemoteContextResolution}
import ch.epfl.bluebrain.nexus.delta.rdf.utils.JsonKeyOrdering
import ch.epfl.bluebrain.nexus.delta.routes.AclsRoutes
import ch.epfl.bluebrain.nexus.delta.sdk._
import ch.epfl.bluebrain.nexus.delta.sdk.eventlog.EventLogUtils.databaseEventLog
import ch.epfl.bluebrain.nexus.delta.sdk.model.acls.AclEvent
import ch.epfl.bluebrain.nexus.delta.sdk.model.{BaseUri, Envelope, MetadataContextValue}
import ch.epfl.bluebrain.nexus.delta.service.acls.AclsImpl.{AclsAggregate, AclsCache}
import ch.epfl.bluebrain.nexus.delta.service.acls.{AclEventExchange, AclsDeletion, AclsImpl}
import ch.epfl.bluebrain.nexus.delta.sourcing.{DatabaseCleanup, EventLog}
import izumi.distage.model.definition.{Id, ModuleDef}
import monix.bio.UIO
import monix.execution.Scheduler

/**
  * Acls module wiring config.
  */
// $COVERAGE-OFF$
object AclsModule extends ModuleDef {
  implicit private val classLoader: ClassLoader = getClass.getClassLoader

  make[EventLog[Envelope[AclEvent]]].fromEffect { databaseEventLog[AclEvent](_, _) }

  make[AclsAggregate].fromEffect {
    (config: AppConfig, permissions: Permissions, realms: Realms, as: ActorSystem[Nothing], clock: Clock[UIO]) =>
      AclsImpl.aggregate(permissions, realms, config.acls.aggregate)(as, clock)
  }

  make[AclsCache].from { (config: AppConfig, as: ActorSystem[Nothing]) =>
    AclsImpl.cache(config.acls)(as)
  }

  many[ResourcesDeletion].add { (cache: AclsCache, agg: AclsAggregate, dbCleanup: DatabaseCleanup) =>
    AclsDeletion(cache, agg, dbCleanup)
  }

  make[Acls].fromEffect {
    (
        agg: AclsAggregate,
        cache: AclsCache,
        cfg: AppConfig,
        eventLog: EventLog[Envelope[AclEvent]],
        as: ActorSystem[Nothing],
        uuidF: UUIDF,
        scheduler: Scheduler,
        permissions: Permissions
    ) =>
      AclsImpl(agg, cache, cfg.acls, permissions, eventLog)(as, scheduler, uuidF)
  }

  make[AclsRoutes].from {
    (
        identities: Identities,
        acls: Acls,
        baseUri: BaseUri,
        s: Scheduler,
        cr: RemoteContextResolution @Id("aggregate"),
        ordering: JsonKeyOrdering
    ) =>
      new AclsRoutes(identities, acls)(baseUri, s, cr, ordering)
  }

  many[MetadataContextValue].addEffect(MetadataContextValue.fromFile("contexts/acls-metadata.json"))

  many[RemoteContextResolution].addEffect(
    for {
      aclsCtx     <- ContextValue.fromFile("contexts/acls.json")
      aclsMetaCtx <- ContextValue.fromFile("contexts/acls-metadata.json")
    } yield RemoteContextResolution.fixed(contexts.acls -> aclsCtx, contexts.aclsMetadata -> aclsMetaCtx)
  )

  many[PriorityRoute].add { (route: AclsRoutes) => PriorityRoute(pluginsMaxPriority + 5, route.routes) }

  make[AclEventExchange]
  many[EventExchange].ref[AclEventExchange]
}
// $COVERAGE-ON$
