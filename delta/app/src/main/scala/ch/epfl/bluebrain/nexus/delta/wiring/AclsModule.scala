package ch.epfl.bluebrain.nexus.delta.wiring

import akka.http.scaladsl.server.RouteConcatenation
import cats.effect.Clock
import ch.epfl.bluebrain.nexus.delta.Main.pluginsMaxPriority
import ch.epfl.bluebrain.nexus.delta.config.AppConfig
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.contexts
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.{ContextValue, RemoteContextResolution}
import ch.epfl.bluebrain.nexus.delta.rdf.utils.JsonKeyOrdering
import ch.epfl.bluebrain.nexus.delta.routes.{AclsRoutes, UserPermissionsRoutes}
import ch.epfl.bluebrain.nexus.delta.sdk._
import ch.epfl.bluebrain.nexus.delta.sdk.acls.model.AclEvent
import ch.epfl.bluebrain.nexus.delta.sdk.acls.{AclCheck, Acls, AclsImpl}
import ch.epfl.bluebrain.nexus.delta.sdk.deletion.ProjectDeletionTask
import ch.epfl.bluebrain.nexus.delta.sdk.identities.Identities
import ch.epfl.bluebrain.nexus.delta.sdk.model.{BaseUri, MetadataContextValue}
import ch.epfl.bluebrain.nexus.delta.sdk.permissions.Permissions
import ch.epfl.bluebrain.nexus.delta.sdk.sse.SseEncoder
import ch.epfl.bluebrain.nexus.delta.sourcing.Transactors
import izumi.distage.model.definition.{Id, ModuleDef}
import monix.bio.UIO
import monix.execution.Scheduler

/**
  * Acls module wiring config.
  */
// $COVERAGE-OFF$
object AclsModule extends ModuleDef {
  implicit private val classLoader: ClassLoader = getClass.getClassLoader

  make[Acls].from {
    (
        permissions: Permissions,
        config: AppConfig,
        xas: Transactors,
        clock: Clock[UIO]
    ) =>
      acls.AclsImpl(
        permissions.fetchPermissionSet,
        AclsImpl.findUnknownRealms(xas),
        permissions.minimum,
        config.acls,
        xas
      )(clock)
  }

  make[AclCheck].from { (acls: Acls) => AclCheck(acls) }

  make[AclsRoutes].from {
    (
        identities: Identities,
        acls: Acls,
        aclCheck: AclCheck,
        baseUri: BaseUri,
        s: Scheduler,
        cr: RemoteContextResolution @Id("aggregate"),
        ordering: JsonKeyOrdering
    ) =>
      new AclsRoutes(identities, acls, aclCheck)(baseUri, s, cr, ordering)
  }

  many[ProjectDeletionTask].add { (acls: Acls) => Acls.projectDeletionTask(acls) }

  many[SseEncoder[_]].add { base: BaseUri => AclEvent.sseEncoder(base) }

  many[MetadataContextValue].addEffect(MetadataContextValue.fromFile("contexts/acls-metadata.json"))

  many[RemoteContextResolution].addEffect(
    for {
      aclsCtx     <- ContextValue.fromFile("contexts/acls.json")
      aclsMetaCtx <- ContextValue.fromFile("contexts/acls-metadata.json")
    } yield RemoteContextResolution.fixed(contexts.acls -> aclsCtx, contexts.aclsMetadata -> aclsMetaCtx)
  )

  make[UserPermissionsRoutes].from { (identities: Identities, aclCheck: AclCheck, baseUri: BaseUri) =>
    new UserPermissionsRoutes(identities, aclCheck)(baseUri)
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
