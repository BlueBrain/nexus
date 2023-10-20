package ch.epfl.bluebrain.nexus.delta.sdk.schemas

import ch.epfl.bluebrain.nexus.delta.kernel.utils.UUIDF
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.{contexts, nxv}
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.api.{JsonLdApi, JsonLdJavaApi}
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.JsonLdContext.keywords
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.{ContextValue, RemoteContextResolution}
import ch.epfl.bluebrain.nexus.delta.sdk.ConfigFixtures
import ch.epfl.bluebrain.nexus.delta.sdk.generators.{ProjectGen, SchemaGen}
import ch.epfl.bluebrain.nexus.delta.sdk.identities.model.Caller
import ch.epfl.bluebrain.nexus.delta.sdk.model.{IdSegmentRef, Tags}
import ch.epfl.bluebrain.nexus.delta.sdk.projects.FetchContextDummy
import ch.epfl.bluebrain.nexus.delta.sdk.projects.model.ApiMappings
import ch.epfl.bluebrain.nexus.delta.sdk.resolvers.ResolverContextResolution
import ch.epfl.bluebrain.nexus.delta.sdk.schemas.model.SchemaRejection._
import ch.epfl.bluebrain.nexus.delta.sdk.syntax._
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.Subject
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Tag.UserTag
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{Identity, Label, ProjectRef}
import ch.epfl.bluebrain.nexus.delta.sourcing.postgres.Doobie
import ch.epfl.bluebrain.nexus.testkit.ce.IOFixedClock
import ch.epfl.bluebrain.nexus.testkit.mu.ce.CatsEffectSuite
import ch.epfl.bluebrain.nexus.testkit.{CirceLiteral, TestHelpers}
import munit.AnyFixture

import java.util.UUID

