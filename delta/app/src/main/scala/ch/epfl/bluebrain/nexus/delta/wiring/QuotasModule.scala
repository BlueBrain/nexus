package ch.epfl.bluebrain.nexus.delta.wiring

import cats.effect.unsafe.IORuntime
import ch.epfl.bluebrain.nexus.delta.Main.pluginsMaxPriority
import ch.epfl.bluebrain.nexus.delta.config.AppConfig
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.contexts
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.{ContextValue, RemoteContextResolution}
import ch.epfl.bluebrain.nexus.delta.rdf.utils.JsonKeyOrdering
import ch.epfl.bluebrain.nexus.delta.routes.QuotasRoutes
import ch.epfl.bluebrain.nexus.delta.sdk._
import ch.epfl.bluebrain.nexus.delta.sdk.acls.AclCheck
import ch.epfl.bluebrain.nexus.delta.sdk.identities.Identities
import ch.epfl.bluebrain.nexus.delta.sdk.model.BaseUri
import ch.epfl.bluebrain.nexus.delta.sdk.projects.ProjectsStatistics
import ch.epfl.bluebrain.nexus.delta.sdk.quotas.{Quotas, QuotasImpl}
import izumi.distage.model.definition.{Id, ModuleDef}

/**
  * Quotas module wiring config.
  */
// $COVERAGE-OFF$
object QuotasModule extends ModuleDef {

  make[Quotas].from { (projectsStatistics: ProjectsStatistics, cfg: AppConfig) =>
    new QuotasImpl(projectsStatistics)(cfg.quotas, cfg.serviceAccount)
  }

  many[RemoteContextResolution].addEffect(ContextValue.fromFile("contexts/quotas.json").map { ctx =>
    RemoteContextResolution.fixed(contexts.quotas -> ctx)
  })

  make[QuotasRoutes].from {
    (
        identities: Identities,
        aclCheck: AclCheck,
        quotas: Quotas,
        baseUri: BaseUri,
        cr: RemoteContextResolution @Id("aggregate"),
        ordering: JsonKeyOrdering,
        runtime: IORuntime
    ) => new QuotasRoutes(identities, aclCheck, quotas)(baseUri, cr, ordering, runtime)
  }

  many[PriorityRoute].add { (route: QuotasRoutes) =>
    PriorityRoute(pluginsMaxPriority + 10, route.routes, requiresStrictEntity = true)
  }

}
// $COVERAGE-ON$
