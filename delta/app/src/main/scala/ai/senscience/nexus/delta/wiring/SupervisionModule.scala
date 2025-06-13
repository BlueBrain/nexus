package ai.senscience.nexus.delta.wiring

import ai.senscience.nexus.delta.Main.pluginsMaxPriority
import ai.senscience.nexus.delta.routes.SupervisionRoutes
import ch.epfl.bluebrain.nexus.delta.kernel.utils.ClasspathResourceLoader
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.contexts
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.{ContextValue, RemoteContextResolution}
import ch.epfl.bluebrain.nexus.delta.rdf.utils.JsonKeyOrdering
import ch.epfl.bluebrain.nexus.delta.sdk.PriorityRoute
import ch.epfl.bluebrain.nexus.delta.sdk.acls.AclCheck
import ch.epfl.bluebrain.nexus.delta.sdk.identities.Identities
import ch.epfl.bluebrain.nexus.delta.sdk.model.BaseUri
import ch.epfl.bluebrain.nexus.delta.sdk.projects.{ProjectHealer, ProjectsHealth}
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.{ProjectActivitySignals, Supervisor}
import izumi.distage.model.definition.{Id, ModuleDef}

/**
  * Supervision module wiring config.
  */
// $COVERAGE-OFF$
object SupervisionModule extends ModuleDef {

  implicit private val loader: ClasspathResourceLoader = ClasspathResourceLoader.withContext(getClass)

  make[SupervisionRoutes].from {
    (
        identities: Identities,
        aclCheck: AclCheck,
        supervisor: Supervisor,
        baseUri: BaseUri,
        rc: RemoteContextResolution @Id("aggregate"),
        jo: JsonKeyOrdering,
        projectsHealth: ProjectsHealth,
        projectHealer: ProjectHealer,
        projectActivitySignals: ProjectActivitySignals
    ) =>
      new SupervisionRoutes(
        identities,
        aclCheck,
        supervisor.getRunningProjections(),
        projectsHealth,
        projectHealer,
        projectActivitySignals
      )(baseUri, rc, jo)
  }

  many[RemoteContextResolution].addEffect(
    for {
      supervisionCtx <- ContextValue.fromFile("contexts/supervision.json")
    } yield RemoteContextResolution.fixed(
      contexts.supervision -> supervisionCtx
    )
  )

  many[PriorityRoute].add { (route: SupervisionRoutes) =>
    PriorityRoute(pluginsMaxPriority + 12, route.routes, requiresStrictEntity = true)
  }
}
// $COVERAGE-ON$
