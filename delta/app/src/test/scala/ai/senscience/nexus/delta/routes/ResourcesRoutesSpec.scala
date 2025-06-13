package ai.senscience.nexus.delta.routes

import akka.http.scaladsl.model.MediaTypes.`text/html`
import akka.http.scaladsl.model.headers.{Accept, Location, RawHeader}
import akka.http.scaladsl.model.{RequestEntity, StatusCodes, Uri}
import akka.http.scaladsl.server.Route
import cats.syntax.all.*
import ch.epfl.bluebrain.nexus.delta.kernel.utils.UUIDF
import ch.epfl.bluebrain.nexus.delta.kernel.utils.UrlUtils.encodeUriPath
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.{contexts, nxv, schema as schemaOrg, schemas}
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.JsonLdContext.keywords
import ch.epfl.bluebrain.nexus.delta.sdk.IndexingAction
import ch.epfl.bluebrain.nexus.delta.sdk.acls.AclSimpleCheck
import ch.epfl.bluebrain.nexus.delta.sdk.acls.model.AclAddress
import ch.epfl.bluebrain.nexus.delta.sdk.generators.ProjectGen
import ch.epfl.bluebrain.nexus.delta.sdk.identities.IdentitiesDummy
import ch.epfl.bluebrain.nexus.delta.sdk.implicits.*
import ch.epfl.bluebrain.nexus.delta.sdk.model.{IdSegmentRef, ResourceAccess}
import ch.epfl.bluebrain.nexus.delta.sdk.permissions.Permissions.resources.{delete, read, write}
import ch.epfl.bluebrain.nexus.delta.sdk.projects.FetchContextDummy
import ch.epfl.bluebrain.nexus.delta.sdk.projects.model.ApiMappings
import ch.epfl.bluebrain.nexus.delta.sdk.resolvers.ResolverContextResolution
import ch.epfl.bluebrain.nexus.delta.sdk.resources.*
import ch.epfl.bluebrain.nexus.delta.sdk.utils.BaseRouteSpec
import ch.epfl.bluebrain.nexus.delta.sourcing.ScopedEventLog
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.{Subject, User}
import ch.epfl.bluebrain.nexus.delta.sourcing.model.ProjectRef
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Tag.UserTag
import ch.epfl.bluebrain.nexus.testkit.scalatest.ce.CatsIOValues
import io.circe.{Json, Printer}
import org.scalatest.Assertion

import java.util.UUID

class ResourcesRoutesSpec extends BaseRouteSpec with ValidateResourceFixture with CatsIOValues {

  private val uuid                  = UUID.randomUUID()
  implicit private val uuidF: UUIDF = UUIDF.fixed(uuid)

  private val reader  = User("reader", realm)
  private val writer  = User("writer", realm)
  private val deleter = User("deleter", realm)

  private val am         = ApiMappings("nxv" -> nxv.base, "Person" -> schemaOrg.Person)
  private val projBase   = nxv.base
  private val project    = ProjectGen.resourceFor(
    ProjectGen.project(
      "myorg",
      "myproject",
      uuid = uuid,
      orgUuid = uuid,
      base = projBase,
      mappings = am + Resources.mappings
    )
  )
  private val projectRef = project.value.ref
  private val schema1    = nxv + "myschema"
  private val schema2    = nxv + "otherSchema"
  private val tag        = UserTag.unsafe("mytag")

  private val myId                            = nxv + "myid" // Resource created against no schema with id present on the payload
  private def encodeWithBase(id: String)      = encodeUriPath((nxv + id).toString)
  private val payload                         = jsonContentOf("resources/resource.json", "id" -> myId)
  private def simplePayload(id: String)       = jsonContentOf("resources/resource.json", "id" -> (nxv + id))
  private val payloadWithoutId                = payload.removeKeys(keywords.id)
  private val payloadWithBlankId              = jsonContentOf("resources/resource.json", "id" -> "")
  private def payloadWithMetadata(id: String) = jsonContentOf(
    "resources/resource-with-metadata.json",
    "id"   -> (nxv + id),
    "self" -> ResourceAccess.resource(projectRef, nxv + id).uri
  )

  private val aclCheck = AclSimpleCheck.unsafe(
    (reader, AclAddress.Root, Set(read)),
    (writer, AclAddress.Root, Set(read, write)),
    (deleter, AclAddress.Root, Set(read, delete))
  )

  private val validateResource                                     = validateFor(Set((projectRef, schema1), (projectRef, schema2)))
  private val fetchContext                                         = FetchContextDummy(List(project.value))
  private val resolverContextResolution: ResolverContextResolution = ResolverContextResolution(rcr)

