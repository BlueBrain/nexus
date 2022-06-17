package ch.epfl.bluebrain.nexus.delta.wiring

import ch.epfl.bluebrain.nexus.delta.Main.pluginsMaxPriority
import ch.epfl.bluebrain.nexus.delta.config.AppConfig
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.contexts
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.{ContextValue, RemoteContextResolution}
import ch.epfl.bluebrain.nexus.delta.rdf.utils.JsonKeyOrdering
import ch.epfl.bluebrain.nexus.delta.routes.QuotasRoutes
import ch.epfl.bluebrain.nexus.delta.sdk._
import ch.epfl.bluebrain.nexus.delta.sdk.acls.AclCheck
import ch.epfl.bluebrain.nexus.delta.sdk.model.BaseUri
import ch.epfl.bluebrain.nexus.delta.service.quotas.QuotasImpl
import izumi.distage.model.definition.{Id, ModuleDef}
import monix.execution.Scheduler

/**
  * Quotas module wiring config.
  */
// $COVERAGE-OFF$
object QuotasModule extends ModuleDef {
  implicit private val classLoader = getClass.getClassLoader

  make[Quotas].from { (projects: Projects, projectsCounts: ProjectsCounts, cfg: AppConfig) =>
    new QuotasImpl(projects, projectsCounts)(cfg.projects.quotas, cfg.serviceAccount)
  }

  many[RemoteContextResolution].addEffect(ContextValue.fromFile("contexts/quotas.json").map { ctx =>
    RemoteContextResolution.fixed(contexts.quotas -> ctx)
  })

  make[QuotasRoutes].from {
    (
        identities: Identities,
        aclCheck: AclCheck,
        projects: Projects,
        quotas: Quotas,
        s: Scheduler,
        baseUri: BaseUri,
        cr: RemoteContextResolution @Id("aggregate"),
        ordering: JsonKeyOrdering
    ) => new QuotasRoutes(identities, aclCheck, projects, quotas)(baseUri, s, cr, ordering)

  }

  many[PriorityRoute].add { (route: QuotasRoutes) =>
    PriorityRoute(pluginsMaxPriority + 10, route.routes, requiresStrictEntity = true)
  }

}
// $COVERAGE-ON$
