package ch.epfl.bluebrain.nexus.kg.resolve

import cats.Monad
import cats.implicits._
import ch.epfl.bluebrain.nexus.admin.index.ProjectCache
import ch.epfl.bluebrain.nexus.iam.acls.AccessControlLists
import ch.epfl.bluebrain.nexus.iam.types.{Identity, Permission}
import ch.epfl.bluebrain.nexus.kg.resources.ProjectIdentifier.ProjectRef
import ch.epfl.bluebrain.nexus.kg.resources._
import ch.epfl.bluebrain.nexus.rdf.Iri

/**
  * Common resolution logic for resolvers that look through several projects.
  *
  * @param repo         the resources repository
  * @param projects     the set of projects to traverse
  * @param types        the set of resource types to filter against
  * @param identities   the resolver identities
  * @param projectCache the project cache
  * @param acls         the ACLs for all identities in all projects
  * @tparam F the resolution effect type
  */
class MultiProjectResolution[F[_]](
    repo: Repo[F],
    projects: List[ProjectRef],
    types: Set[Iri.AbsoluteIri],
    identities: Set[Identity],
    projectCache: ProjectCache[F],
    acls: AccessControlLists
)(implicit F: Monad[F])
    extends Resolution[F] {

  private val read = Permission.unsafe("resources/read")

  override def resolve(ref: Ref): F[Option[Resource]] =
    projects.collectFirstSomeM(pRef => checkPermsAndResolve(ref, pRef))

  private def containsAny[A](a: Set[A], b: Set[A]): Boolean = b.isEmpty || b.exists(a.contains)

  private def checkPermsAndResolve(ref: Ref, project: ProjectRef): F[Option[Resource]] =
    hasPermission(project).flatMap[Option[Resource]] {
      case false =>
        F.pure(None)
      case true  =>
        InProjectResolution(project, repo).resolve(ref).map {
          case v @ Some(resource) if containsAny(resource.types, types) => v
          case _                                                        => None
        }
    }

  private def hasPermission(projectRef: ProjectRef): F[Boolean] =
    projectCache.getBy(projectRef).map {
      case None          =>
        false
      case Some(projRes) =>
        acls.exists(identities, projRes.value.projectLabel, read)
    }
}

object MultiProjectResolution {

  /**
    * Builds a [[MultiProjectResolution]] instance.
    */
  def apply[F[_]: Monad](
      repo: Repo[F],
      projects: List[ProjectRef],
      types: Set[Iri.AbsoluteIri],
      identities: Set[Identity],
      projectCache: ProjectCache[F],
      acls: AccessControlLists
  ): MultiProjectResolution[F] =
    new MultiProjectResolution(repo, projects, types, identities, projectCache, acls)
}
