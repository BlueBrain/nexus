package ch.epfl.bluebrain.nexus.delta.sdk

import cats.implicits._
import cats.kernel.Monoid
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.sdk.ProjectReferenceFinder.ProjectReferenceMap
import ch.epfl.bluebrain.nexus.delta.sdk.model.ResourceF
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.ProjectRef
import io.circe.Encoder
import io.circe.generic.semiauto.deriveEncoder
import monix.bio.UIO

import java.time.Instant

/**
  * Allows to find projects reference to the one provided from other projects
  */
trait ProjectReferenceFinder {

  /**
    * Find references in other project for the given project
    */
  def apply(project: ProjectRef): UIO[ProjectReferenceMap]

}

object ProjectReferenceFinder {

  final case class ProjectReferenceMap(value: Map[ProjectRef, List[Iri]])

  object ProjectReferenceMap {

    val empty: ProjectReferenceMap = ProjectReferenceMap(Map.empty)

    def single(project: ProjectRef, ids: Iri*): ProjectReferenceMap =
      ProjectReferenceMap(Map(project -> ids.toList))

    def of(entries: (ProjectRef, List[Iri])*): ProjectReferenceMap  = ProjectReferenceMap(Map.from(entries))

    implicit val projectReferenceMapMonoid: Monoid[ProjectReferenceMap] =
      new Monoid[ProjectReferenceMap] {
        override def empty: ProjectReferenceMap = ProjectReferenceMap.empty

        override def combine(x: ProjectReferenceMap, y: ProjectReferenceMap): ProjectReferenceMap =
          ProjectReferenceMap(x.value |+| y.value)
      }

    implicit val projectReferenceMapEncoder: Encoder.AsObject[ProjectReferenceMap] = deriveEncoder[ProjectReferenceMap]

  }
  def ordering[A]: Ordering[ResourceF[A]] = Ordering[Instant] on (r => r.createdAt)

  /**
    * Combine several reference finders in a single one
    */
  def combine(finders: Seq[ProjectReferenceFinder]): ProjectReferenceFinder =
    (project: ProjectRef) =>
      finders.foldLeftM(ProjectReferenceMap.empty) { (acc, finder) =>
        finder(project).map(acc |+| _)
      }

}
