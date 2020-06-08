package ch.epfl.bluebrain.nexus.iam.config

import ch.epfl.bluebrain.nexus.commons.cache.KeyValueStoreConfig
import ch.epfl.bluebrain.nexus.iam.config.IamConfig._
import ch.epfl.bluebrain.nexus.iam.types.Permission
import ch.epfl.bluebrain.nexus.sourcing.akka.aggregate.AggregateConfig
import ch.epfl.bluebrain.nexus.sourcing.akka.statemachine.StateMachineConfig
import ch.epfl.bluebrain.nexus.sourcing.projections.IndexingConfig

/**
  * Application configuration
  *
  * @param indexing    indexing configuration
  * @param acls        configuration for acls
  * @param permissions configuration for permissions
  * @param realms      configuration for realms
  * @param groups      configuration for groups
  */
final case class IamConfig(
    indexing: IndexingConfig,
    acls: AclsConfig,
    permissions: PermissionsConfig,
    realms: RealmsConfig,
    groups: StateMachineConfig
)

object IamConfig {

  /**
    * ACLs configuration
    *
    * @param aggregate the acls aggregate configuration
    * @param indexing the indexing configuration
    */
  final case class AclsConfig(aggregate: AggregateConfig, indexing: IndexingConfig)

  /**
    * Permissions configuration.
    *
    * @param aggregate the permissions aggregate configuration
    * @param minimum  the minimum set of permissions
    */
  final case class PermissionsConfig(aggregate: AggregateConfig, minimum: Set[Permission])

  /**
    * Realms configuration.
    *
    * @param aggregate      the realms aggregate configuration
    * @param keyValueStore the key value store configuration
    * @param indexing      the indexing configuration
    */
  final case class RealmsConfig(
      aggregate: AggregateConfig,
      keyValueStore: KeyValueStoreConfig,
      indexing: IndexingConfig
  )


  implicit def toPermissionConfig(implicit appConfig: IamConfig): PermissionsConfig = appConfig.permissions
  implicit def toAclsConfig(implicit appConfig: IamConfig): AclsConfig              = appConfig.acls
  implicit def toIndexing(implicit appConfig: IamConfig): IndexingConfig            = appConfig.indexing
}
