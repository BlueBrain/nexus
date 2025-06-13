package ai.senscience.nexus.tests.resources

import ai.senscience.nexus.tests.Identity.resources.Rick
import ai.senscience.nexus.tests.Optics._rev
import ai.senscience.nexus.tests.Optics.admin._constrainedBy
import ai.senscience.nexus.tests.admin.ProjectPayload
import ai.senscience.nexus.tests.{BaseIntegrationSpec, Optics, SchemaPayload}
import akka.http.scaladsl.model.{HttpResponse, StatusCodes}
import ch.epfl.bluebrain.nexus.delta.kernel.utils.UrlUtils.encodeUriPath
import io.circe.Json

class EnforcedSchemaSpec extends BaseIntegrationSpec {

  private val orgId   = genId()
  private val projId  = genId()
  private val project = s"$orgId/$projId"

  private val unconstrainedSchema = "https://bluebrain.github.io/nexus/schemas/unconstrained.json"

  private val testSchema = "https://dev.nexus.test.com/test-schema"
  private val newSchema  = "https://dev.nexus.test.com/new-schema"

  private val sameSchema = "_"

  private val enforcedSchemaPayload =
    ProjectPayload.generateWithCustomBase(project, "https://dev.nexus.test.com/", enforceSchema = true)

  override def beforeAll(): Unit = {
    super.beforeAll()
    val setup = for {
      _             <- createOrg(Rick, orgId)
      _             <- adminDsl.createProject(orgId, projId, enforcedSchemaPayload, Rick)
      // Create schemas
      schemaPayload <- SchemaPayload.loadSimpleNoId()
      _             <- deltaClient.put[Json](s"/schemas/$project/test-schema", schemaPayload, Rick) { expectCreated }
      _             <- deltaClient.put[Json](s"/schemas/$project/new-schema", schemaPayload, Rick) { expectCreated }
    } yield ()
    setup.accepted
  }

  private def createValidatedResource = {
    val id = genId()
    for {
      payload <- SimpleResource.sourcePayload(id, 42)
      _       <- deltaClient.post[Json](s"/resources/$project/test-schema/", payload, Rick) {
                   expectCreated
                 }
    } yield id
  }

  private def createUnconstrainedResource = {
    val id                    = genId()
    val optionalSchemaPayload = enforcedSchemaPayload.copy(enforceSchema = false)
    for {
      revOpt  <- deltaClient.getJson[Json](s"/projects/$project", Rick).map(Optics._rev.getOption)
      rev      = revOpt.value
      _       <- adminDsl.updateProject(orgId, projId, optionalSchemaPayload, Rick, rev)
      payload <- SimpleResource.sourcePayload(id, 42)
      _       <- deltaClient.post[Json](s"/resources/$project/_/", payload, Rick) { expectCreated }
      _       <- adminDsl.updateProject(orgId, projId, enforcedSchemaPayload, Rick, rev + 1)
    } yield id
  }

  private def failOnMissingSchema(json: Json, response: HttpResponse) = {
    response.status shouldEqual StatusCodes.BadRequest
    json should have(`@type`("SchemaIsMandatory"))
  }

  private def expectRevAndSchema(rev: Int, schema: String)(json: Json, response: HttpResponse) = {
    assert(response.status.isSuccess())
    _rev.getOption(json).value shouldEqual rev
    _constrainedBy.getOption(json) should contain(schema)
  }

  private def updateResource(id: String, schema: String, payload: Json, rev: Int = 1) =
    deltaClient.put[Json](s"/resources/$project/${encodeUriPath(schema)}/$id?rev=$rev", payload, Rick)(_)

  private def updateResourceSchema(id: String, schema: String) =
    deltaClient.put[Json](s"/resources/$project/${encodeUriPath(schema)}/$id/update-schema", Json.Null, Rick)(_)

  private val contextPayload = json"""{ "@context": { "@base": "http://example.com/base/" } }"""

  private val whitelistedResourcePayload = json"""{
                "@context": { "schema" : "http://schema.org/" },
                 "@type": ["schema:Book", "schema:Chapter"],
                 "schema:headline": "1984"
              }"""

