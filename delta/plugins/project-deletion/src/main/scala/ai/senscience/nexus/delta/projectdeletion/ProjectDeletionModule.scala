package ai.senscience.nexus.delta.projectdeletion

import ai.senscience.nexus.delta.projectdeletion.model.{contexts, ProjectDeletionConfig}
import cats.effect.{Clock, IO}
import ch.epfl.bluebrain.nexus.delta.kernel.utils.ClasspathResourceLoader
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.{ContextValue, RemoteContextResolution}
import ch.epfl.bluebrain.nexus.delta.rdf.utils.JsonKeyOrdering
import ch.epfl.bluebrain.nexus.delta.sdk.PriorityRoute
import ch.epfl.bluebrain.nexus.delta.sdk.model.BaseUri
import ch.epfl.bluebrain.nexus.delta.sdk.projects.{Projects, ProjectsStatistics}
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.Supervisor
import izumi.distage.model.definition.{Id, ModuleDef}

class ProjectDeletionModule(priority: Int) extends ModuleDef {

  implicit private val loader: ClasspathResourceLoader = ClasspathResourceLoader.withContext(getClass)

  many[RemoteContextResolution].addEffect {
    ContextValue.fromFile("contexts/project-deletion.json").map { ctx =>
      RemoteContextResolution.fixed(contexts.projectDeletion -> ctx)
    }
  }

  make[ProjectDeletionRoutes].from {
    (
        config: ProjectDeletionConfig,
        baseUri: BaseUri,
        cr: RemoteContextResolution @Id("aggregate"),
        ordering: JsonKeyOrdering
    ) => new ProjectDeletionRoutes(config)(baseUri, cr, ordering)
  }

  many[PriorityRoute].add { (route: ProjectDeletionRoutes) =>
    PriorityRoute(priority, route.routes, requiresStrictEntity = true)
  }

  make[ProjectDeletionRunner].fromEffect {
    (
        projects: Projects,
        config: ProjectDeletionConfig,
        projectStatistics: ProjectsStatistics,
        supervisor: Supervisor,
        clock: Clock[IO]
    ) => ProjectDeletionRunner.start(projects, config, projectStatistics, supervisor, clock)
  }
}
