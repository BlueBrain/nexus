package ch.epfl.bluebrain.nexus.delta.sdk

import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.ProjectCountsCollection.ProjectCount
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.{ProjectCountsCollection, ProjectRef}
import monix.bio.UIO

import scala.collection.mutable.{Map => MutableMap}

class ProjectsCountsDummy(map: MutableMap[ProjectRef, ProjectCount]) extends ProjectsCounts {
  override def get(): UIO[ProjectCountsCollection] = UIO.pure(ProjectCountsCollection(map.toMap))

  override def get(project: ProjectRef): UIO[Option[ProjectCount]] = get().map(_.get(project))

  override def remove(project: ProjectRef): UIO[Unit] = UIO.pure(map.remove(project)).void
}

object ProjectsCountsDummy {
  final def apply(tuples: (ProjectRef, ProjectCount)*): ProjectsCounts =
    new ProjectsCountsDummy(MutableMap(tuples: _*))
}