  "Creating a resource" should {
    "fail if no schema is provided for a non-whitelisted resource" in {
      for {
        payload <- SimpleResource.sourcePayload("xxx", 5)
        _       <- deltaClient.post[Json](s"/resources/$project/_/", payload, Rick) { failOnMissingSchema }
      } yield succeed
    }

    "succeed if a schema is provided" in {
      for {
        payload <- SimpleResource.sourcePayload("xxx", 5)
        _       <- deltaClient.post[Json](s"/resources/$project/test-schema/", payload, Rick) {
                     expectRevAndSchema(1, testSchema)
                   }
      } yield succeed
    }

    "succeed for a context (aka a resource with no type)" in {
      deltaClient.post[Json](s"/resources/$project/_/", contextPayload, Rick) { expectCreated }
    }

    "succeed for a resource where all types are whitelisted" in {
      deltaClient.post[Json](s"/resources/$project/_/", whitelistedResourcePayload, Rick) { expectCreated }
    }

    "fails for a resource where a type is not whitelisted" in {
      val payload =
        json"""{
                "@context": { "schema" : "http://schema.org/" },
                 "@type": ["schema:Book", "schema:ComicStory"],
                 "schema:headline": "1984"
              }"""
      deltaClient.post[Json](s"/resources/$project/_/", payload, Rick) { failOnMissingSchema }
    }
  }

  "Updating a validated resource" should {

    "fail on an attempt to set it as unconstrained" in {
      for {
        id             <- createValidatedResource
        updatedPayload <- SimpleResource.sourcePayload(id, 5)
        _              <- updateResource(id, unconstrainedSchema, updatedPayload) { failOnMissingSchema }
      } yield succeed
    }

    "succeed when setting it as unconstrained for a whitelisted type" in {
      for {
        id <- createValidatedResource
        _  <- updateResource(id, unconstrainedSchema, whitelistedResourcePayload) { expectOk }
      } yield succeed
    }

    "succeed when setting it as unconstrained for a context" in {
      for {
        id <- createValidatedResource
        _  <- updateResource(id, unconstrainedSchema, contextPayload) { expectOk }
      } yield succeed
    }

    "succeed when keeping the same schema" in {
      for {
        id             <- createValidatedResource
        updatedPayload <- SimpleResource.sourcePayload(id, 5)
        _              <- updateResource(id, sameSchema, updatedPayload) { expectRevAndSchema(2, testSchema) }
      } yield succeed
    }

    "succeed when providing a new schema" in {
      for {
        id             <- createValidatedResource
        updatedPayload <- SimpleResource.sourcePayload(id, 5)
        _              <- updateResource(id, "new-schema", updatedPayload) { expectRevAndSchema(2, newSchema) }
      } yield succeed
    }

    "fail to update the schema to unconstrained with the update-schema endpoint" in {
      for {
        id <- createValidatedResource
        _  <- updateResourceSchema(id, unconstrainedSchema) { failOnMissingSchema }
      } yield succeed
    }

    "succeed to update to another schema with the update-schema endpoint" in {
      for {
        id <- createValidatedResource
        _  <- updateResourceSchema(id, newSchema) { expectRevAndSchema(2, newSchema) }
      } yield succeed
    }
  }

  "Updating an unconstrained resource" should {
    "succeed with the unconstrained schema" in {
      for {
        id             <- createUnconstrainedResource
        updatedPayload <- SimpleResource.sourcePayload(id, 5)
        _              <- updateResource(id, unconstrainedSchema, updatedPayload) { expectRevAndSchema(2, unconstrainedSchema) }
      } yield succeed
    }

    "succeed when keeping the unconstrained schema" in {
      for {
        id             <- createUnconstrainedResource
        updatedPayload <- SimpleResource.sourcePayload(id, 5)
        _              <- updateResource(id, sameSchema, updatedPayload) { expectRevAndSchema(2, unconstrainedSchema) }
      } yield succeed
    }

    "succeed when providing a new schema and then fail on an attempt to go back to unconstrained" in {
      for {
        id             <- createUnconstrainedResource
        updatedPayload <- SimpleResource.sourcePayload(id, 5)
        _              <- updateResource(id, newSchema, updatedPayload) { expectRevAndSchema(2, newSchema) }
        _              <- updateResource(id, unconstrainedSchema, updatedPayload, rev = 2) { failOnMissingSchema }
        _              <- updateResourceSchema(id, unconstrainedSchema) { failOnMissingSchema }
      } yield succeed
    }

    "succeed to keep the unconstrained schema with the update-schema endpoint" in {
      for {
        id <- createUnconstrainedResource
        _  <- updateResourceSchema(id, unconstrainedSchema) { expectRevAndSchema(2, unconstrainedSchema) }
      } yield succeed
    }
  }
}
