package ch.epfl.bluebrain.nexus.delta.wiring

import cats.effect.Clock
import ch.epfl.bluebrain.nexus.delta.Main.pluginsMaxPriority
import ch.epfl.bluebrain.nexus.delta.config.AppConfig
import ch.epfl.bluebrain.nexus.delta.kernel.database.Transactors
import ch.epfl.bluebrain.nexus.delta.kernel.utils.UUIDF
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.contexts
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.{ContextValue, RemoteContextResolution}
import ch.epfl.bluebrain.nexus.delta.rdf.utils.JsonKeyOrdering
import ch.epfl.bluebrain.nexus.delta.routes.OrganizationsRoutes
import ch.epfl.bluebrain.nexus.delta.sdk._
import ch.epfl.bluebrain.nexus.delta.sdk.acls.AclCheck
import ch.epfl.bluebrain.nexus.delta.sdk.directives.DeltaSchemeDirectives
import ch.epfl.bluebrain.nexus.delta.sdk.identities.Identities
import ch.epfl.bluebrain.nexus.delta.sdk.model.{BaseUri, MetadataContextValue}
import ch.epfl.bluebrain.nexus.delta.sdk.organizations.model.OrganizationEvent
import ch.epfl.bluebrain.nexus.delta.sdk.organizations.{Organizations, OrganizationsImpl}
import ch.epfl.bluebrain.nexus.delta.sdk.sse.SseEncoder
import izumi.distage.model.definition.{Id, ModuleDef}
import monix.bio.UIO
import monix.execution.Scheduler

/**
  * Organizations module wiring config.
  */
// $COVERAGE-OFF$
object OrganizationsModule extends ModuleDef {
  implicit private val classLoader = getClass.getClassLoader

  make[Organizations].from {
    (
        config: AppConfig,
        scopeInitializations: Set[ScopeInitialization],
        clock: Clock[UIO],
        uuidF: UUIDF,
        xas: Transactors
    ) =>
      OrganizationsImpl(
        scopeInitializations,
        config.organizations,
        xas
      )(clock, uuidF)
  }

  make[OrganizationsRoutes].from {
    (
        identities: Identities,
        organizations: Organizations,
        cfg: AppConfig,
        aclCheck: AclCheck,
        schemeDirectives: DeltaSchemeDirectives,
        s: Scheduler,
        cr: RemoteContextResolution @Id("aggregate"),
        ordering: JsonKeyOrdering
    ) =>
      new OrganizationsRoutes(identities, organizations, aclCheck, schemeDirectives)(
        cfg.http.baseUri,
        cfg.organizations.pagination,
        s,
        cr,
        ordering
      )
  }

  many[SseEncoder[_]].add { base: BaseUri => OrganizationEvent.sseEncoder(base) }

  many[MetadataContextValue].addEffect(MetadataContextValue.fromFile("contexts/organizations-metadata.json"))

  many[RemoteContextResolution].addEffect(
    for {
      orgsCtx     <- ContextValue.fromFile("contexts/organizations.json")
      orgsMetaCtx <- ContextValue.fromFile("contexts/organizations-metadata.json")
    } yield RemoteContextResolution.fixed(
      contexts.organizations         -> orgsCtx,
      contexts.organizationsMetadata -> orgsMetaCtx
    )
  )

  many[PriorityRoute].add { (route: OrganizationsRoutes) =>
    PriorityRoute(pluginsMaxPriority + 6, route.routes, requiresStrictEntity = true)
  }

}
// $COVERAGE-ON$