  private lazy val resources = {
    val resourceDef = Resources.definition(validateResource, DetectChange(enabled = true), clock)
    val scopedLog   = ScopedEventLog(resourceDef, eventLogConfig, xas)

    ResourcesImpl(
      scopedLog,
      fetchContext,
      resolverContextResolution
    )
  }

  private lazy val routes = Route.seal(
    ResourcesRoutes(
      IdentitiesDummy.fromUsers(reader, writer, deleter),
      aclCheck,
      resources,
      IndexingAction.noop
    )
  )

  private val payloadUpdated             = payload deepMerge json"""{"name": "Alice", "address": null}"""
  private def payloadUpdated(id: String) =
    simplePayload(id) deepMerge json"""{"name": "Alice", "address": null , "uuid": "${UUID.randomUUID()}"}"""

  private val varyHeader = RawHeader("Vary", "Accept,Accept-Encoding")

  private val resourceCtx =
    json"""{"@context": ["${contexts.metadata}", {"@vocab": "${nxv.base}", "schema" : "${schemaOrg.base}"}]}"""

  "A resource route" should {

    "fail to create a resource without resources/write permission" in {
      Post("/v1/resources/myorg/myproject", payload.toEntity) ~> as(reader) ~> routes ~> check {
        response.shouldBeForbidden
      }
    }

    "create a resource" in {
      val endpoints = List(
        ("/v1/resources/myorg/myproject", schemas.resources),
        ("/v1/resources/myorg/myproject/myschema", schema1)
      )
      forAll(endpoints) { case (endpoint, schema) =>
        val id = genString()
        Post(endpoint, simplePayload(id).toEntity) ~> as(writer) ~> routes ~> check {
          status shouldEqual StatusCodes.Created
          response.asJson shouldEqual standardWriterMetadata(id, schema = schema)
        }
      }
    }

    "create a tagged resource" in {
      val endpoints = List(
        ("/v1/resources/myorg/myproject?tag=mytag", schemas.resources),
        ("/v1/resources/myorg/myproject/myschema?tag=mytag", schema1)
      )
      forAll(endpoints) { case (endpoint, schema) =>
        val id = genString()
        Post(endpoint, simplePayload(id).toEntity) ~> as(writer) ~> routes ~> check {
          status shouldEqual StatusCodes.Created
          response.asJson shouldEqual standardWriterMetadata(id, schema = schema)
          lookupResourceByTag(resources, nxv + id) should contain(tag)
        }
      }
    }

    "create a resource with an authenticated user and provided id" in {
      val endpoints = List(
        ((id: String) => s"/v1/resources/myorg/myproject/_/$id", schemas.resources),
        ((id: String) => s"/v1/resources/myorg/myproject/myschema/$id", schema1)
      )
      forAll(endpoints) { case (endpoint, schema) =>
        val id = genString()
        Put(endpoint(id), simplePayload(id).toEntity) ~> as(writer) ~> routes ~> check {
          status shouldEqual StatusCodes.Created
          response.asJson shouldEqual standardWriterMetadata(id, schema = schema)
        }
      }
    }

    "create a tagged resource with an authenticated user and provided id" in {
      val endpoints = List(
        ((id: String) => s"/v1/resources/myorg/myproject/_/$id?tag=mytag", schemas.resources),
        ((id: String) => s"/v1/resources/myorg/myproject/myschema/$id?tag=mytag", schema1)
      )
      forAll(endpoints) { case (endpoint, schema) =>
        val id = genString()
        Put(endpoint(id), simplePayload(id).toEntity) ~> as(writer) ~> routes ~> check {
          status shouldEqual StatusCodes.Created
          response.asJson shouldEqual standardWriterMetadata(id, schema = schema)
          lookupResourceByTag(resources, nxv + id) should contain(tag)
        }
      }
    }

    def lookupResourceByTag(resources: Resources, id: Iri): List[UserTag] =
      resources.fetch(IdSegmentRef(id, tag), projectRef, None).accepted.value.tags.value.keys.toList

    "reject the creation of a resource which already exists" in {
      givenAResource { id =>
        Put(s"/v1/resources/myorg/myproject/_/$id", simplePayload(id).toEntity) ~> as(writer) ~> routes ~> check {
          status shouldEqual StatusCodes.Conflict
          response.asJson shouldEqual
            jsonContentOf("resources/errors/already-exists.json", "id" -> (nxv + id), "project" -> "myorg/myproject")
        }
      }
    }

    "fail to create a resource against a schema that does not exist" in {
      Put(
        "/v1/resources/myorg/myproject/pretendschema/someid",
        payloadWithoutId.toEntity
      ) ~> as(writer) ~> routes ~> check {
        status shouldEqual StatusCodes.NotFound
        response.asJson shouldEqual jsonContentOf("schemas/errors/invalid-schema-2.json")
      }
    }

    "fail if the id is blank" in {
      Post("/v1/resources/myorg/myproject/_/", payloadWithBlankId.toEntity) ~> as(writer) ~> routes ~> check {
        response.status shouldEqual StatusCodes.BadRequest
        response.asJson.hcursor.get[String]("@type").toOption should contain("BlankId")
      }
    }

    "fail if underscore fields are present" in {
      val payloadWithUnderscores = json"""{ "_createdAt": "1970-01-01T00:00:00Z" }"""

      Post("/v1/resources/myorg/myproject/_/", payloadWithUnderscores.toEntity) ~> as(writer) ~> routes ~> check {
        response.status shouldEqual StatusCodes.BadRequest
        response.asJson shouldEqual jsonContentOf(
          "resources/errors/underscore-fields.json"
        )
      }
    }

    "fail to update a resource without resources/write permission" in {
      givenAResource { id =>
        Put(s"/v1/resources/myorg/myproject/_/$id?rev=1", payload.toEntity) ~> as(reader) ~> routes ~> check {
          response.shouldBeForbidden
        }
      }
    }

    "update a resource" in {
      val schema = "myschema"
      givenAResourceWithSchema(schema) { id =>
        val endpoints = List(
          s"/v1/resources/myorg/myproject/_/$id"                         -> 1,
          s"/v1/resources/myorg/myproject/_/${encodeWithBase(id)}"       -> 2,
          s"/v1/resources/myorg/myproject/$schema/$id"                   -> 3,
          s"/v1/resources/myorg/myproject/${encodeWithBase(schema)}/$id" -> 4
        )
        forAll(endpoints) { case (endpoint, rev) =>
          Put(s"$endpoint?rev=$rev", payloadUpdated(id).toEntity(Printer.noSpaces)) ~> as(writer) ~> routes ~> check {
            status shouldEqual StatusCodes.OK
            response.asJson shouldEqual standardWriterMetadata(id, rev = rev + 1, schema1)
          }
        }
      }
    }

    "reject the update of a non-existent resource" in {
      val payload = payloadUpdated.removeKeys(keywords.id)
      Put("/v1/resources/myorg/myproject/_/doesNotExist?rev=1", payload.toEntity) ~> as(writer) ~> routes ~> check {
        status shouldEqual StatusCodes.NotFound
        response.asJson shouldEqual
          jsonContentOf("resources/errors/not-found.json", "id" -> (nxv + "doesNotExist"), "proj" -> "myorg/myproject")
      }
    }

    "reject the update of a resource at a non-existent revision" in {
      givenAResource { id =>
        Put(s"/v1/resources/myorg/myproject/_/$id?rev=10", payloadUpdated(id).toEntity) ~> as(
          writer
        ) ~> routes ~> check {
          status shouldEqual StatusCodes.Conflict
          response.asJson shouldEqual
            jsonContentOf("resources/errors/incorrect-rev.json", "provided" -> 10, "expected" -> 1)
        }
      }
    }

    "fail to refresh a resource without resources/write permission" in {
      givenAResource { id =>
        Put(s"/v1/resources/myorg/myproject/_/$id/refresh", payload.toEntity) ~> as(reader) ~> routes ~> check {
          response.shouldBeForbidden
        }
      }
    }

    "refresh a resource" in {
      val schema = "myschema"
      givenAResourceWithSchema(schema) { id =>
        val endpoints = List(
          s"/v1/resources/myorg/myproject/_/$id/refresh",
          s"/v1/resources/myorg/myproject/_/${encodeWithBase(id)}/refresh",
          s"/v1/resources/myorg/myproject/$schema/$id/refresh",
          s"/v1/resources/myorg/myproject/${encodeWithBase(schema)}/$id/refresh"
        )
        forAll(endpoints) { endpoint =>
          Put(s"$endpoint", payloadUpdated.toEntity(Printer.noSpaces)) ~> as(writer) ~> routes ~> check {
            status shouldEqual StatusCodes.OK
            response.asJson shouldEqual standardWriterMetadata(id, rev = 1, schema = schema1)
          }
        }
      }
    }

    "reject the refresh of a non-existent resource" in {
      val payload = payloadUpdated.removeKeys(keywords.id)
      Put("/v1/resources/myorg/myproject/_/doesNotExist/refresh", payload.toEntity) ~> as(writer) ~> routes ~> check {
        status shouldEqual StatusCodes.NotFound
        response.asJson shouldEqual
          jsonContentOf("resources/errors/not-found.json", "id" -> (nxv + "doesNotExist"), "proj" -> "myorg/myproject")
      }
    }

    "fail to update a schema without resources/write permission" in {
      Put(s"/v1/resources/$projectRef/_/someId/update-schema") ~> as(reader) ~> routes ~> check {
        response.shouldBeForbidden
      }
    }

    "fail to update a schema when providing a non-existing schema" in {
      givenAResourceWithSchema("myschema") { id =>
        Put(s"/v1/resources/$projectRef/wrongSchema/$id/update-schema") ~> as(writer) ~> routes ~> check {
          response.status shouldEqual StatusCodes.NotFound
          response.asJson.hcursor.get[String]("@type").toOption should contain("InvalidSchemaRejection")
        }
      }
    }

    "fail to update schema when providing _ as schema" in {
      givenAResourceWithSchema("myschema") { id =>
        Put(s"/v1/resources/$projectRef/_/$id/update-schema") ~> as(writer) ~> routes ~> check {
          response.status shouldEqual StatusCodes.BadRequest
          response.asJson.hcursor.get[String]("@type").toOption should contain("NoSchemaProvided")
        }
      }
    }

    "succeed to update the schema" in {
      givenAResourceWithSchema("myschema") { id =>
        Put(s"/v1/resources/$projectRef/otherSchema/$id/update-schema") ~> as(writer) ~> routes ~> check {
          response.status shouldEqual StatusCodes.OK
          response.asJson.hcursor.get[String]("_constrainedBy").toOption should contain(schema2.toString)
        }
      }
    }

    "fail to deprecate a resource without resources/write permission" in {
      givenAResource { id =>
        Delete(s"/v1/resources/myorg/myproject/_/$id?rev=1") ~> as(reader) ~> routes ~> check {
          response.shouldBeForbidden
        }
      }
    }

    "deprecate a resource" in {
      givenAResource { id =>
        Delete(s"/v1/resources/myorg/myproject/_/$id?rev=1") ~> as(writer) ~> routes ~> check {
          status shouldEqual StatusCodes.OK
          response.asJson shouldEqual standardWriterMetadata(id, rev = 2, deprecated = true)
        }
      }
    }

    "fail to undeprecate a resource without resources/write permission" in {
      givenADeprecatedResource { id =>
        Put(s"/v1/resources/myorg/myproject/_/$id/undeprecate?rev=2") ~> as(reader) ~> routes ~> check {
          response.shouldBeForbidden
        }
      }
    }

    "fail to undeprecate an non-deprecated resource" in {
      givenAResource { id =>
        Put(s"/v1/resources/myorg/myproject/_/$id/undeprecate?rev=1") ~> as(writer) ~> routes ~> check {
          response.status shouldEqual StatusCodes.BadRequest
          response.asJson shouldEqual jsonContentOf(
            "resources/errors/resource-not-deprecated.json",
            "id" -> (nxv + id)
          )
        }
      }
    }

    "undeprecate a resource" in {
      givenADeprecatedResource { id =>
        Put(s"/v1/resources/myorg/myproject/_/$id/undeprecate?rev=2") ~> as(writer) ~> routes ~> check {
          response.status shouldEqual StatusCodes.OK
          response.asJson shouldEqual standardWriterMetadata(id, rev = 3, deprecated = false)
        }
      }
    }

    "reject the deprecation of a resource without rev" in {
      givenAResource { id =>
        Delete(s"/v1/resources/myorg/myproject/_/$id") ~> as(writer) ~> routes ~> check {
          status shouldEqual StatusCodes.BadRequest
          response.asJson shouldEqual jsonContentOf("errors/missing-query-param.json", "field" -> "rev")
        }
      }
    }

    "reject the deprecation of a already deprecated resource" in {
      givenADeprecatedResource { id =>
        Delete(s"/v1/resources/myorg/myproject/_/$id?rev=2") ~> as(writer) ~> routes ~> check {
          status shouldEqual StatusCodes.BadRequest
          response.asJson shouldEqual jsonContentOf("resources/errors/resource-deprecated.json", "id" -> (nxv + id))
        }
      }
    }

    "tag a resource" in {
      val tag            = "mytag"
      val taggingPayload = json"""{"tag": "$tag", "rev": 1}"""
      givenAResource { id =>
        Post(
          s"/v1/resources/myorg/myproject/_/$id/tags?rev=1",
          taggingPayload.toEntity
        ) ~> as(writer) ~> routes ~> check {
          status shouldEqual StatusCodes.Created
          response.asJson shouldEqual standardWriterMetadata(id, rev = 2)
        }
      }
    }

    "fail fetching a resource information without resources/read permission" in {
      val mySchema = "myschema"
      val myTag    = "myTag"
      givenAResourceWithSchemaAndTag(mySchema.some, myTag.some) { id =>
        val endpoints = List(
          s"/v1/resources/myorg/myproject/_/$id",
          s"/v1/resources/myorg/myproject/_/$id?rev=1",
          s"/v1/resources/myorg/myproject/_/$id?tag=$myTag",
          s"/v1/resources/myorg/myproject/$mySchema/${encodeWithBase(id)}",
          s"/v1/resources/myorg/myproject/_/$id/source",
          s"/v1/resources/myorg/myproject/_/$id/source?annotate=true",
          s"/v1/resources/myorg/myproject/_/$id/remote-contexts",
          s"/v1/resources/myorg/myproject/_/$id/tags"
        )
        forAll(endpoints) { endpoint =>
          Get(endpoint) ~> routes ~> check {
            response.shouldBeForbidden
          }
        }
      }
    }

    "fetch a resource" in {
      givenAResource { id =>
        Get(s"/v1/resources/myorg/myproject/_/$id") ~> as(reader) ~> routes ~> check {
          status shouldEqual StatusCodes.OK
          val meta = standardWriterMetadata(id, tpe = "schema:Custom")
          response.asJson shouldEqual simplePayload(id).deepMerge(meta).deepMerge(resourceCtx)
          response.expectConditionalCacheHeaders
          response.headers should contain(varyHeader)
        }
      }
    }

    "fetch a resource by rev and tag" in {
      val mySchema = "myschema"
      val myTag    = "myTag"
      givenAResourceWithSchemaAndTag(mySchema.some, myTag.some) { id =>
        val endpoints = List(
          s"/v1/resources/myorg/myproject/$mySchema/$id?rev=1",
          s"/v1/resources/myorg/myproject/_/$id?rev=1",
          s"/v1/resources/myorg/myproject/$mySchema/$id?tag=$myTag"
        )
        val meta      = standardWriterMetadata(id, schema = schema1, tpe = "schema:Custom")
        forAll(endpoints) { endpoint =>
          Get(endpoint) ~> as(reader) ~> routes ~> check {
            status shouldEqual StatusCodes.OK
            response.asJson shouldEqual simplePayload(id).deepMerge(meta).deepMerge(resourceCtx)
            response.expectConditionalCacheHeaders
            response.headers should contain(varyHeader)
          }
        }
      }
    }

    "fetch a resource original payload" in {
      givenAResource { id =>
        Get(s"/v1/resources/myorg/myproject/_/$id/source") ~> as(reader) ~> routes ~> check {
          status shouldEqual StatusCodes.OK
          response.asJson shouldEqual simplePayload(id)
          response.expectConditionalCacheHeaders
        }
      }
    }

    "fetch a resource where the source has a null value" in {
      val payloadWithNullField = (id: String) => simplePayload(id) deepMerge json"""{ "empty": null }"""
      givenAResourceWithPayload(payloadWithNullField(_).toEntity(Printer.noSpaces)) { id =>
        Get(s"/v1/resources/myorg/myproject/_/$id") ~> as(reader) ~> routes ~> check {
          response.asJson shouldEqual
            payloadWithNullField(id).dropNullValues
              .deepMerge(standardWriterMetadata(id, tpe = "schema:Custom"))
              .deepMerge(resourceCtx)
        }
      }
    }

    "fetch a resource original payload where the source has a null value" in {
      val payloadWithNullField = (id: String) => json"""{ "@id": "$id", "empty": null }"""
      givenAResourceWithPayload(payloadWithNullField(_).toEntity(Printer.noSpaces)) { id =>
        Get(s"/v1/resources/myorg/myproject/_/$id/source") ~> as(reader) ~> routes ~> check {
          response.asJson shouldEqual payloadWithNullField(id)
        }
      }
    }

    "fetch a resource remote contexts" in {
      val id             = genString()
      val payloadWithCtx = simplePayload(id).deepMerge(resourceCtx)
      Post("/v1/resources/myorg/myproject", payloadWithCtx.toEntity) ~> as(writer) ~> routes ~> check {
        status shouldEqual StatusCodes.Created
      }

      val tag        = "mytag"
      val tagPayload = json"""{"tag": "$tag", "rev": 1}"""
      Post(s"/v1/resources/myorg/myproject/_/$id/tags?rev=1", tagPayload.toEntity) ~> as(writer) ~> routes ~> check {
        status shouldEqual StatusCodes.Created
      }

      val endpoints = List(
        s"/v1/resources/myorg/myproject/_/$id/remote-contexts",
        s"/v1/resources/myorg/myproject/_/$id/remote-contexts?rev=1",
        s"/v1/resources/myorg/myproject/_/$id/remote-contexts?tag=$tag"
      )

      forAll(endpoints) { endpoint =>
        Get(endpoint) ~> as(reader) ~> routes ~> check {
          status shouldEqual StatusCodes.OK
          response.asJson shouldEqual
            json"""{
                     "@context" : "https://bluebrain.github.io/nexus/contexts/remote-contexts.json",
                     "remoteContexts" : [
                       { "@type": "StaticContextRef", "iri": "https://bluebrain.github.io/nexus/contexts/metadata.json" }
                     ]
                  }"""
        }
      }

    }

    "return not found when a resource does not exist" in {
      val endpoints = List(
        "/v1/resources/myorg/myproject/_/wrongid",
        "/v1/resources/myorg/myproject/_/wrongid?rev=1",
        "/v1/resources/myorg/myproject/_/wrongid?tag=mytag",
        "/v1/resources/myorg/myproject/_/wrongid/source",
        "/v1/resources/myorg/myproject/_/wrongid/source?annotate=true",
        "/v1/resources/myorg/myproject/_/wrongid/remote-contexts",
        "/v1/resources/myorg/myproject/_/wrongid/tags"
      )
      forAll(endpoints) { endpoint =>
        Get(endpoint) ~> as(reader) ~> routes ~> check {
          status shouldEqual StatusCodes.NotFound
          response.asJson shouldEqual jsonContentOf(
            "resources/errors/not-found.json",
            "id"   -> "https://bluebrain.github.io/nexus/vocabulary/wrongid",
            "proj" -> "myorg/myproject"
          )
          response.headers should not contain varyHeader
        }
      }
    }

    "fetch a resource original payload with metadata" in {
      givenAResource { id =>
        Get(s"/v1/resources/myorg/myproject/_/$id/source?annotate=true") ~> as(reader) ~> routes ~> check {
          status shouldEqual StatusCodes.OK
          response.asJson shouldEqual payloadWithMetadata(id)
          response.headers should contain(varyHeader)
        }
      }
    }

    "fetch a resource original payload with metadata using tags and revisions" in {
      val mySchema = encodeUriPath(schemas.resources.toString)
      val myTag    = "myTag"
      givenAResourceWithTag(myTag) { id =>
        val endpoints = List(
          s"/v1/resources/myorg/myproject/$mySchema/$id/source?rev=1&annotate=true",
          s"/v1/resources/myorg/myproject/_/$id/source?rev=1&annotate=true",
          s"/v1/resources/myorg/myproject/$mySchema/$id/source?tag=$myTag&annotate=true"
        )
        forAll(endpoints) { endpoint =>
          Get(endpoint) ~> as(reader) ~> routes ~> check {
            status shouldEqual StatusCodes.OK
            response.asJson shouldEqual payloadWithMetadata(id)
            response.expectConditionalCacheHeaders
            response.headers should contain(varyHeader)
          }
        }
      }
    }

    "fetch a resource original payload by rev and tag" in {
      val mySchema = "myschema"
      val myTag    = "mytag"
      givenAResourceWithSchemaAndTag(mySchema.some, myTag.some) { id =>
        val endpoints = List(
          s"/v1/resources/myorg/myproject/$mySchema/$id/source?rev=1",
          s"/v1/resources/myorg/myproject/_/$id/source?rev=1",
          s"/v1/resources/myorg/myproject/$mySchema/$id/source?tag=$myTag"
        )
        forAll(endpoints) { endpoint =>
          Get(endpoint) ~> as(reader) ~> routes ~> check {
            status shouldEqual StatusCodes.OK
            response.asJson shouldEqual simplePayload(id)
            response.headers should contain(varyHeader)
          }
        }
      }
    }

    "fetch the resource tags" in {
      val tag    = "mytag"
      val schema = "myschema"
      givenAResourceWithSchema(schema) { id =>
        val taggingPayload = json"""{"tag": "$tag", "rev": 1}"""
        Post(
          s"/v1/resources/myorg/myproject/_/$id/tags?rev=1",
          taggingPayload.toEntity
        ) ~> as(writer) ~> routes ~> check {
          Get(s"/v1/resources/myorg/myproject/_/$id/tags?rev=1") ~> as(reader) ~> routes ~> check {
            status shouldEqual StatusCodes.OK
            response.asJson shouldEqual json"""{"tags": []}""".addContext(contexts.tags)
          }
          Get(s"/v1/resources/myorg/myproject/$schema/$id/tags") ~> as(reader) ~> routes ~> check {
            status shouldEqual StatusCodes.OK
            response.asJson shouldEqual json"""{"tags": [{"rev": 1, "tag": "$tag"}]}""".addContext(contexts.tags)
          }
        }
      }
    }

    "return not found if tag not found" in {
      givenAResourceWithTag("myTag") { id =>
        Get(s"/v1/resources/myorg/myproject/myschema/$id?tag=myother") ~> as(reader) ~> routes ~> check {
          status shouldEqual StatusCodes.NotFound
          response.asJson shouldEqual jsonContentOf("errors/tag-not-found.json", "tag" -> "myother")
        }
      }
    }

    "reject if provided rev and tag simultaneously" in {
      givenAResourceWithTag("myTag") { id =>
        Get(s"/v1/resources/myorg/myproject/myschema/$id?tag=mytag&rev=1") ~> as(reader) ~> routes ~> check {
          status shouldEqual StatusCodes.BadRequest
          response.asJson shouldEqual jsonContentOf("errors/tag-and-rev-error.json")
        }
      }
    }

    "tag a deprecated resource" in {
      givenADeprecatedResource { id =>
        val revToTag = 2
        val payload  = json"""{"tag": "mytag", "rev": $revToTag}"""
        Post(
          s"/v1/resources/myorg/myproject/_/$id/tags?rev=$revToTag",
          payload.toEntity
        ) ~> as(writer) ~> routes ~> check {
          status shouldEqual StatusCodes.Created
          response.asJson shouldEqual standardWriterMetadata(id, rev = 3, deprecated = true)
        }
      }
    }

    "delete a tag on resource" in {
      val myTag = "myTag"
      givenAResourceWithTag(myTag) { id =>
        Delete(s"/v1/resources/myorg/myproject/_/$id/tags/$myTag?rev=1") ~> as(writer) ~> routes ~> check {
          status shouldEqual StatusCodes.OK
          response.asJson shouldEqual standardWriterMetadata(id, rev = 2)
        }
      }
    }

    "not return the deleted tag" in {
      val myTag = "myTag"
      givenAResourceWithTag(myTag) { id =>
        Delete(s"/v1/resources/myorg/myproject/_/$id/tags/$myTag?rev=1") ~> as(writer) ~> routes ~> check {
          Get(s"/v1/resources/myorg/myproject/_/$id/tags") ~> as(reader) ~> routes ~> check {
            status shouldEqual StatusCodes.OK
            response.asJson shouldEqual json"""{"tags": []}""".addContext(contexts.tags)
          }
        }
      }
    }

    "fail to fetch resource by the deleted tag" in {
      val myTag = "myTag"
      givenAResourceWithTag(myTag) { id =>
        Delete(s"/v1/resources/myorg/myproject/_/$id/tags/$myTag?rev=1") ~> as(writer) ~> routes ~> check {
          Get(s"/v1/resources/myorg/myproject/_/$id?tag=$myTag") ~> as(reader) ~> routes ~> check {
            status shouldEqual StatusCodes.NotFound
            response.asJson shouldEqual jsonContentOf("errors/tag-not-found.json", "tag" -> myTag)
          }
        }
      }
    }

    "redirect to fusion with a given tag if the Accept header is set to text/html" in {
      val myTag = "myTag"
      givenAResourceWithTag(myTag) { id =>
        Get(s"/v1/resources/myorg/myproject/_/$id?tag=$myTag") ~> Accept(`text/html`) ~> as(reader) ~> routes ~> check {
          response.status shouldEqual StatusCodes.SeeOther
          response.header[Location].value.uri shouldEqual Uri(
            s"https://bbp.epfl.ch/nexus/web/myorg/myproject/resources/$id"
          ).withQuery(Uri.Query("tag" -> myTag))
        }
      }
    }

    "return not found for an unknown `xxx` suffix" in {
      val methods = List(Get, Put, Post, Delete)
      givenAResource { id =>
        forAll(methods) { method =>
          method(s"/v1/resources/myorg/myproject/myschema/${encodeUriPath(id)}/xxx") ~> as(
            reader
          ) ~> routes ~> check {
            status shouldEqual StatusCodes.NotFound
            response.asJson.hcursor.get[String]("@type").toOption should contain("ResourceNotFound")
          }
        }
      }
    }

    "fail to delete a resource for an unauthorized user" in {
      Delete("/v1/resources/myorg/myproject/_/xxx?prune=true") ~> as(reader) ~> routes ~> check {
        response.shouldBeForbidden
      }
    }

    "successfully delete a resource for an authorized user" in {
      givenAResource { id =>
        Delete(s"/v1/resources/myorg/myproject/_/$id?prune=true") ~> as(deleter) ~> routes ~> check {
          status shouldEqual StatusCodes.NoContent
        }
        Get(s"/v1/resources/myorg/myproject/_/$id") ~> as(reader) ~> routes ~> check {
          status shouldEqual StatusCodes.NotFound
        }
      }
    }
  }

