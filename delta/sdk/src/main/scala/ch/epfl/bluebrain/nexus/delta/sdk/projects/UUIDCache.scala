package ch.epfl.bluebrain.nexus.delta.sdk.projects

import cats.effect.IO
import ch.epfl.bluebrain.nexus.delta.kernel.cache.{CacheConfig, LocalCache}
import ch.epfl.bluebrain.nexus.delta.sdk.organizations.Organizations
import ch.epfl.bluebrain.nexus.delta.sourcing.Transactors
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{Label, ProjectRef}
import doobie.implicits._

import java.util.UUID

/**
  * Allows to fetch org labels and project references by uuid
  */
trait UUIDCache {

  /**
    * Fetches an org label from an uuid
    */
  def orgLabel(uuid: UUID): IO[Option[Label]]

  /**
    * Fetches a project reference from an uuid
    */
  def projectRef(uuid: UUID): IO[Option[ProjectRef]]

}

object UUIDCache {

  def apply(projectsConfig: CacheConfig, orgsConfig: CacheConfig, xas: Transactors): IO[UUIDCache] =
    for {
      orgsCache     <- LocalCache.lru[UUID, Label](orgsConfig)
      projectsCache <- LocalCache.lru[UUID, ProjectRef](projectsConfig)
    } yield new UUIDCache {
      override def orgLabel(uuid: UUID): IO[Option[Label]] =
        orgsCache.getOrElseAttemptUpdate(
          uuid,
          sql"SELECT value->>'label' FROM global_states WHERE type = ${Organizations.entityType} AND value->>'uuid' = ${uuid.toString} "
            .query[Label]
            .option
            .transact(xas.read)
        )

      override def projectRef(uuid: UUID): IO[Option[ProjectRef]] =
        projectsCache.getOrElseAttemptUpdate(
          uuid,
          sql"SELECT org, project FROM scoped_states WHERE type = ${Projects.entityType} AND value->>'uuid' = ${uuid.toString} "
            .query[(Label, Label)]
            .map { case (o, p) => ProjectRef(o, p) }
            .option
            .transact(xas.read)
        )
    }

}
