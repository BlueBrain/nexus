package ch.epfl.bluebrain.nexus.delta.wiring

import akka.actor.typed.ActorSystem
import cats.effect.Clock
import ch.epfl.bluebrain.nexus.delta.config.AppConfig
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.RemoteContextResolution
import ch.epfl.bluebrain.nexus.delta.rdf.utils.JsonKeyOrdering
import ch.epfl.bluebrain.nexus.delta.routes.ResourcesRoutes
import ch.epfl.bluebrain.nexus.delta.sdk._
import ch.epfl.bluebrain.nexus.delta.sdk.eventlog.EventLogUtils.databaseEventLog
import ch.epfl.bluebrain.nexus.delta.sdk.model.resolvers.ResolverContextResolution
import ch.epfl.bluebrain.nexus.delta.sdk.model.resources.ResourceEvent
import ch.epfl.bluebrain.nexus.delta.sdk.model.{BaseUri, Envelope}
import ch.epfl.bluebrain.nexus.delta.sdk.utils.UUIDF
import ch.epfl.bluebrain.nexus.delta.service.resources.ResourcesImpl
import ch.epfl.bluebrain.nexus.sourcing.EventLog
import izumi.distage.model.definition.ModuleDef
import monix.bio.UIO
import monix.execution.Scheduler

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
        as: ActorSystem[Nothing]
    ) =>
      ResourcesImpl(
        organizations,
        projects,
        ResourceResolution.schemaResource(acls, resolvers, schemas),
        resolverContextResolution,
        config.resources.aggregate,
        eventLog
      )(UUIDF.random, as, Clock[UIO])
  }

  make[ResolverContextResolution].from {
    (acls: Acls, resolvers: Resolvers, resources: Resources, rcr: RemoteContextResolution) =>
      ResolverContextResolution(acls, resolvers, resources, rcr)
  }

  make[ResourcesRoutes].from {
    (
        identities: Identities,
        acls: Acls,
        organizations: Organizations,
        projects: Projects,
        resources: Resources,
        baseUri: BaseUri,
        s: Scheduler,
        cr: RemoteContextResolution,
        ordering: JsonKeyOrdering
    ) =>
      new ResourcesRoutes(identities, acls, organizations, projects, resources)(baseUri, s, cr, ordering)
  }

}
