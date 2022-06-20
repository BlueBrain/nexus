package ch.epfl.bluebrain.nexus.delta.wiring

import cats.effect.Clock
import ch.epfl.bluebrain.nexus.delta.Main.pluginsMaxPriority
import ch.epfl.bluebrain.nexus.delta.config.AppConfig
import ch.epfl.bluebrain.nexus.delta.kernel.Transactors
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.contexts
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.{ContextValue, RemoteContextResolution}
import ch.epfl.bluebrain.nexus.delta.rdf.utils.JsonKeyOrdering
import ch.epfl.bluebrain.nexus.delta.routes.PermissionsRoutes
import ch.epfl.bluebrain.nexus.delta.sdk._
import ch.epfl.bluebrain.nexus.delta.sdk.acls.AclCheck
import ch.epfl.bluebrain.nexus.delta.sdk.identities.Identities
import ch.epfl.bluebrain.nexus.delta.sdk.model.{BaseUri, MetadataContextValue}
import ch.epfl.bluebrain.nexus.delta.sdk.permissions.{Permissions, PermissionsImpl}
import izumi.distage.model.definition.{Id, ModuleDef}
import monix.bio.UIO
import monix.execution.Scheduler

/**
  * Permissions module wiring config.
  */
// $COVERAGE-OFF$
object PermissionsModule extends ModuleDef {
  implicit private val classLoader = getClass.getClassLoader

  make[Permissions].fromEffect { (cfg: AppConfig, xas: Transactors, clock: Clock[UIO]) =>
    PermissionsImpl(
      cfg.permissions,
      xas
    )(clock)
  }

  make[PermissionsRoutes].from {
    (
        identities: Identities,
        permissions: Permissions,
        aclCheck: AclCheck,
        baseUri: BaseUri,
        s: Scheduler,
        cr: RemoteContextResolution @Id("aggregate"),
        ordering: JsonKeyOrdering
    ) => new PermissionsRoutes(identities, permissions, aclCheck)(baseUri, s, cr, ordering)
  }

  many[MetadataContextValue].addEffect(MetadataContextValue.fromFile("contexts/permissions-metadata.json"))

  many[RemoteContextResolution].addEffect(
    for {
      permissionsCtx     <- ContextValue.fromFile("contexts/permissions.json")
      permissionsMetaCtx <- ContextValue.fromFile("contexts/permissions-metadata.json")
    } yield RemoteContextResolution.fixed(
      contexts.permissions         -> permissionsCtx,
      contexts.permissionsMetadata -> permissionsMetaCtx
    )
  )

  many[PriorityRoute].add { (route: PermissionsRoutes) =>
    PriorityRoute(pluginsMaxPriority + 3, route.routes, requiresStrictEntity = true)
  }
}
// $COVERAGE-ON$