  private def resourceMetadata(
      project: ProjectRef,
      id: Iri,
      schema: Iri,
      tpe: String,
      rev: Int,
      deprecated: Boolean,
      createdBy: Subject,
      updatedBy: Subject
  ): Json    =
    jsonContentOf(
      "resources/resource-route-metadata-response.json",
      "project"    -> project,
      "id"         -> id,
      "rev"        -> rev,
      "type"       -> tpe,
      "deprecated" -> deprecated,
      "createdBy"  -> createdBy.asIri,
      "updatedBy"  -> updatedBy.asIri,
      "schema"     -> schema,
      "self"       -> ResourceAccess.resource(project, id).uri
    )

  // Metadata for a resource that has been created and updated by the writer user
  private def standardWriterMetadata(
      id: String,
      rev: Int = 1,
      schema: Iri = schemas.resources,
      deprecated: Boolean = false,
      tpe: String = (schemaOrg + "Custom").toString
  ) =
    resourceMetadata(
      projectRef,
      nxv + id,
      schema,
      tpe,
      rev,
      deprecated,
      writer,
      writer
    )

  private def givenAResourceWithSchemaAndTag(
      schema: Option[String],
      tag: Option[String],
      payload: String => RequestEntity = simplePayload(_).toEntity
  )(
      assertion: String => Assertion
  ): Assertion = {
    val id            = genString()
    val schemaSegment = schema.getOrElse("_")
    val tagSegment    = tag.map(t => s"?tag=$t").getOrElse("")
    Post(s"/v1/resources/$projectRef/$schemaSegment$tagSegment", payload(id)) ~> as(writer) ~> routes ~> check {
      status shouldEqual StatusCodes.Created
    }
    assertion(id)
  }

