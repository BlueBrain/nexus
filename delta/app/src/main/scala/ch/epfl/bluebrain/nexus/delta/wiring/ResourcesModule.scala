package ch.epfl.bluebrain.nexus.delta.wiring

import akka.actor.typed.ActorSystem
import cats.effect.Clock
import ch.epfl.bluebrain.nexus.delta.config.AppConfig
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.RemoteContextResolution
import ch.epfl.bluebrain.nexus.delta.rdf.utils.JsonKeyOrdering
import ch.epfl.bluebrain.nexus.delta.routes.ResourcesRoutes
import ch.epfl.bluebrain.nexus.delta.sdk.model.resources.ResourceEvent
import ch.epfl.bluebrain.nexus.delta.sdk.model.{BaseUri, Envelope, ResourceRef}
import ch.epfl.bluebrain.nexus.delta.sdk.utils.UUIDF
import ch.epfl.bluebrain.nexus.delta.sdk._
import ch.epfl.bluebrain.nexus.delta.service.resources.ResourcesImpl
import ch.epfl.bluebrain.nexus.sourcing.EventLog
import izumi.distage.model.definition.ModuleDef
import monix.bio.UIO
import monix.execution.Scheduler

/**
  * Resources wiring
  */
object ResourcesModule extends ModuleDef {

  // TODO: Change this when we have schemas implemented
  private val fetchSchema: ResourceRef => UIO[Option[SchemaResource]] = _ => UIO.pure(None)

  make[EventLog[Envelope[ResourceEvent]]].fromEffect { databaseEventLog[ResourceEvent](_, _) }

  make[Resources].fromEffect {
    (
        config: AppConfig,
        eventLog: EventLog[Envelope[ResourceEvent]],
        organizations: Organizations,
        projects: Projects,
        cr: RemoteContextResolution,
        as: ActorSystem[Nothing]
    ) =>
      ResourcesImpl(
        organizations,
        projects,
        fetchSchema,
        config.resources.aggregate,
        eventLog
      )(cr, UUIDF.random, as, Clock[UIO])
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
