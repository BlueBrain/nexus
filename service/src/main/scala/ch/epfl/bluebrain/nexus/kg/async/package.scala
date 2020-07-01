package ch.epfl.bluebrain.nexus.kg

import cats.Monad
import cats.implicits._
import ch.epfl.bluebrain.nexus.admin.index.ProjectCache
import ch.epfl.bluebrain.nexus.admin.projects.ProjectResource
import ch.epfl.bluebrain.nexus.iam.acls.{AccessControlList, AccessControlLists}
import ch.epfl.bluebrain.nexus.kg.resources.syntax._
import ch.epfl.bluebrain.nexus.rdf.Iri.Path
import ch.epfl.bluebrain.nexus.rdf.Iri.Path._
import com.typesafe.scalalogging.Logger

package object async {

  val anyProject: Path = "*" / "*"

  /**
    * Resolve the projects from each path inside the ACLs
    *
    * @param acls the ACLs (map of ''Path'' to ''ResourceAccessControlList'')
    * @tparam F effect type
    * @return a map where the key is the [[ProjectResource]] and the value is the [[AccessControlList]] applied for that project
    */
  def resolveProjects[F[_]](
      acls: AccessControlLists
  )(implicit projectCache: ProjectCache[F], log: Logger, F: Monad[F]): F[Map[ProjectResource, AccessControlList]] =
    acls.value.foldLeft(F.pure(Map.empty[ProjectResource, AccessControlList])) {
      case (fProjectsMap, (path, resourceAcl)) =>
        val acl = resourceAcl.value
        for {
          projectMap <- fProjectsMap
          projects   <- path.resolveProjects
        } yield projects.foldLeft(projectMap)((acc, project) =>
          acc + (project -> acc.get(project).map(_ ++ acl).getOrElse(acl))
        )
    }
}
