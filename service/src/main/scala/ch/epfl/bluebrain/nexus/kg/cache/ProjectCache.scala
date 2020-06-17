package ch.epfl.bluebrain.nexus.kg.cache

import java.util.UUID

import akka.actor.ActorSystem
import cats.Monad
import cats.effect.{Effect, Timer}
import cats.implicits._
import ch.epfl.bluebrain.nexus.admin.client.types.Project
import ch.epfl.bluebrain.nexus.commons.cache.{KeyValueStore, KeyValueStoreConfig}
import ch.epfl.bluebrain.nexus.kg.resources.syntax._
import ch.epfl.bluebrain.nexus.kg.resources.{OrganizationRef, ProjectIdentifier}
import ch.epfl.bluebrain.nexus.kg.resources.ProjectIdentifier._

/**
  * The project cache backed by a KeyValueStore using akka Distributed Data
  *
  * @param store the underlying Distributed Data LWWMap store.
  */
class ProjectCache[F[_]] private (store: KeyValueStore[F, UUID, Project])(implicit F: Monad[F])
    extends Cache[F, UUID, Project](store) {

  implicit private val ordering: Ordering[Project] = Ordering.by { proj =>
    s"${proj.organizationLabel}/${proj.label}"
  }

  /**
    * Attempts to fetch the project with the provided ''identifier''
    *
    * @param identifier the project unique reference
    */
  def get(identifier: ProjectIdentifier): F[Option[Project]] =
    identifier match {
      case label: ProjectLabel => store.findValue(_.projectLabel == label)
      case ref: ProjectRef     => super.get(ref.id)
    }

  /**
    * Attempts to fetch the project with the provided ''ref'' and ''orgRef''
    *
    * @param orgRef the organization unique reference
    * @param ref    the project unique reference
    */
  def get(orgRef: OrganizationRef, ref: ProjectRef): F[Option[Project]] =
    get(ref).map(_.filter(_.organizationUuid == orgRef.id))

  /**
    * Attempts to fetch the project label with the provided ''ref''
    *
    * @param ref the project unique reference
    */
  def getLabel(ref: ProjectRef): F[Option[ProjectLabel]] =
    get(ref.id).map(_.map(project => project.projectLabel))

  /**
    * Fetches all the projects that belong to the provided organization reference
    *
    * @param organizationRef the organization reference to filter the projects
    */
  def list(organizationRef: OrganizationRef): F[List[Project]] =
    store.values.map(_.filter(_.organizationUuid == organizationRef.id).toList.sorted)

  /**
    * Fetches all the projects that belong to the provided organization label
    *
    * @param organizationLabel the organization label to filter the projects
    */
  def list(organizationLabel: String): F[List[Project]] =
    store.values.map(_.filter(_.organizationLabel == organizationLabel).toList.sorted)

  /**
    * Fetches all projects
    */
  def list(): F[List[Project]] =
    store.values.map(_.toList.sorted)

  /**
    * Creates or replaces the project with key provided uuid and value.
    *
    * @param value the project value
    */
  def replace(value: Project): F[Unit] = super.replace(value.uuid, value)

  /**
    * Deprecates the project with the provided ref
    *
    * @param ref the project unique reference
    * @param rev the project new revision
    */
  def deprecate(ref: ProjectRef, rev: Long): F[Unit] =
    store.computeIfPresent(ref.id, c => c.copy(rev = rev, deprecated = true)) >> F.unit

}

object ProjectCache {

  /**
    * Creates a new project index.
    */
  def apply[F[_]: Effect: Timer](implicit as: ActorSystem, config: KeyValueStoreConfig): ProjectCache[F] =
    new ProjectCache(KeyValueStore.distributed("projects", (_, project) => project.rev))
}