  /**
    * Provides a resource with a schema to assert on. The latest revision is 1. Note that the schema needs to be
    * available in the context where this is used.
    */
  private def givenAResourceWithSchema(schemaName: String)(assertion: String => Assertion): Assertion =
    givenAResourceWithSchemaAndTag(Some(schemaName), None, simplePayload(_).toEntity)(assertion)

  /** Provides a simple resource to assert on. The latest revision is 1. */
  private def givenAResource(assertion: String => Assertion): Assertion =
    givenAResourceWithSchemaAndTag(None, None, simplePayload(_).toEntity)(assertion)

  /** Provides a resource with a specific payload to assert on. The latest revision is 1. */
  private def givenAResourceWithPayload(payload: String => RequestEntity)(assertion: String => Assertion): Assertion =
    givenAResourceWithSchemaAndTag(None, None, payload)(assertion)

  /** Provides a resource with a tag on revision 1. The latest revision is 1 */
  private def givenAResourceWithTag(tag: String)(assertion: String => Assertion): Assertion =
    givenAResourceWithSchemaAndTag(None, Some(tag), simplePayload(_).toEntity)(assertion)

  /** Provides a deprecate resource to assert on. The latest revision is 2. */
  private def givenADeprecatedResource(assertion: String => Assertion): Assertion =
    givenAResource { id =>
      Delete(s"/v1/resources/$projectRef/_/$id?rev=1") ~> as(writer) ~> routes ~> check {
        status shouldEqual StatusCodes.OK
      }
      assertion(id)
    }

}
