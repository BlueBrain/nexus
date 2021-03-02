package ch.epfl.bluebrain.nexus.delta.wiring

import akka.actor.typed.ActorSystem
import cats.effect.Clock
import ch.epfl.bluebrain.nexus.delta.config.AppConfig
import ch.epfl.bluebrain.nexus.delta.kernel.utils.UUIDF
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.RemoteContextResolution
import ch.epfl.bluebrain.nexus.delta.routes.ResourcesRoutes
import ch.epfl.bluebrain.nexus.delta.sdk._
import ch.epfl.bluebrain.nexus.delta.sdk.eventlog.EventLogUtils.databaseEventLog
import ch.epfl.bluebrain.nexus.delta.sdk.model.Envelope
import ch.epfl.bluebrain.nexus.delta.sdk.model.resolvers.ResolverContextResolution
import ch.epfl.bluebrain.nexus.delta.sdk.model.resources.ResourceEvent
import ch.epfl.bluebrain.nexus.delta.service.resources.{ResourceReferenceExchange, ResourcesImpl}
import ch.epfl.bluebrain.nexus.delta.sourcing.EventLog
import izumi.distage.model.definition.ModuleDef
import monix.bio.UIO

/**
  * Resources wiring
  */
object ResourcesModule extends ModuleDef {

  make[EventLog[Envelope[ResourceEvent]]].fromEffect { databaseEventLog[ResourceEvent](_, _) }

  make[Resources].fromEffect {
    (
        config: AppConfig,
        eventLog: EventLog[Envelope[ResourceEvent]],
        acls: Acls,
        organizations: Organizations,
        projects: Projects,
        resolvers: Resolvers,
        schemas: Schemas,
        resolverContextResolution: ResolverContextResolution,
        as: ActorSystem[Nothing],
        clock: Clock[UIO],
        uuidF: UUIDF
    ) =>
      ResourcesImpl(
        organizations,
        projects,
        ResourceResolution.schemaResource(acls, resolvers, schemas),
        resolverContextResolution,
        config.resources.aggregate,
        eventLog
      )(uuidF, as, clock)
  }

  make[ResolverContextResolution].from {
    (acls: Acls, resolvers: Resolvers, resources: Resources, rcr: RemoteContextResolution) =>
      ResolverContextResolution(acls, resolvers, resources, rcr)
  }

  make[ResourcesRoutes]

  make[ResourceReferenceExchange]
  many[ReferenceExchange].ref[ResourceReferenceExchange]
}
