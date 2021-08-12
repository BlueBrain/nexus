package ch.epfl.bluebrain.nexus.delta.sdk

import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.ProjectCountsCollection.ProjectCount
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.{ProjectCountsCollection, ProjectRef}
import monix.bio.UIO

class ProjectsCountsDummy(collection: ProjectCountsCollection) extends ProjectsCounts {
  override def get(): UIO[ProjectCountsCollection] = UIO.pure(collection)

  override def get(project: ProjectRef): UIO[Option[ProjectCount]] = get().map(_.get(project))
}

object ProjectsCountsDummy {
  final def apply(tuples: (ProjectRef, ProjectCount)*): ProjectsCounts =
    new ProjectsCountsDummy(ProjectCountsCollection(Map(tuples: _*)))
}
