package ch.epfl.bluebrain.nexus.delta.sdk.schemas

import ch.epfl.bluebrain.nexus.delta.kernel.utils.UUIDF
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.{contexts, nxv}
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.api.{JsonLdApi, JsonLdJavaApi}
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.JsonLdContext.keywords
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.{ContextValue, RemoteContextResolution}
import ch.epfl.bluebrain.nexus.delta.sdk.ConfigFixtures
import ch.epfl.bluebrain.nexus.delta.sdk.generators.{ProjectGen, SchemaGen}
import ch.epfl.bluebrain.nexus.delta.sdk.identities.model.Caller
import ch.epfl.bluebrain.nexus.delta.sdk.model.resolvers.{ResolverContextResolution, ResourceResolutionReport}
import ch.epfl.bluebrain.nexus.delta.sdk.model.{IdSegmentRef, Tags}
import ch.epfl.bluebrain.nexus.delta.sdk.projects.FetchContextDummy
import ch.epfl.bluebrain.nexus.delta.sdk.projects.model.ApiMappings
import ch.epfl.bluebrain.nexus.delta.sdk.schemas.model.SchemaRejection.{IncorrectRev, InvalidSchema, ProjectContextRejection, ResourceAlreadyExists, RevisionNotFound, SchemaIsDeprecated, SchemaNotFound, TagNotFound, UnexpectedSchemaId}
import ch.epfl.bluebrain.nexus.delta.sdk.syntax._
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.Subject
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Tag.UserTag
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{Identity, Label, ProjectRef}
import ch.epfl.bluebrain.nexus.testkit.{CirceLiteral, DoobieScalaTestFixture, IOFixedClock, IOValues}
import monix.bio.IO
import monix.execution.Scheduler
import org.scalatest.matchers.should.Matchers
import org.scalatest.{CancelAfterFailure, OptionValues}

import java.util.UUID

