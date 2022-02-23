package ch.epfl.bluebrain.nexus.delta.plugins.projectdeletion

import ch.epfl.bluebrain.nexus.delta.plugins.projectdeletion.model.{contexts, ProjectDeletionConfig}
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.{ContextValue, RemoteContextResolution}
import ch.epfl.bluebrain.nexus.delta.rdf.utils.JsonKeyOrdering
import ch.epfl.bluebrain.nexus.delta.sdk.model.Event.ProjectScopedEvent
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.ServiceAccount
import ch.epfl.bluebrain.nexus.delta.sdk.model.{BaseUri, Envelope}
import ch.epfl.bluebrain.nexus.delta.sdk.{PriorityRoute, ProjectReferenceFinder, Projects}
import ch.epfl.bluebrain.nexus.delta.sourcing.EventLog
import izumi.distage.model.definition.{Id, ModuleDef}
import monix.execution.Scheduler

class ProjectDeletionModule(priority: Int) extends ModuleDef {

  implicit private val classLoader: ClassLoader = getClass.getClassLoader

  many[RemoteContextResolution].addEffect {
    ContextValue.fromFile("contexts/project-deletion.json").map { ctx =>
      RemoteContextResolution.fixed(contexts.projectDeletion -> ctx)
    }
  }

  make[ProjectDeletionRoutes].from {
    (
        config: ProjectDeletionConfig,
        baseUri: BaseUri,
        s: Scheduler,
        cr: RemoteContextResolution @Id("aggregate"),
        ordering: JsonKeyOrdering
    ) => new ProjectDeletionRoutes(config)(baseUri, s, cr, ordering)
  }

  many[PriorityRoute].add { (route: ProjectDeletionRoutes) =>
    PriorityRoute(priority, route.routes, requiresStrictEntity = true)
  }

  make[DeleteProject].from {
    (projects: Projects, serviceAccount: ServiceAccount, prf: ProjectReferenceFinder @Id("aggregate")) =>
      DeleteProject(projects)(serviceAccount.subject, prf)
  }

  make[ProjectDeletion].fromEffect {
    (
        projects: Projects,
        config: ProjectDeletionConfig,
        eventLog: EventLog[Envelope[ProjectScopedEvent]],
        deleteProject: DeleteProject
    ) =>
      ProjectDeletion(projects, config, eventLog, deleteProject)
  }
}
