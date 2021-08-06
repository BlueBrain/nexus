package ch.epfl.bluebrain.nexus.delta.wiring

import akka.actor.typed.ActorSystem
import cats.effect.Clock
import ch.epfl.bluebrain.nexus.delta.Main.pluginsMaxPriority
import ch.epfl.bluebrain.nexus.delta.config.AppConfig
import ch.epfl.bluebrain.nexus.delta.kernel.utils.UUIDF
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.contexts
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.{ContextValue, RemoteContextResolution}
import ch.epfl.bluebrain.nexus.delta.rdf.utils.JsonKeyOrdering
import ch.epfl.bluebrain.nexus.delta.routes.SchemasRoutes
import ch.epfl.bluebrain.nexus.delta.sdk._
import ch.epfl.bluebrain.nexus.delta.sdk.eventlog.EventLogUtils.databaseEventLog
import ch.epfl.bluebrain.nexus.delta.sdk.model._
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.ApiMappings
import ch.epfl.bluebrain.nexus.delta.sdk.model.resolvers.ResolverContextResolution
import ch.epfl.bluebrain.nexus.delta.sdk.model.schemas.SchemaEvent
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

  make[Schemas].fromEffect {
    (
        config: AppConfig,
        eventLog: EventLog[Envelope[SchemaEvent]],
        organizations: Organizations,
        projects: Projects,
        schemaImports: SchemaImports,
        resolverContextResolution: ResolverContextResolution,
        resourceIdCheck: ResourceIdCheck,
        quotas: Quotas,
        clock: Clock[UIO],
        uuidF: UUIDF,
        as: ActorSystem[Nothing]
    ) =>
      SchemasImpl(
        organizations,
        projects,
        schemaImports,
        resolverContextResolution,
        config.schemas,
        eventLog,
        resourceIdCheck,
        quotas
      )(uuidF, as, clock)
  }

  make[SchemaImports].from {
    (
        acls: Acls,
        resolvers: Resolvers,
        resources: Resources,
        schemas: Schemas
    ) =>
      SchemaImports(acls, resolvers, schemas, resources)
  }

  make[SchemasRoutes].from {
    (
        identities: Identities,
        acls: Acls,
        organizations: Organizations,
        projects: Projects,
        schemas: Schemas,
        indexingAction: IndexingAction @Id("aggregate"),
        baseUri: BaseUri,
        s: Scheduler,
        cr: RemoteContextResolution @Id("aggregate"),
        ordering: JsonKeyOrdering
    ) =>
      new SchemasRoutes(identities, acls, organizations, projects, schemas, indexingAction)(baseUri, s, cr, ordering)
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

  many[PriorityRoute].add { (route: SchemasRoutes) => PriorityRoute(pluginsMaxPriority + 8, route.routes) }

  many[ReferenceExchange].add { (schemas: Schemas) =>
    Schemas.referenceExchange(schemas)
  }

  make[SchemaEventExchange]
  many[EventExchange].ref[SchemaEventExchange]

  many[EntityType].add(EntityType(Schemas.moduleType))
}
