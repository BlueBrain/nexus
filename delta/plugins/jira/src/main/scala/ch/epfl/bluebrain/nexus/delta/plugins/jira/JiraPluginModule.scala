package ch.epfl.bluebrain.nexus.delta.plugins.jira

import akka.actor.typed.ActorSystem
import cats.effect.Clock
import ch.epfl.bluebrain.nexus.delta.plugins.jira.config.JiraConfig
import ch.epfl.bluebrain.nexus.delta.plugins.jira.routes.JiraRoutes
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.RemoteContextResolution
import ch.epfl.bluebrain.nexus.delta.rdf.utils.JsonKeyOrdering
import ch.epfl.bluebrain.nexus.delta.sdk._
import ch.epfl.bluebrain.nexus.delta.sdk.acls.AclCheck
import ch.epfl.bluebrain.nexus.delta.sdk.identities.Identities
import ch.epfl.bluebrain.nexus.delta.sdk.model._
import ch.epfl.bluebrain.nexus.delta.sourcing.config.DatabaseConfigOld
import izumi.distage.model.definition.{Id, ModuleDef}
import monix.bio.UIO
import monix.execution.Scheduler

/**
  * Jira plugin wiring.
  */
class JiraPluginModule(priority: Int) extends ModuleDef {

  make[JiraConfig].from { JiraConfig.load(_) }

  make[JiraClient].fromEffect {
    (databaseConfig: DatabaseConfigOld, jiraConfig: JiraConfig, as: ActorSystem[Nothing], clock: Clock[UIO]) =>
      TokenStore(databaseConfig, as, clock).flatMap { cache =>
        JiraClient(cache, jiraConfig)
      }
  }

  make[JiraRoutes].from {
    (
        identities: Identities,
        aclCheck: AclCheck,
        jiraClient: JiraClient,
        baseUri: BaseUri,
        s: Scheduler,
        cr: RemoteContextResolution @Id("aggregate"),
        ordering: JsonKeyOrdering
    ) =>
      new JiraRoutes(
        identities,
        aclCheck,
        jiraClient
      )(
        baseUri,
        s,
        cr,
        ordering
      )
  }

  many[PriorityRoute].add { (route: JiraRoutes) =>
    PriorityRoute(priority, route.routes, requiresStrictEntity = true)
  }

}
