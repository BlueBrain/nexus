package ai.senscience.nexus.delta.wiring

import ai.senscience.nexus.delta.Main.pluginsMaxPriority
import ai.senscience.nexus.delta.routes.{AclsRoutes, UserPermissionsRoutes}
import akka.http.scaladsl.server.RouteConcatenation
import cats.effect.{Clock, IO}
import ch.epfl.bluebrain.nexus.delta.kernel.Logger
import ch.epfl.bluebrain.nexus.delta.kernel.utils.ClasspathResourceLoader
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.contexts
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.{ContextValue, RemoteContextResolution}
import ch.epfl.bluebrain.nexus.delta.rdf.utils.JsonKeyOrdering
import ch.epfl.bluebrain.nexus.delta.sdk.*
import ch.epfl.bluebrain.nexus.delta.sdk.acls.*
import ch.epfl.bluebrain.nexus.delta.sdk.acls.model.FlattenedAclStore
import ch.epfl.bluebrain.nexus.delta.sdk.deletion.ProjectDeletionTask
import ch.epfl.bluebrain.nexus.delta.sdk.identities.Identities
import ch.epfl.bluebrain.nexus.delta.sdk.identities.model.ServiceAccount
import ch.epfl.bluebrain.nexus.delta.sdk.model.{BaseUri, MetadataContextValue}
import ch.epfl.bluebrain.nexus.delta.sdk.permissions.{Permissions, PermissionsConfig, StoragePermissionProvider}
import ch.epfl.bluebrain.nexus.delta.sdk.projects.OwnerPermissionsScopeInitialization
import ch.epfl.bluebrain.nexus.delta.sourcing.Transactors
import izumi.distage.model.definition.{Id, ModuleDef}

/**
  * Acls module wiring config.
  */
// $COVERAGE-OFF$
class AclsModule(aclsConfig: AclsConfig, permissionConfig: PermissionsConfig) extends ModuleDef {

  private val logger = Logger[AclsModule]

  implicit private val loader: ClasspathResourceLoader = ClasspathResourceLoader.withContext(getClass)

  make[FlattenedAclStore].from { (xas: Transactors) => new FlattenedAclStore(xas) }

  make[Acls].fromEffect {
    (
        permissions: Permissions,
        flattenedAclStore: FlattenedAclStore,
        xas: Transactors,
        clock: Clock[IO]
    ) =>
      AclsImpl.applyWithInitial(
        permissions.fetchPermissionSet,
        AclsImpl.findUnknownRealms(xas),
        permissions.minimum,
        aclsConfig.eventLog,
        flattenedAclStore,
        xas,
        clock
      )
  }

  make[AclCheck].from { (flattenedAclStore: FlattenedAclStore) => AclCheck(flattenedAclStore) }

  make[AclsRoutes].from {
    (
        identities: Identities,
        acls: Acls,
        aclCheck: AclCheck,
        baseUri: BaseUri,
        cr: RemoteContextResolution @Id("aggregate"),
        ordering: JsonKeyOrdering
    ) =>
      new AclsRoutes(identities, acls, aclCheck)(baseUri, cr, ordering)
  }

  if (aclsConfig.enableOwnerPermissions) {
    many[ScopeInitialization].addEffect { (acls: Acls, serviceAccount: ServiceAccount) =>
      logger
        .info("Owner permissions initialization is enabled")
        .as(
          OwnerPermissionsScopeInitialization(acls, permissionConfig.ownerPermissions, serviceAccount)
        )
    }
  }

  make[AclProvisioning].from { (acls: Acls, serviceAccount: ServiceAccount) =>
    new AclProvisioning(acls, aclsConfig.provisioning, serviceAccount)
  }

  many[ProjectDeletionTask].add { (acls: Acls) => Acls.projectDeletionTask(acls) }

  many[MetadataContextValue].addEffect(MetadataContextValue.fromFile("contexts/acls-metadata.json"))

  many[RemoteContextResolution].addEffect(
    for {
      aclsCtx     <- ContextValue.fromFile("contexts/acls.json")
      aclsMetaCtx <- ContextValue.fromFile("contexts/acls-metadata.json")
    } yield RemoteContextResolution.fixed(contexts.acls -> aclsCtx, contexts.aclsMetadata -> aclsMetaCtx)
  )

  make[UserPermissionsRoutes].from {
    (
        identities: Identities,
        aclCheck: AclCheck,
        baseUri: BaseUri,
        storagePermissionProvider: StoragePermissionProvider
    ) =>
      new UserPermissionsRoutes(identities, aclCheck, storagePermissionProvider)(baseUri)
  }

  many[PriorityRoute].add { (alcs: AclsRoutes, userPermissions: UserPermissionsRoutes) =>
    PriorityRoute(
      pluginsMaxPriority + 5,
      RouteConcatenation.concat(alcs.routes, userPermissions.routes),
      requiresStrictEntity = true
    )
  }
}
// $COVERAGE-ON$
