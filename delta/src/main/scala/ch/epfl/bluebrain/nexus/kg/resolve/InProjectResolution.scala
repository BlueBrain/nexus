package ch.epfl.bluebrain.nexus.kg.resolve

import ch.epfl.bluebrain.nexus.kg.resources.ProjectIdentifier.ProjectRef
import ch.epfl.bluebrain.nexus.kg.resources._

/**
  * Simplest implementation that handles the resolution process of references to resources
  * within a given project.
  *
  * @param project the resolution scope
  * @param repo the resources repository
  * @tparam F      the resolution effect type
  */
class InProjectResolution[F[_]](project: ProjectRef, repo: Repo[F]) extends Resolution[F] {

  override def resolve(ref: Ref): F[Option[Resource]] =
    ref match {
      case Ref.Latest(value)        => repo.get(Id(project, value), None).value
      case Ref.Revision(value, rev) => repo.get(Id(project, value), rev, None).value
      case Ref.Tag(value, tag)      => repo.get(Id(project, value), tag, None).value
    }
}

object InProjectResolution {

  /**
    * Constructs an [[InProjectResolution]] instance.
    */
  def apply[F[_]](project: ProjectRef, repo: Repo[F]): InProjectResolution[F] =
    new InProjectResolution(project, repo)
}