class SchemasImplSpec
    extends DoobieScalaTestFixture
    with Matchers
    with IOValues
    with IOFixedClock
    with CancelAfterFailure
    with CirceLiteral
    with OptionValues
    with ConfigFixtures {

  implicit private val subject: Subject = Identity.User("user", Label.unsafe("realm"))
  implicit private val caller: Caller   = Caller(subject, Set(subject))

  implicit private val scheduler: Scheduler = Scheduler.global

  private val uuid                     = UUID.randomUUID()
  implicit private val uuidF: UUIDF    = UUIDF.fixed(uuid)
  implicit private val cl: ClassLoader = getClass.getClassLoader

  implicit private val api: JsonLdApi = JsonLdJavaApi.lenient

  implicit def res: RemoteContextResolution =
    RemoteContextResolution.fixed(
      contexts.shacl           -> ContextValue.fromFile("contexts/shacl.json").accepted,
      contexts.schemasMetadata -> ContextValue.fromFile("contexts/schemas-metadata.json").accepted
    )

  private val schemaImports: SchemaImports = new SchemaImports(
    (_, _, _) => IO.raiseError(ResourceResolutionReport()),
    (_, _, _) => IO.raiseError(ResourceResolutionReport())
  )

  private val resolverContextResolution: ResolverContextResolution = new ResolverContextResolution(
    res,
    (_, _, _) => IO.raiseError(ResourceResolutionReport())
  )

  private val org               = Label.unsafe("myorg")
  private val am                = ApiMappings("nxv" -> nxv.base)
  private val projBase          = nxv.base
  private val project           = ProjectGen.project("myorg", "myproject", base = projBase, mappings = am)
  private val projectDeprecated = ProjectGen.project("myorg", "myproject2")
  private val projectRef        = project.ref
  private val mySchema          = nxv + "myschema"  // Create with id present in payload
  private val mySchema2         = nxv + "myschema2" // Create with id present in payload and passed
  private val mySchema3         = nxv + "myschema3" // Create with id passed
  private val source            = jsonContentOf("resources/schema.json").addContext(contexts.shacl, contexts.schemasMetadata)
  private val sourceNoId        = source.removeKeys(keywords.id)
  private val schema            = SchemaGen.schema(mySchema, project.ref, source)
  private val sourceUpdated     = sourceNoId.replace("datatype" -> "xsd:integer", "xsd:double")
  private val schemaUpdated     = SchemaGen.schema(mySchema, project.ref, sourceUpdated)

  private val fetchContext =
    FetchContextDummy(Map(project.ref -> project.context), Set(projectDeprecated.ref), ProjectContextRejection)
  private val config       = SchemasConfig(eventLogConfig)

  private lazy val schemas: Schemas = SchemasImpl(fetchContext, schemaImports, resolverContextResolution, config, xas)

  "The Schemas operations bundle" when {

    val tag = UserTag.unsafe("tag")

    "creating a schema" should {

      "succeed with the id present on the payload" in {
        schemas.create(projectRef, source).accepted shouldEqual
          SchemaGen.resourceFor(schema, subject = subject, am = am, base = projBase)
      }

      "succeed with the id present on the payload and passed" in {
        val sourceWithId = source deepMerge json"""{"@id": "$mySchema2"}"""
        val schema       = SchemaGen.schema(mySchema2, project.ref, sourceWithId)
        schemas.create("myschema2", projectRef, sourceWithId).accepted shouldEqual
          SchemaGen.resourceFor(schema, subject = subject, am = am, base = projBase)
      }

      "succeed with the passed id" in {
        val sourceWithId = source deepMerge json"""{"@id": "$mySchema3"}"""
        val schema       = SchemaGen.schema(mySchema3, project.ref, sourceWithId).copy(source = sourceNoId)
        schemas.create(mySchema3, projectRef, sourceNoId).accepted shouldEqual
          SchemaGen.resourceFor(schema, subject = subject, am = am, base = projBase)
      }

      "reject with different ids on the payload and passed" in {
        val otherId = nxv + "other"
        schemas.create(otherId, projectRef, source).rejected shouldEqual
          UnexpectedSchemaId(id = otherId, payloadId = mySchema)
      }

      "reject if it already exists" in {
        schemas.create(mySchema, projectRef, source).rejected shouldEqual
          ResourceAlreadyExists(mySchema, projectRef)
      }

      "reject if it does not validate against the SHACL schema" in {
        val otherId     = nxv + "other"
        val wrongSource = sourceNoId.replace("minCount" -> 1, "wrong")
        schemas.create(otherId, projectRef, wrongSource).rejectedWith[InvalidSchema]
      }

      "reject if project does not exist" in {
        val projectRef = ProjectRef(org, Label.unsafe("other"))
        schemas.create(projectRef, sourceNoId).rejectedWith[ProjectContextRejection]
      }

      "reject if project is deprecated" in {
        schemas.create(projectDeprecated.ref, sourceNoId).rejectedWith[ProjectContextRejection]
      }
    }

    "updating a schema" should {

      "succeed" in {
        schemas.update(mySchema, projectRef, 1, sourceUpdated).accepted shouldEqual
          SchemaGen.resourceFor(schemaUpdated, rev = 2, subject = subject, am = am, base = projBase)
      }

      "reject if it doesn't exists" in {
        schemas.update(nxv + "other", projectRef, 1, json"""{"a": "b"}""").rejectedWith[SchemaNotFound]
      }

      "reject if the revision passed is incorrect" in {
        schemas.update(mySchema, projectRef, 3, json"""{"a": "b"}""").rejected shouldEqual
          IncorrectRev(provided = 3, expected = 2)
      }

      "reject if deprecated" in {
        schemas.deprecate(mySchema3, projectRef, 1).accepted
        schemas.update(mySchema3, projectRef, 2, json"""{"a": "b"}""").rejectedWith[SchemaIsDeprecated]
      }

      "reject if it does not validate against its schema" in {
        val wrongSource = sourceNoId.replace("minCount" -> 1, "wrong")
        schemas.update(mySchema, projectRef, 2, wrongSource).rejectedWith[InvalidSchema]
      }

      "reject if project does not exist" in {
        val projectRef = ProjectRef(org, Label.unsafe("other"))

        schemas.update(mySchema, projectRef, 2, source).rejectedWith[ProjectContextRejection]
      }

      "reject if project is deprecated" in {
        schemas.update(mySchema, projectDeprecated.ref, 2, source).rejectedWith[ProjectContextRejection]
      }
    }

    "tagging a schema" should {

      "succeed" in {
        val sourceWithId = source deepMerge json"""{"@id": "$mySchema2"}"""
        val schema       = SchemaGen.schema(mySchema2, project.ref, sourceWithId, tags = Tags(tag -> 1))

        schemas.tag(mySchema2, projectRef, tag, 1, 1).accepted shouldEqual
          SchemaGen.resourceFor(schema, subject = subject, rev = 2, am = am, base = projBase)
      }

      "reject if it doesn't exists" in {
        schemas.tag(nxv + "other", projectRef, tag, 1, 1).rejectedWith[SchemaNotFound]
      }

      "reject if the revision passed is incorrect" in {
        schemas.tag(mySchema, projectRef, tag, 1, 3).rejected shouldEqual
          IncorrectRev(provided = 3, expected = 2)
      }

      "succeed if deprecated" in {
        val sourceWithId = source deepMerge json"""{"@id": "$mySchema3"}"""
        val schema       = SchemaGen.schema(mySchema3, project.ref, sourceWithId, Tags(tag -> 2)).copy(source = sourceNoId)
        schemas.tag(mySchema3, projectRef, tag, 2, 2).accepted shouldEqual
          SchemaGen.resourceFor(
            schema,
            subject = subject,
            rev = 3,
            am = am,
            base = projBase,
            deprecated = true
          )
      }

      "reject if tag revision not found" in {
        schemas.tag(mySchema, projectRef, tag, 6, 2).rejected shouldEqual
          RevisionNotFound(provided = 6, current = 2)
      }

      "reject if project does not exist" in {
        val projectRef = ProjectRef(org, Label.unsafe("other"))

        schemas.tag(mySchema, projectRef, tag, 2, 1).rejectedWith[ProjectContextRejection]
      }

      "reject if project is deprecated" in {
        schemas.tag(mySchema, projectDeprecated.ref, tag, 2, 2).rejectedWith[ProjectContextRejection]
      }
    }

    "deprecating a schema" should {

      "succeed" in {
        schemas.deprecate(mySchema, projectRef, 2).accepted shouldEqual
          SchemaGen.resourceFor(schemaUpdated, subject = subject, rev = 3, deprecated = true, am = am, base = projBase)
      }

      "reject if it doesn't exists" in {
        schemas.deprecate(nxv + "other", projectRef, 1).rejectedWith[SchemaNotFound]
      }

      "reject if the revision passed is incorrect" in {
        schemas.deprecate(mySchema, projectRef, 5).rejected shouldEqual
          IncorrectRev(provided = 5, expected = 3)
      }

      "reject if deprecated" in {
        schemas.deprecate(mySchema, projectRef, 3).rejectedWith[SchemaIsDeprecated]
      }

      "reject if project does not exist" in {
        val projectRef = ProjectRef(org, Label.unsafe("other"))

        schemas.deprecate(mySchema, projectRef, 1).rejectedWith[ProjectContextRejection]
      }

      "reject if project is deprecated" in {
        schemas.deprecate(mySchema, projectDeprecated.ref, 1).rejectedWith[ProjectContextRejection]
      }
    }

    "fetching a schema" should {
      val schema2 = SchemaGen.schema(mySchema2, project.ref, source deepMerge json"""{"@id": "$mySchema2"}""")

      "succeed" in {
        schemas.fetch(mySchema, projectRef).accepted shouldEqual
          SchemaGen.resourceFor(schemaUpdated, rev = 3, deprecated = true, subject = subject, am = am, base = projBase)
      }

      "succeed by tag" in {
        schemas.fetch(IdSegmentRef(mySchema2, tag), projectRef).accepted shouldEqual
          SchemaGen.resourceFor(schema2, subject = subject, am = am, base = projBase)
      }

      "succeed by rev" in {
        schemas.fetch(IdSegmentRef(mySchema2, 1), projectRef).accepted shouldEqual
          SchemaGen.resourceFor(schema2, subject = subject, am = am, base = projBase)
      }

      "reject if tag does not exist" in {
        val otherTag = UserTag.unsafe("other")
        schemas.fetch(IdSegmentRef(mySchema, otherTag), projectRef).rejected shouldEqual TagNotFound(otherTag)
      }

      "reject if revision does not exist" in {
        schemas.fetch(IdSegmentRef(mySchema, 5), projectRef).rejected shouldEqual
          RevisionNotFound(provided = 5, current = 3)
      }

      "fail fetching if schema does not exist" in {
        val mySchema = nxv + "notFound"
        schemas.fetch(mySchema, projectRef).rejectedWith[SchemaNotFound]
        schemas.fetch(IdSegmentRef(mySchema, tag), projectRef).rejectedWith[SchemaNotFound]
        schemas.fetch(IdSegmentRef(mySchema, 2), projectRef).rejectedWith[SchemaNotFound]
      }

      "reject if project does not exist" in {
        val projectRef = ProjectRef(org, Label.unsafe("other"))

        schemas.fetch(mySchema, projectRef).rejectedWith[ProjectContextRejection]
      }
    }

    "deleting a schema tag" should {
      "succeed" in {
        val sourceWithId = source deepMerge json"""{"@id": "$mySchema2"}"""
        val schema       = SchemaGen.schema(mySchema2, project.ref, sourceWithId)
        schemas.deleteTag(mySchema2, projectRef, tag, 2).accepted shouldEqual
          SchemaGen.resourceFor(schema, subject = subject, rev = 3, am = am, base = projBase)
      }
      "reject if the schema doesn't exist" in {
        schemas.deleteTag(nxv + "other", projectRef, tag, 1).rejectedWith[SchemaNotFound]
      }
      "reject if the revision passed is incorrect" in {
        schemas.deleteTag(mySchema2, projectRef, tag, 2).rejected shouldEqual IncorrectRev(
          provided = 2,
          expected = 3
        )
      }

      "reject if the tag doesn't exist" in {
        schemas.deleteTag(mySchema2, projectRef, tag, 3).rejectedWith[TagNotFound]
      }
    }
  }

}
