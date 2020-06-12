package ch.epfl.bluebrain.nexus.admin.config

import ch.epfl.bluebrain.nexus.admin.config.AdminConfig._
import ch.epfl.bluebrain.nexus.commons.cache.KeyValueStoreConfig
import ch.epfl.bluebrain.nexus.commons.search.FromPagination
import ch.epfl.bluebrain.nexus.iam.auth.AccessToken
import ch.epfl.bluebrain.nexus.iam.types.Permission
import ch.epfl.bluebrain.nexus.sourcing.RetryStrategyConfig
import ch.epfl.bluebrain.nexus.sourcing.akka.aggregate.AggregateConfig
import ch.epfl.bluebrain.nexus.sourcing.projections.IndexingConfig

/**
  * Application configuration
  *
  * @param indexing       Indexing configuration
  * @param keyValueStore  Distributed data configuration
  * @param aggregate      Aggregate configuration
  * @param pagination     pagination configuration
  * @param permissions    permissions configuration
  */
final case class AdminConfig(
    indexing: IndexingConfig,
    keyValueStore: KeyValueStoreConfig,
    aggregate: AggregateConfig,
    pagination: PaginationConfig,
    serviceAccount: ServiceAccountConfig,
    permissions: PermissionsConfig
)

object AdminConfig {

  final case class ServiceAccountConfig(token: Option[String]) {
    def credentials: Option[AccessToken] = token.map(AccessToken)
  }

  /**
    * Permissions configuration.
    *
    * @param owner  permissions applied to the creator of the project.
    */
  final case class PermissionsConfig(owner: Set[String], retry: RetryStrategyConfig) {

    def ownerPermissions: Set[Permission] = owner.map(Permission.unsafe)
  }

  /**
    * Pagination configuration
    *
    * @param size    the default results size
    * @param maxSize the maximum results size
    */
  final case class PaginationConfig(size: Int, maxSize: Int) {
    val default: FromPagination = FromPagination(0, size)
  }

  implicit def toPermissionsConfig(implicit config: AdminConfig): PermissionsConfig = config.permissions
  implicit def toKeyValueStore(implicit config: AdminConfig): KeyValueStoreConfig   = config.keyValueStore

}
