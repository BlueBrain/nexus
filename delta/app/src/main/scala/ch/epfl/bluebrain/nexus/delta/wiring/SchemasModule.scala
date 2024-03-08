package ch.epfl.bluebrain.nexus.delta.wiring

import cats.effect.{Clock, IO}
import ch.epfl.bluebrain.nexus.delta.Main.pluginsMaxPriority
import ch.epfl.bluebrain.nexus.delta.kernel.utils.{ClasspathResourceLoader, UUIDF}
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.contexts
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.api.JsonLdApi
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.{ContextValue, RemoteContextResolution}
import ch.epfl.bluebrain.nexus.delta.rdf.shacl.ShaclShapesGraph
import ch.epfl.bluebrain.nexus.delta.rdf.utils.JsonKeyOrdering
import ch.epfl.bluebrain.nexus.delta.routes.SchemasRoutes
import ch.epfl.bluebrain.nexus.delta.sdk.IndexingAction.AggregateIndexingAction
import ch.epfl.bluebrain.nexus.delta.sdk._
import ch.epfl.bluebrain.nexus.delta.sdk.acls.AclCheck
import ch.epfl.bluebrain.nexus.delta.sdk.directives.DeltaSchemeDirectives
import ch.epfl.bluebrain.nexus.delta.sdk.fusion.FusionConfig
import ch.epfl.bluebrain.nexus.delta.sdk.identities.Identities
import ch.epfl.bluebrain.nexus.delta.sdk.model._
import ch.epfl.bluebrain.nexus.delta.sdk.model.metrics.ScopedEventMetricEncoder
import ch.epfl.bluebrain.nexus.delta.sdk.projects.FetchContext
import ch.epfl.bluebrain.nexus.delta.sdk.projects.model.ApiMappings
import ch.epfl.bluebrain.nexus.delta.sdk.resolvers.{ResolverContextResolution, Resolvers}
import ch.epfl.bluebrain.nexus.delta.sdk.resources.FetchResource
import ch.epfl.bluebrain.nexus.delta.sdk.schemas.Schemas.{ScopedSchemaDefinition, ScopedSchemaLog}
import ch.epfl.bluebrain.nexus.delta.sdk.schemas.model.{Schema, SchemaEvent}
import ch.epfl.bluebrain.nexus.delta.sdk.schemas._
import ch.epfl.bluebrain.nexus.delta.sdk.sse.SseEncoder
import ch.epfl.bluebrain.nexus.delta.sourcing.{ScopedEventLog, Transactors}
import izumi.distage.model.definition.{Id, ModuleDef}

/**
  * Schemas wiring
  */
object SchemasModule extends ModuleDef {

  implicit private val loader: ClasspathResourceLoader = ClasspathResourceLoader.withContext(getClass)

  make[ValidateSchema].fromEffect { (api: JsonLdApi, rcr: RemoteContextResolution @Id("aggregate")) =>
    ShaclShapesGraph.shaclShaclShapes.map(ValidateSchema(api, _, rcr))

  }

  make[ScopedSchemaDefinition].from { (validateSchema: ValidateSchema, clock: Clock[IO]) =>
    Schemas.definition(validateSchema, clock)
  }

  make[ScopedSchemaLog].from { (scopedDefinition: ScopedSchemaDefinition, config: SchemasConfig, xas: Transactors) =>
    ScopedEventLog(scopedDefinition, config.eventLog, xas)
  }

  make[Schemas].from {
    (
        scopedLog: ScopedSchemaLog,
        fetchContext: FetchContext,
        schemaImports: SchemaImports,
        api: JsonLdApi,
        resolverContextResolution: ResolverContextResolution,
        uuidF: UUIDF
    ) =>
      SchemasImpl(
        scopedLog,
        fetchContext,
        schemaImports,
        resolverContextResolution
      )(api, uuidF)
  }

  make[SchemaImports].from {
    (
        aclCheck: AclCheck,
        resolvers: Resolvers,
        schemas: Schemas,
        fetchResource: FetchResource
    ) =>
      SchemaImports(aclCheck, resolvers, schemas, fetchResource)
  }

  make[SchemasRoutes].from {
    (
        identities: Identities,
        aclCheck: AclCheck,
        schemas: Schemas,
        schemeDirectives: DeltaSchemeDirectives,
        indexingAction: AggregateIndexingAction,
        shift: Schema.Shift,
        baseUri: BaseUri,
        cr: RemoteContextResolution @Id("aggregate"),
        ordering: JsonKeyOrdering,
        fusionConfig: FusionConfig
    ) =>
      new SchemasRoutes(identities, aclCheck, schemas, schemeDirectives, indexingAction(_, _, _)(shift))(
        baseUri,
        cr,
        ordering,
        fusionConfig
      )
  }

  many[SseEncoder[_]].add { base: BaseUri => SchemaEvent.sseEncoder(base) }

  many[ScopedEventMetricEncoder[_]].add { SchemaEvent.schemaEventMetricEncoder }

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

  make[Schema.Shift].from { (schemas: Schemas, base: BaseUri) =>
    Schema.shift(schemas)(base)
  }

  many[ResourceShift[_, _, _]].ref[Schema.Shift]
}
