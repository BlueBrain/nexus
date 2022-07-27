package ch.epfl.bluebrain.nexus.delta.wiring

import cats.effect.Clock
import ch.epfl.bluebrain.nexus.delta.Main.pluginsMaxPriority
import ch.epfl.bluebrain.nexus.delta.config.AppConfig
import ch.epfl.bluebrain.nexus.delta.kernel.database.Transactors
import ch.epfl.bluebrain.nexus.delta.kernel.utils.UUIDF
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.contexts
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.api.JsonLdApi
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.{ContextValue, RemoteContextResolution}
import ch.epfl.bluebrain.nexus.delta.rdf.utils.JsonKeyOrdering
import ch.epfl.bluebrain.nexus.delta.routes.SchemasRoutes
import ch.epfl.bluebrain.nexus.delta.sdk._
import ch.epfl.bluebrain.nexus.delta.sdk.acls.AclCheck
import ch.epfl.bluebrain.nexus.delta.sdk.directives.DeltaSchemeDirectives
import ch.epfl.bluebrain.nexus.delta.sdk.fusion.FusionConfig
import ch.epfl.bluebrain.nexus.delta.sdk.identities.Identities
import ch.epfl.bluebrain.nexus.delta.sdk.model._
import ch.epfl.bluebrain.nexus.delta.sdk.projects.FetchContext
import ch.epfl.bluebrain.nexus.delta.sdk.projects.FetchContext.ContextRejection
import ch.epfl.bluebrain.nexus.delta.sdk.projects.model.ApiMappings
import ch.epfl.bluebrain.nexus.delta.sdk.resolvers.{ResolverContextResolution, Resolvers}
import ch.epfl.bluebrain.nexus.delta.sdk.resources.Resources
import ch.epfl.bluebrain.nexus.delta.sdk.schemas.model.SchemaRejection.ProjectContextRejection
import ch.epfl.bluebrain.nexus.delta.sdk.schemas.{SchemaImports, Schemas, SchemasImpl}
import izumi.distage.model.definition.{Id, ModuleDef}
import monix.bio.UIO
import monix.execution.Scheduler

/**
  * Schemas wiring
  */
object SchemasModule extends ModuleDef {
  implicit private val classLoader = getClass.getClassLoader

  make[Schemas].from {
    (
        fetchContext: FetchContext[ContextRejection],
        schemaImports: SchemaImports,
        api: JsonLdApi,
        resolverContextResolution: ResolverContextResolution,
        config: AppConfig,
        xas: Transactors,
        clock: Clock[UIO],
        uuidF: UUIDF
    ) =>
      SchemasImpl(
        fetchContext.mapRejection(ProjectContextRejection),
        schemaImports,
        resolverContextResolution,
        config.schemas,
        xas
      )(api, clock, uuidF)
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
        schemas: Schemas,
        schemeDirectives: DeltaSchemeDirectives,
        indexingAction: IndexingAction @Id("aggregate"),
        baseUri: BaseUri,
        s: Scheduler,
        cr: RemoteContextResolution @Id("aggregate"),
        ordering: JsonKeyOrdering,
        fusionConfig: FusionConfig
    ) =>
      new SchemasRoutes(identities, aclCheck, schemas, schemeDirectives, indexingAction)(
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
}
