package ch.epfl.bluebrain.nexus.delta.plugins.jira

import ch.epfl.bluebrain.nexus.delta.plugins.jira.config.JiraConfig
import ch.epfl.bluebrain.nexus.delta.plugins.jira.routes.JiraRoutes
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.RemoteContextResolution
import ch.epfl.bluebrain.nexus.delta.rdf.utils.JsonKeyOrdering
import ch.epfl.bluebrain.nexus.delta.sdk._
import ch.epfl.bluebrain.nexus.delta.sdk.cache.KeyValueStore
import ch.epfl.bluebrain.nexus.delta.sdk.model._
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Identity.User
import izumi.distage.model.definition.{Id, ModuleDef}
import monix.execution.Scheduler

/**
  * Graph analytics plugin wiring.
  */
class JiraPluginModule(priority: Int) extends ModuleDef {

  make[JiraConfig].from { JiraConfig.load(_) }

  make[JiraClient].fromEffect { (jiraConfig: JiraConfig) =>
    //TODO persist tokens between restarts
    KeyValueStore.localLRU[User, OAuthToken](500).flatMap { cache =>
      JiraClient(cache, jiraConfig)
    }
  }

  make[JiraRoutes].from {
    (
        identities: Identities,
        acls: Acls,
        jiraClient: JiraClient,
        baseUri: BaseUri,
        s: Scheduler,
        cr: RemoteContextResolution @Id("aggregate"),
        ordering: JsonKeyOrdering
    ) =>
      new JiraRoutes(
        identities,
        acls,
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
