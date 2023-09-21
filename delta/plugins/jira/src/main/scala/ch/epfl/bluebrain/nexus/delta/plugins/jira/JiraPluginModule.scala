package ch.epfl.bluebrain.nexus.delta.plugins.jira

import cats.effect.{Clock, IO}
import ch.epfl.bluebrain.nexus.delta.plugins.jira.config.JiraConfig
import ch.epfl.bluebrain.nexus.delta.plugins.jira.routes.JiraRoutes
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.RemoteContextResolution
import ch.epfl.bluebrain.nexus.delta.rdf.utils.JsonKeyOrdering
import ch.epfl.bluebrain.nexus.delta.sdk._
import ch.epfl.bluebrain.nexus.delta.sdk.acls.AclCheck
import ch.epfl.bluebrain.nexus.delta.sdk.identities.Identities
import ch.epfl.bluebrain.nexus.delta.sdk.model._
import ch.epfl.bluebrain.nexus.delta.sourcing.Transactors
import izumi.distage.model.definition.{Id, ModuleDef}

/**
  * Jira plugin wiring.
  */
class JiraPluginModule(priority: Int) extends ModuleDef {

  make[JiraConfig].from { JiraConfig.load(_) }

  make[JiraClient].fromEffect { (xas: Transactors, jiraConfig: JiraConfig, clock: Clock[IO]) =>
    JiraClient(TokenStore(xas)(clock), jiraConfig)
  }

  make[JiraRoutes].from {
    (
        identities: Identities,
        aclCheck: AclCheck,
        jiraClient: JiraClient,
        baseUri: BaseUri,
        cr: RemoteContextResolution @Id("aggregate"),
        ordering: JsonKeyOrdering
    ) =>
      new JiraRoutes(
        identities,
        aclCheck,
        jiraClient
      )(
        baseUri,
        cr,
        ordering
      )
  }

  many[PriorityRoute].add { (route: JiraRoutes) =>
    PriorityRoute(priority, route.routes, requiresStrictEntity = true)
  }

}
