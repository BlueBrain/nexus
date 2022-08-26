package ch.epfl.bluebrain.nexus.delta.plugins.jira

import cats.effect.Clock
import ch.epfl.bluebrain.nexus.delta.kernel.database.{DatabaseConfig, Transactors}
import ch.epfl.bluebrain.nexus.delta.plugins.jira.config.JiraConfig
import ch.epfl.bluebrain.nexus.delta.plugins.jira.routes.JiraRoutes
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.RemoteContextResolution
import ch.epfl.bluebrain.nexus.delta.rdf.utils.JsonKeyOrdering
import ch.epfl.bluebrain.nexus.delta.sdk._
import ch.epfl.bluebrain.nexus.delta.sdk.acls.AclCheck
import ch.epfl.bluebrain.nexus.delta.sdk.identities.Identities
import ch.epfl.bluebrain.nexus.delta.sdk.model._
import izumi.distage.model.definition.{Id, ModuleDef}
import monix.bio.UIO
import monix.execution.Scheduler

/**
  * Jira plugin wiring.
  */
class JiraPluginModule(priority: Int) extends ModuleDef {

  make[JiraConfig].from { JiraConfig.load(_) }

  make[JiraClient].fromEffect {
    (xas: Transactors, databaseConfig: DatabaseConfig, jiraConfig: JiraConfig, clock: Clock[UIO]) =>
      TokenStore(xas, databaseConfig.tablesAutocreate)(clock).flatMap { cache =>
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
