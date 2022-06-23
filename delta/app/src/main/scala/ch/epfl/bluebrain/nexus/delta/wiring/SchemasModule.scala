package ch.epfl.bluebrain.nexus.delta.wiring

import akka.actor.typed.ActorSystem
import cats.effect.Clock
import ch.epfl.bluebrain.nexus.delta.Main.pluginsMaxPriority
import ch.epfl.bluebrain.nexus.delta.config.AppConfig
import ch.epfl.bluebrain.nexus.delta.kernel.utils.UUIDF
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.contexts
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.api.JsonLdApi
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.{ContextValue, RemoteContextResolution}
import ch.epfl.bluebrain.nexus.delta.rdf.utils.JsonKeyOrdering
import ch.epfl.bluebrain.nexus.delta.routes.SchemasRoutes
import ch.epfl.bluebrain.nexus.delta.sdk._
import ch.epfl.bluebrain.nexus.delta.sdk.acls.AclCheck
import ch.epfl.bluebrain.nexus.delta.sdk.eventlog.EventLogUtils.databaseEventLog
import ch.epfl.bluebrain.nexus.delta.sdk.fusion.FusionConfig
import ch.epfl.bluebrain.nexus.delta.sdk.identities.Identities
import ch.epfl.bluebrain.nexus.delta.sdk.model._
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.ApiMappings
import ch.epfl.bluebrain.nexus.delta.sdk.model.resolvers.ResolverContextResolution
import ch.epfl.bluebrain.nexus.delta.sdk.model.schemas.SchemaEvent
import ch.epfl.bluebrain.nexus.delta.sdk.organizations.Organizations
import ch.epfl.bluebrain.nexus.delta.service.schemas.SchemasImpl.{SchemasAggregate, SchemasCache}
import ch.epfl.bluebrain.nexus.delta.service.schemas.{SchemaEventExchange, SchemasImpl}
import ch.epfl.bluebrain.nexus.delta.sourcing.EventLog
import izumi.distage.model.definition.{Id, ModuleDef}
import monix.bio.UIO
import monix.execution.Scheduler

/**
  * Schemas wiring
  */
object SchemasModule extends ModuleDef {
  implicit private val classLoader = getClass.getClassLoader

  make[EventLog[Envelope[SchemaEvent]]].fromEffect { databaseEventLog[SchemaEvent](_, _) }

  make[SchemasCache].fromEffect { (config: AppConfig) => SchemasImpl.cache(config.schemas) }

  make[SchemasAggregate].fromEffect {
    (
        config: AppConfig,
        resourceIdCheck: ResourceIdCheck,
        api: JsonLdApi,
        as: ActorSystem[Nothing],
        clock: Clock[UIO]
    ) =>
      SchemasImpl.aggregate(config.schemas.aggregate, resourceIdCheck)(api, as, clock)
  }

  make[Schemas].from {
    (
        eventLog: EventLog[Envelope[SchemaEvent]],
        organizations: Organizations,
        projects: Projects,
        schemaImports: SchemaImports,
        api: JsonLdApi,
        resolverContextResolution: ResolverContextResolution,
        agg: SchemasAggregate,
        cache: SchemasCache,
        uuidF: UUIDF
    ) =>
      SchemasImpl(organizations, projects, schemaImports, resolverContextResolution, eventLog, agg, cache)(api, uuidF)
  }

  make[SchemaImports].from {
    (
        aclCheck: AclCheck,
        resolvers: Resolvers,
        resources: Resources,
        schemas: Schemas
    ) =>
      SchemaImports(aclCheck, resolvers, schemas, resources)
  }

  make[SchemasRoutes].from {
    (
        identities: Identities,
        aclCheck: AclCheck,
        organizations: Organizations,
        projects: Projects,
        schemas: Schemas,
        indexingAction: IndexingAction @Id("aggregate"),
        baseUri: BaseUri,
        s: Scheduler,
        cr: RemoteContextResolution @Id("aggregate"),
        ordering: JsonKeyOrdering,
        fusionConfig: FusionConfig
    ) =>
      new SchemasRoutes(identities, aclCheck, organizations, projects, schemas, indexingAction)(
        baseUri,
        s,
        cr,
        ordering,
        fusionConfig
      )
  }

  many[ApiMappings].add(Schemas.mappings)

  many[ResourceToSchemaMappings].add(Schemas.resourcesToSchemas)

  many[MetadataContextValue].addEffect(MetadataContextValue.fromFile("contexts/schemas-metadata.json"))

  many[RemoteContextResolution].addEffect(
    for {
      shaclCtx       <- ContextValue.fromFile("contexts/shacl.json")
      schemasMetaCtx <- ContextValue.fromFile("contexts/schemas-metadata.json")
    } yield RemoteContextResolution.fixed(
      contexts.shacl           -> shaclCtx,
      contexts.schemasMetadata -> schemasMetaCtx
    )
  )

  many[PriorityRoute].add { (route: SchemasRoutes) =>
    PriorityRoute(pluginsMaxPriority + 8, route.routes, requiresStrictEntity = true)
  }

  many[ReferenceExchange].add { (schemas: Schemas) =>
    Schemas.referenceExchange(schemas)
  }

  make[SchemaEventExchange]
  many[EventExchange].ref[SchemaEventExchange]
  many[EventExchange].named("resources").ref[SchemaEventExchange]
}
