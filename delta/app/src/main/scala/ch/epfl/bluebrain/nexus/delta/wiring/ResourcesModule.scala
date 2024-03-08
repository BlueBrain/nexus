package ch.epfl.bluebrain.nexus.delta.wiring

import cats.effect.{Clock, IO}
import ch.epfl.bluebrain.nexus.delta.Main.pluginsMinPriority
import ch.epfl.bluebrain.nexus.delta.config.AppConfig
import ch.epfl.bluebrain.nexus.delta.kernel.utils.UUIDF
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.api.JsonLdApi
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.RemoteContextResolution
import ch.epfl.bluebrain.nexus.delta.rdf.utils.JsonKeyOrdering
import ch.epfl.bluebrain.nexus.delta.routes.ResourcesRoutes
import ch.epfl.bluebrain.nexus.delta.sdk.IndexingAction.AggregateIndexingAction
import ch.epfl.bluebrain.nexus.delta.sdk._
import ch.epfl.bluebrain.nexus.delta.sdk.acls.AclCheck
import ch.epfl.bluebrain.nexus.delta.sdk.fusion.FusionConfig
import ch.epfl.bluebrain.nexus.delta.sdk.identities.Identities
import ch.epfl.bluebrain.nexus.delta.sdk.model.BaseUri
import ch.epfl.bluebrain.nexus.delta.sdk.model.metrics.ScopedEventMetricEncoder
import ch.epfl.bluebrain.nexus.delta.sdk.projects.FetchContext
import ch.epfl.bluebrain.nexus.delta.sdk.projects.model.ApiMappings
import ch.epfl.bluebrain.nexus.delta.sdk.resolvers.ResolverResolution.ResourceResolution
import ch.epfl.bluebrain.nexus.delta.sdk.resolvers.{ResolverContextResolution, Resolvers, ResourceResolution}
import ch.epfl.bluebrain.nexus.delta.sdk.resources.Resources.{ScopedResourceDefinition, ScopedResourceLog}
import ch.epfl.bluebrain.nexus.delta.sdk.resources._
import ch.epfl.bluebrain.nexus.delta.sdk.resources.model.{Resource, ResourceEvent}
import ch.epfl.bluebrain.nexus.delta.sdk.schemas.Schemas
import ch.epfl.bluebrain.nexus.delta.sdk.schemas.model.Schema
import ch.epfl.bluebrain.nexus.delta.sdk.sse.SseEncoder
import ch.epfl.bluebrain.nexus.delta.sourcing.{ScopedEventLog, Transactors}
import izumi.distage.model.definition.{Id, ModuleDef}

/**
  * Resources wiring
  */
object ResourcesModule extends ModuleDef {
  make[ResourceResolution[Schema]].from { (aclCheck: AclCheck, resolvers: Resolvers, schemas: Schemas) =>
    ResourceResolution.schemaResource(aclCheck, resolvers, schemas, excludeDeprecated = false)
  }

  make[ValidateResource].from {
    (resourceResolution: ResourceResolution[Schema], rcr: RemoteContextResolution @Id("aggregate")) =>
      ValidateResource(resourceResolution)(rcr)
  }

  make[ResourcesConfig].from { (config: AppConfig) => config.resources }

  make[DetectChange].from { (config: ResourcesConfig) => DetectChange(config.skipUpdateNoChange) }

  make[ScopedResourceDefinition].from {
    (validateResource: ValidateResource, detectChange: DetectChange, clock: Clock[IO]) =>
      Resources.definition(validateResource, detectChange, clock)
  }

  make[ScopedResourceLog].from {
    (scopedDefinition: ScopedResourceDefinition, config: ResourcesConfig, xas: Transactors) =>
      ScopedEventLog(scopedDefinition, config.eventLog, xas)
  }

  make[FetchResource].from { (scopedLog: ScopedResourceLog) =>
    FetchResource(scopedLog)
  }

  make[Resources].from {
    (
        scopedLog: ScopedResourceLog,
        fetchContext: FetchContext,
        resolverContextResolution: ResolverContextResolution,
        api: JsonLdApi,
        uuidF: UUIDF
    ) =>
      ResourcesImpl(
        scopedLog,
        fetchContext,
        resolverContextResolution
      )(
        api,
        uuidF
      )
  }

  make[ResolverContextResolution].from {
    (
        aclCheck: AclCheck,
        resolvers: Resolvers,
        rcr: RemoteContextResolution @Id("aggregate"),
        fetchResource: FetchResource
    ) =>
      ResolverContextResolution(aclCheck, resolvers, rcr, fetchResource)
  }

  make[ResourcesRoutes].from {
    (
        identities: Identities,
        aclCheck: AclCheck,
        resources: Resources,
        indexingAction: AggregateIndexingAction,
        shift: Resource.Shift,
        baseUri: BaseUri,
        cr: RemoteContextResolution @Id("aggregate"),
        ordering: JsonKeyOrdering,
        fusionConfig: FusionConfig,
        config: ResourcesConfig
    ) =>
      new ResourcesRoutes(
        identities,
        aclCheck,
        resources,
        indexingAction(_, _, _)(shift)
      )(
        baseUri,
        cr,
        ordering,
        fusionConfig,
        config.decodingOption
      )
  }

  many[SseEncoder[_]].add { base: BaseUri => ResourceEvent.sseEncoder(base) }

  many[ScopedEventMetricEncoder[_]].add { ResourceEvent.resourceEventMetricEncoder }

  many[ApiMappings].add(Resources.mappings)

  many[PriorityRoute].add { (route: ResourcesRoutes) =>
    PriorityRoute(pluginsMinPriority - 1, route.routes, requiresStrictEntity = true)
  }

  make[Resource.Shift].from { (resources: Resources, base: BaseUri) =>
    Resource.shift(resources)(base)
  }

  many[ResourceShift[_, _, _]].ref[Resource.Shift]

}
