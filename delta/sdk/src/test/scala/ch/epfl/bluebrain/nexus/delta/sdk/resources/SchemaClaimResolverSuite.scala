package ch.epfl.bluebrain.nexus.delta.sdk.resources

import cats.effect.IO
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.{contexts, nxv, schemas}
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.api.{JsonLdApi, TitaniumJsonLdApi}
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.{ContextValue, RemoteContextResolution}
import ch.epfl.bluebrain.nexus.delta.sdk.generators.{ResourceResolutionGen, SchemaGen}
import ch.epfl.bluebrain.nexus.delta.sdk.identities.model.Caller
import ch.epfl.bluebrain.nexus.delta.sdk.model.Fetch.FetchF
import ch.epfl.bluebrain.nexus.delta.sdk.resolvers.ResolverResolution.ResourceResolution
import ch.epfl.bluebrain.nexus.delta.sdk.resources.ResourcesConfig.SchemaEnforcementConfig
import ch.epfl.bluebrain.nexus.delta.sdk.resources.model.ResourceRejection.{SchemaIsDeprecated, SchemaIsMandatory}
import ch.epfl.bluebrain.nexus.delta.sdk.schemas.model.Schema
import ch.epfl.bluebrain.nexus.delta.sdk.syntax.*
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.User
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{Label, ProjectRef, ResourceRef}
import ch.epfl.bluebrain.nexus.testkit.mu.NexusSuite

class SchemaClaimResolverSuite extends NexusSuite {

  implicit val api: JsonLdApi                       = TitaniumJsonLdApi.lenient
  implicit private val rcr: RemoteContextResolution =
    RemoteContextResolution.fixedIO(
      contexts.metadata        -> ContextValue.fromFile("contexts/metadata.json"),
      contexts.shacl           -> ContextValue.fromFile("contexts/shacl.json"),
      contexts.schemasMetadata -> ContextValue.fromFile("contexts/schemas-metadata.json")
    )

  private val project = ProjectRef.unsafe("org", "proj")

  private val subject = User("myuser", Label.unsafe("myrealm"))
  private val caller  = Caller(subject, Set.empty)

  private def schemaValue(id: Iri) = loader
    .jsonContentOf("resources/schema.json")
    .map(_.addContext(contexts.shacl, contexts.schemasMetadata))
    .flatMap { source =>
      SchemaGen.schemaAsync(id, project, source)
    }
    .accepted

  private val unconstrained = ResourceRef.Revision(schemas.resources, 1)

  private val schemaId  = nxv + "my-schema"
  private val schemaRef = ResourceRef.Revision(schemaId, 1)
  private val schema    = SchemaGen.resourceFor(schemaValue(schemaId))

  private val deprecatedSchemaId  = nxv + "deprecated"
  private val deprecatedSchemaRef = ResourceRef.Revision(deprecatedSchemaId, 1)
  private val deprecatedSchema    = SchemaGen.resourceFor(schemaValue(deprecatedSchemaId)).copy(deprecated = true)

  private val fetchSchema: (ResourceRef, ProjectRef) => FetchF[Schema] = {
    case (ref, p) if ref.iri == schemaId && p == project           => IO.some(schema)
    case (ref, p) if ref.iri == deprecatedSchemaId && p == project => IO.some(deprecatedSchema)
    case _                                                         => IO.none
  }
  private val schemaResolution: ResourceResolution[Schema]             =
    ResourceResolutionGen.singleInProject(project, fetchSchema)

  private val strictSchemaClaimResolver = SchemaClaimResolver(
    schemaResolution,
    SchemaEnforcementConfig(Set.empty, allowNoTypes = false)
  )

  test(s"Succeed on a create claim with a defined schema with the strict resolver") {
    val claim = SchemaClaim.onCreate(project, schemaRef, caller)
    strictSchemaClaimResolver(claim, Set.empty, enforceSchema = true).assertEquals(Some(schema))
  }

  test(s"Succeed on a create claim without a schema and with no enforcement with the strict resolver") {
    val claim = SchemaClaim.onCreate(project, unconstrained, caller)
    strictSchemaClaimResolver(claim, Set.empty, enforceSchema = false).assertEquals(None)
  }

  test("Fail on a create claim without a schema and with enforcement with the strict resolver") {
    val claim = SchemaClaim.onCreate(project, unconstrained, caller)
    strictSchemaClaimResolver(claim, Set.empty, enforceSchema = true).interceptEquals(SchemaIsMandatory(project))
  }

  test(s"Succeed on an update claim with a defined schema with the strict resolver") {
    val claim = SchemaClaim.onUpdate(project, None, schemaRef, caller)
    strictSchemaClaimResolver(claim, Set.empty, enforceSchema = true).assertEquals(Some(schema))
  }

  test(s"Succeed on an update claim when staying without a schema with the strict resolver") {
    val claim = SchemaClaim.onUpdate(project, None, unconstrained, caller)
    strictSchemaClaimResolver(claim, Set.empty, enforceSchema = true).assertEquals(None)
  }

  test("Fail on a update claim without a schema and with enforcement with the strict resolver") {
    val claim = SchemaClaim.onUpdate(project, Some(unconstrained), schemaRef, caller)
    strictSchemaClaimResolver(claim, Set.empty, enforceSchema = true).interceptEquals(SchemaIsMandatory(project))
  }

  test("Fail on a create claim with a deprecated schema") {
    val claim = SchemaClaim.onCreate(project, deprecatedSchemaRef, caller)
    strictSchemaClaimResolver(claim, Set.empty, enforceSchema = false).interceptEquals(
      SchemaIsDeprecated(deprecatedSchemaId)
    )
  }

  private val whitelistedType    = nxv + "Whitelist"
  private val nonWhitelistedType = nxv + "NonWhitelist"

  private val lenientSchemaClaimResolver = SchemaClaimResolver(
    schemaResolution,
    SchemaEnforcementConfig(Set(whitelistedType), allowNoTypes = true)
  )

  test(
    s"Succeed on a create claim without a schema and with enforcement on a whitelisted type with a lenient resolver"
  ) {
    val claim = SchemaClaim.onCreate(project, unconstrained, caller)
    lenientSchemaClaimResolver(claim, Set(whitelistedType), enforceSchema = true).assertEquals(None)
  }

  test(s"Succeed on a create claim without a schema and with enforcement for no type with a lenient resolver") {
    val claim = SchemaClaim.onCreate(project, unconstrained, caller)
    lenientSchemaClaimResolver(claim, Set.empty, enforceSchema = true).assertEquals(None)
  }

  test(
    s"Fail on a create claim without a schema and with enforcement on a non-whitelisted type with a lenient resolver"
  ) {
    val claim = SchemaClaim.onCreate(project, unconstrained, caller)
    lenientSchemaClaimResolver(claim, Set(whitelistedType, nonWhitelistedType), enforceSchema = true).interceptEquals(
      SchemaIsMandatory(project)
    )
  }
}