class SchemasImplSuite
    extends CatsEffectSuite
    with Doobie.Fixture
    with IOFixedClock
    with CirceLiteral
    with ConfigFixtures
    with TestHelpers {

  override def munitFixtures: Seq[AnyFixture[_]] = List(doobie)

  private lazy val xas = doobie()

  implicit private val subject: Subject = Identity.User("user", Label.unsafe("realm"))
  implicit private val caller: Caller   = Caller(subject, Set(subject))

  private val uuid                  = UUID.randomUUID()
  implicit private val uuidF: UUIDF = UUIDF.fixed(uuid)

  implicit private val api: JsonLdApi = JsonLdJavaApi.lenient

  implicit def res: RemoteContextResolution =
    RemoteContextResolution.fixedIO(
      contexts.shacl           -> ContextValue.fromFile("contexts/shacl.json"),
      contexts.schemasMetadata -> ContextValue.fromFile("contexts/schemas-metadata.json")
    )

  private val schemaImports: SchemaImports = SchemaImports.alwaysFail

  private val resolverContextResolution: ResolverContextResolution = ResolverContextResolution(res)

  private val org               = Label.unsafe("myorg")
  private val am                = ApiMappings("nxv" -> nxv.base)
  private val projBase          = nxv.base
  private val project           = ProjectGen.project("myorg", "myproject", base = projBase, mappings = am)
  private val projectDeprecated = ProjectGen.project("myorg", "myproject2")
  private val projectRef        = project.ref
  private val mySchema          = nxv + "myschema"  // Create with id present in payload
  private val mySchema2         = nxv + "myschema2" // Create with id present in payload and passed
  private val mySchema3         = nxv + "myschema3" // Create with id passed
  private val mySchema4         = nxv + "myschema4" // For refreshing
  private val source            = jsonContentOf("resources/schema.json").addContext(contexts.shacl, contexts.schemasMetadata)
  private val sourceNoId        = source.removeKeys(keywords.id)
  private val schema            = SchemaGen.schema(mySchema, project.ref, source)
  private val sourceUpdated     = sourceNoId.replace("datatype" -> "xsd:integer", "xsd:double")
  private val schemaUpdated     = SchemaGen.schema(mySchema, project.ref, sourceUpdated)
  private val tag               = UserTag.unsafe("tag")

  private val fetchContext =
    FetchContextDummy(Map(project.ref -> project.context), Set(projectDeprecated.ref), ProjectContextRejection)
  private val config       = SchemasConfig(eventLogConfig)

  private lazy val schemas: Schemas =
    SchemasImpl(fetchContext, schemaImports, resolverContextResolution, config, xas)

  private def schemaSourceWithId(id: Iri) = {
    source deepMerge json"""{"@id": "$id"}"""
  }

  test("Creating a schema Succeeds with the id present in the payload") {
    val expected = SchemaGen.resourceFor(schema, subject = subject)
    schemas.create(projectRef, source).assertEquals(expected)
  }

  test("Creating a schema succeeds with the id present on the payload and passed") {
    val source   = schemaSourceWithId(mySchema2)
    val schema   = SchemaGen.schema(mySchema2, project.ref, source)
    val expected = SchemaGen.resourceFor(schema, subject = subject)
    schemas.create("myschema2", projectRef, source).assertEquals(expected)
  }

  test("Creating a schema succeeds  with the passed id") {
    val source   = schemaSourceWithId(mySchema3)
    val schema   = SchemaGen.schema(mySchema3, project.ref, source).copy(source = sourceNoId)
    val expected = SchemaGen.resourceFor(schema, subject = subject)
    schemas.create(mySchema3, projectRef, sourceNoId).assertEquals(expected)
  }

  test("Creating a schema as a dry-run succeeds without persisting it in the database") {
    val id       = nxv + "dry-run"
    val source   = schemaSourceWithId(id)
    val schema   = SchemaGen.schema(id, project.ref, source)
    val expected = SchemaGen.resourceFor(schema, subject = subject)
    for {
      _ <- schemas.createDryRun(projectRef, source).assertEquals(expected)
      _ <- schemas.fetch(IdSegmentRef(id), projectRef).intercept[SchemaNotFound]
    } yield ()
  }

  test("Creating a schema fails with different ids on the payload and passed") {
    val otherId = nxv + "other"
    schemas.create(otherId, projectRef, source).intercept(UnexpectedSchemaId(id = otherId, payloadId = mySchema))
  }

  test("Creating a schema fails if it already exists") {
    schemas.create(mySchema, projectRef, source).intercept(ResourceAlreadyExists(mySchema, projectRef))
  }

  test("Creating a schema fails if it does not validate against the SHACL schema") {
    val otherId     = nxv + "other"
    val wrongSource = sourceNoId.replace("minCount" -> 1, "wrong")
    schemas.create(otherId, projectRef, wrongSource).intercept[InvalidSchema]
  }

  test("Creating a schema fails if project does not exist") {
    val projectRef = ProjectRef(org, Label.unsafe("other"))
    schemas.create(projectRef, sourceNoId).intercept[ProjectContextRejection]
  }

  test("Creating a schema fails if project is deprecated") {
    schemas.update(mySchema, projectDeprecated.ref, 2, source).intercept[ProjectContextRejection]
  }

  test("Updating a schema succeeds") {
    val expected = SchemaGen.resourceFor(schemaUpdated, rev = 2, subject = subject)
    schemas.update(mySchema, projectRef, 1, sourceUpdated).assertEquals(expected)
  }

  test("Updating a schema fails if the schema does not exist") {
    schemas.update(nxv + "other", projectRef, 1, json"""{"a": "b"}""").intercept[SchemaNotFound]
  }

  test("Updating a schema fails if the revision passed is incorrect") {
    schemas.update(mySchema, projectRef, 3, json"""{"a": "b"}""").intercept(IncorrectRev(provided = 3, expected = 2))
  }

  test("Updating a schema fails if deprecated") {
    for {
      _ <- schemas.deprecate(mySchema3, projectRef, 1)
      _ <- schemas.update(mySchema3, projectRef, 2, json"""{"a": "b"}""").intercept[SchemaIsDeprecated]
    } yield ()
  }

  test("Updating a schema fails if it does not validate against its schema") {
    val wrongSource = sourceNoId.replace("minCount" -> 1, "wrong")
    schemas.update(mySchema, projectRef, 2, wrongSource).intercept[InvalidSchema]
  }

  test("Updating a schema fails if project does not exist") {
    val projectRef = ProjectRef(org, Label.unsafe("other"))
    schemas.update(mySchema, projectRef, 2, source).intercept[ProjectContextRejection]
  }

  test("Updating a schema fails if project is deprecated") {
    schemas.update(mySchema, projectDeprecated.ref, 2, source).intercept[ProjectContextRejection]
  }

  private val schema4 = SchemaGen.schema(mySchema4, project.ref, schemaSourceWithId(mySchema4))

  test("Creates the schema for subsequent tests") {
    val expected = SchemaGen.resourceFor(schema4, subject = subject)
    schemas.create(projectRef, schemaSourceWithId(mySchema4)).assertEquals(expected)
  }

  test("Refreshing a schema succeeds") {
    val expected = SchemaGen.resourceFor(schema4, rev = 2, subject = subject)
    schemas.refresh(mySchema4, projectRef).assertEquals(expected)
  }

  test("Refreshing a schema fails if it does not exist") {
    schemas.refresh(nxv + "other", projectRef).intercept[SchemaNotFound]
  }

  test("Refreshing a schema fails if deprecated") {
    schemas.refresh(mySchema3, projectRef).intercept[SchemaIsDeprecated]
  }

  test("Refreshing a schema fails if project does not exist") {
    val projectRef = ProjectRef(org, Label.unsafe("other"))
    schemas.refresh(mySchema4, projectRef).intercept[ProjectContextRejection]
  }

  test("Refreshing a schema fails if project is deprecated") {
    schemas.refresh(mySchema4, projectDeprecated.ref).intercept[ProjectContextRejection]
  }

  test("Tagging a schema succeeds") {
    val schema   = SchemaGen.schema(mySchema2, project.ref, schemaSourceWithId(mySchema2), tags = Tags(tag -> 1))
    val expected = SchemaGen.resourceFor(schema, subject = subject, rev = 2)
    schemas.tag(mySchema2, projectRef, tag, 1, 1).assertEquals(expected)
  }

  test("Tagging a schema succeeds if deprecated") {
    val schema   = SchemaGen
      .schema(mySchema3, project.ref, schemaSourceWithId(mySchema3), Tags(tag -> 2))
      .copy(source = sourceNoId)
    val expected = SchemaGen.resourceFor(schema, subject = subject, rev = 3, deprecated = true)
    schemas.tag(mySchema3, projectRef, tag, 2, 2).assertEquals(expected)
  }

  test("Tagging a schema fails if it doesn't exist") {
    schemas.tag(nxv + "other", projectRef, tag, 1, 1).intercept[SchemaNotFound]
  }

  test("Tagging a schema fails  if the revision passed is incorrect") {
    schemas.tag(mySchema, projectRef, tag, 1, 3).intercept(IncorrectRev(provided = 3, expected = 2))
  }

  test("Tagging a schema fails  if the tag revision is not found") {
    schemas.tag(mySchema, projectRef, tag, 6, 2).intercept(RevisionNotFound(provided = 6, current = 2))
  }

  test("Tagging a schema fails  if project does not exist") {
    val projectRef = ProjectRef(org, Label.unsafe("other"))
    schemas.tag(mySchema, projectRef, tag, 2, 1).intercept[ProjectContextRejection]
  }

  test("Tagging a schema fails  if project is deprecated") {
    schemas.tag(mySchema, projectDeprecated.ref, tag, 2, 2).intercept[ProjectContextRejection]
  }

  test("Deprecating a schema succeeds") {
    val expected = SchemaGen.resourceFor(schemaUpdated, subject = subject, rev = 3, deprecated = true)
    schemas.deprecate(mySchema, projectRef, 2).assertEquals(expected)
  }

  test("Deprecating a schema fails if it doesn't exists") {
    schemas.deprecate(nxv + "other", projectRef, 1).intercept[SchemaNotFound]
  }

  test("Deprecating a schema fails if the revision passed is incorrect") {
    schemas.deprecate(mySchema, projectRef, 5).intercept(IncorrectRev(provided = 5, expected = 3))
  }

  test("Deprecating a schema fails if deprecated") {
    schemas.deprecate(mySchema, projectRef, 3).intercept[SchemaIsDeprecated]
  }

  test("Deprecating a schema fails if project does not exist") {
    val projectRef = ProjectRef(org, Label.unsafe("other"))

    schemas.deprecate(mySchema, projectRef, 1).intercept[ProjectContextRejection]
  }

  test("Deprecating a schema fails if project is deprecated") {
    schemas.deprecate(mySchema, projectDeprecated.ref, 1).intercept[ProjectContextRejection]
  }

  private val schema2 = SchemaGen.schema(mySchema2, project.ref, schemaSourceWithId(mySchema2))

  test("Fetching a schema succeeds") {
    val expected = SchemaGen.resourceFor(schemaUpdated, rev = 3, deprecated = true, subject = subject)
    schemas.fetch(mySchema, projectRef).assertEquals(expected)
  }

  test("Fetching a schema succeeds by tag") {
    val expected = SchemaGen.resourceFor(schema2, subject = subject)
    schemas.fetch(IdSegmentRef(mySchema2, tag), projectRef).assertEquals(expected)
  }

  test("Fetching a schema succeeds by rev") {
    val expected = SchemaGen.resourceFor(schema2, subject = subject)
    schemas.fetch(IdSegmentRef(mySchema2, 1), projectRef).assertEquals(expected)
  }

  test("Fetching a schema fails if tag does not exist") {
    val otherTag = UserTag.unsafe("other")
    schemas.fetch(IdSegmentRef(mySchema, otherTag), projectRef).intercept(TagNotFound(otherTag))
  }

  test("Fetching a schema fails if revision does not exist") {
    schemas.fetch(IdSegmentRef(mySchema, 5), projectRef).intercept(RevisionNotFound(provided = 5, current = 3))
  }

  test("Fetching a schema fails if schema does not exist") {
    val mySchema = nxv + "notFound"
    for {
      _ <- schemas.fetch(mySchema, projectRef).intercept[SchemaNotFound]
      _ <- schemas.fetch(IdSegmentRef(mySchema, tag), projectRef).intercept[SchemaNotFound]
      _ <- schemas.fetch(IdSegmentRef(mySchema, 2), projectRef).intercept[SchemaNotFound]
    } yield ()
  }

  test("Fetching a schema fails  if project does not exist") {
    val projectRef = ProjectRef(org, Label.unsafe("other"))
    schemas.fetch(mySchema, projectRef).intercept[ProjectContextRejection]
  }

  test("Deleting a schema tag succeeds") {
    val sourceWithId = schemaSourceWithId(mySchema2)
    val schema       = SchemaGen.schema(mySchema2, project.ref, sourceWithId)
    val expected     = SchemaGen.resourceFor(schema, subject = subject, rev = 3)
    schemas.deleteTag(mySchema2, projectRef, tag, 2).assertEquals(expected)
  }

  test("Deleting a schema tag fails if the schema doesn't exist") {
    schemas.deleteTag(nxv + "other", projectRef, tag, 1).intercept[SchemaNotFound]
  }

  test("Deleting a schema tag fails if the revision passed is incorrect") {
    schemas.deleteTag(mySchema2, projectRef, tag, 2).intercept(IncorrectRev(provided = 2, expected = 3))
  }

  test("Deleting a schema tag fails if the tag doesn't exist") {
    schemas.deleteTag(mySchema2, projectRef, tag, 3).intercept[TagNotFound]
  }

}
