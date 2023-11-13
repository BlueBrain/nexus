package ch.epfl.bluebrain.nexus.delta.wiring

import cats.effect.{Clock, ContextShift, IO, Timer}
import ch.epfl.bluebrain.nexus.delta.Main.pluginsMaxPriority
import ch.epfl.bluebrain.nexus.delta.config.AppConfig
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.contexts
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.{ContextValue, RemoteContextResolution}
import ch.epfl.bluebrain.nexus.delta.rdf.utils.JsonKeyOrdering
import ch.epfl.bluebrain.nexus.delta.routes.PermissionsRoutes
import ch.epfl.bluebrain.nexus.delta.sdk._
import ch.epfl.bluebrain.nexus.delta.sdk.acls.AclCheck
import ch.epfl.bluebrain.nexus.delta.sdk.identities.Identities
import ch.epfl.bluebrain.nexus.delta.sdk.model.{BaseUri, MetadataContextValue}
import ch.epfl.bluebrain.nexus.delta.sdk.permissions.model.PermissionsEvent
import ch.epfl.bluebrain.nexus.delta.sdk.permissions.{Permissions, PermissionsImpl}
import ch.epfl.bluebrain.nexus.delta.sdk.sse.SseEncoder
import ch.epfl.bluebrain.nexus.delta.sourcing.Transactors
import izumi.distage.model.definition.{Id, ModuleDef}

/**
  * Permissions module wiring config.
  */
// $COVERAGE-OFF$
object PermissionsModule extends ModuleDef {
  implicit private val classLoader: ClassLoader = getClass.getClassLoader

  make[Permissions].from {
    (cfg: AppConfig, xas: Transactors, clock: Clock[IO], contextShift: ContextShift[IO], timer: Timer[IO]) =>
      PermissionsImpl(
        cfg.permissions,
        xas
      )(clock, contextShift, timer)
  }

  make[PermissionsRoutes].from {
    (
        identities: Identities,
        permissions: Permissions,
        aclCheck: AclCheck,
        baseUri: BaseUri,
        cr: RemoteContextResolution @Id("aggregate"),
        ordering: JsonKeyOrdering
    ) => new PermissionsRoutes(identities, permissions, aclCheck)(baseUri, cr, ordering)
  }

  many[SseEncoder[_]].add { base: BaseUri => PermissionsEvent.sseEncoder(base) }

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
