package ai.senscience.nexus.delta.routes

import ai.senscience.nexus.delta.routes.ResourcesTrialRoutes.GenerateSchema
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.server.Route
import cats.effect.IO
import ch.epfl.bluebrain.nexus.delta.kernel.utils.UrlUtils.encodeUriPath
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.{contexts, nxv, schemas}
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.JsonLdContext.keywords
import ch.epfl.bluebrain.nexus.delta.sdk.acls.AclSimpleCheck
import ch.epfl.bluebrain.nexus.delta.sdk.generators.SchemaGen
import ch.epfl.bluebrain.nexus.delta.sdk.identities.IdentitiesDummy
import ch.epfl.bluebrain.nexus.delta.sdk.identities.model.Caller
import ch.epfl.bluebrain.nexus.delta.sdk.implicits.*
import ch.epfl.bluebrain.nexus.delta.sdk.model.IdSegment.{IriSegment, StringSegment}
import ch.epfl.bluebrain.nexus.delta.sdk.model.{IdSegment, IdSegmentRef, ResourceF}
import ch.epfl.bluebrain.nexus.delta.sdk.permissions.Permissions
import ch.epfl.bluebrain.nexus.delta.sdk.resources.*
import ch.epfl.bluebrain.nexus.delta.sdk.resources.ValidationResult.*
import ch.epfl.bluebrain.nexus.delta.sdk.resources.model.ResourceRejection.{ReservedResourceId, ResourceNotFound}
import ch.epfl.bluebrain.nexus.delta.sdk.resources.model.{ResourceGenerationResult, ResourceState}
import ch.epfl.bluebrain.nexus.delta.sdk.schemas.model.Schema
import ch.epfl.bluebrain.nexus.delta.sdk.schemas.model.SchemaRejection.*
import ch.epfl.bluebrain.nexus.delta.sdk.utils.BaseRouteSpec
import ch.epfl.bluebrain.nexus.delta.sourcing.model.ResourceRef.Revision
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{ProjectRef, ResourceRef, Tags}

import java.time.Instant

class ResourcesTrialRoutesSpec extends BaseRouteSpec with ResourceInstanceFixture with ValidateResourceFixture {

  private val permissions = Set(Permissions.resources.write)
  private val aclCheck    = AclSimpleCheck((alice, projectRef, permissions)).accepted

  private val schemaSource = jsonContentOf("resources/schema.json").addContext(contexts.shacl, contexts.schemasMetadata)
  private val schemaId     = nxv + "myschema"
  private val schema1      = SchemaGen.schema(schemaId, projectRef, schemaSource.removeKeys(keywords.id))

  private val validSource   = source
  private val invalidSource = json"""{ "invalid": "xxx"}"""

  private val instant: Instant = Instant.EPOCH
  private val resourceF        = ResourceState(
    myId,
    projectRef,
    projectRef,
    source,
    compacted,
    expanded,
    remoteContexts,
    rev = 1,
    deprecated = false,
    Revision(schemaId, 1),
    types,
    Tags.empty,
    createdAt = instant,
    createdBy = alice,
    updatedAt = instant,
    updatedBy = alice
  ).toResource

  private val expectedError = ReservedResourceId(nxv + "invalid")

  private val resourcesTrial = new ResourcesTrial {
    override def generate(project: ProjectRef, schema: IdSegment, source: NexusSource)(implicit
        caller: Caller
    ): IO[ResourceGenerationResult] =
      generate(source, None)

    override def generate(project: ProjectRef, schema: ResourceF[Schema], source: NexusSource)(implicit
        caller: Caller
    ): IO[ResourceGenerationResult] =
      generate(source, Some(schema))

    // Successfully generates a resource if `validSource` is passed, fails otherwise
    private def generate(source: NexusSource, schemaOpt: Option[ResourceF[Schema]]): IO[ResourceGenerationResult] =
      IO.pure {
        source match {
          case NexusSource(`validSource`) => ResourceGenerationResult(schemaOpt, Right(resourceF))
          case _                          => ResourceGenerationResult(schemaOpt, Left(expectedError))
        }
      }

    override def validate(id: IdSegmentRef, project: ProjectRef, schemaOpt: Option[IdSegment])(implicit
        caller: Caller
    ): IO[ValidationResult] =
      (id.value, schemaOpt) match {
        // Returns a validated result for myId when no schema is provided
        case (StringSegment("myId") | IriSegment(`myId`), None)                                =>
          IO.pure(Validated(projectRef, ResourceRef.Revision(schemaId, defaultSchemaRevision), defaultReport))
        // Returns no validation result for myId for `schemas.resources`
        case (StringSegment("myId") | IriSegment(`myId`), Some(IriSegment(schemas.resources))) =>
          IO.pure(NoValidation(projectRef))
        case (IriSegment(iri), None)                                                           => IO.raiseError(ResourceNotFound(iri, project))
        case _                                                                                 => IO.raiseError(new IllegalStateException("Should not happen !"))
      }
  }

  private val generateSchema: GenerateSchema = {
    case (_, `schemaSource`, _) => IO.pure(SchemaGen.resourceFor(schema1))
    case _                      => IO.raiseError(SchemaShaclEngineRejection(nxv + "invalid", "Invalid schema"))
  }

  private lazy val routes =
    Route.seal(
      new ResourcesTrialRoutes(
        IdentitiesDummy.fromUsers(alice),
        aclCheck,
        generateSchema,
        resourcesTrial
      ).routes
    )

  "A resource trial route" should {

    "fail to generate a resource for a user without access" in {
      val payload = json"""{ "resource": $validSource }"""
      Post(s"/v1/trial/resources/$projectRef/", payload.toEntity) ~> routes ~> check {
        response.shouldBeForbidden
      }
    }

    "generate a resource without passing a schema" in {
      val payload = json"""{ "resource": $validSource }"""
      Post(s"/v1/trial/resources/$projectRef/", payload.toEntity) ~> as(alice) ~> routes ~> check {
        response.status shouldEqual StatusCodes.OK
        val jsonResponse = response.asJsonObject
        jsonResponse("schema") shouldBe empty
        jsonResponse("result") shouldEqual Some(jsonContentOf("trial/generated-resource.json"))
        jsonResponse("error") shouldBe empty
      }
    }

    "generate a resource passing a new schema and using post" in {
      val payload = json"""{ "schema": $schemaSource, "resource": $validSource }"""
      Post(s"/v1/trial/resources/$projectRef/", payload.toEntity) ~> as(alice) ~> routes ~> check {
        response.status shouldEqual StatusCodes.OK
        val jsonResponse = response.asJsonObject
        jsonResponse("schema") should not be empty
        jsonResponse("result") should not be empty
        jsonResponse("error") shouldBe empty
      }
    }

    "fails to generate a resource when passing an invalid new schema" in {
      val payload = json"""{ "schema": { "invalid":  "xxx" }, "resource": $validSource }"""
      Post(s"/v1/trial/resources/$projectRef/", payload.toEntity) ~> as(alice) ~> routes ~> check {
        response.status shouldEqual StatusCodes.BadRequest
        response.asJson shouldEqual
          json"""{
                   "@context" : "https://bluebrain.github.io/nexus/contexts/error.json",
                   "@type" : "SchemaShaclEngineRejection",
                   "reason" : "Schema 'https://bluebrain.github.io/nexus/vocabulary/invalid' failed to produce a SHACL engine for the SHACL schema.",
                   "details" : "Invalid schema"
                 }
              """
      }
    }

    "fails to generate a resource when the resource payload is invalid and without passing a schema" in {
      val payload = json"""{ "resource": $invalidSource }"""
      Post(s"/v1/trial/resources/$projectRef/", payload.toEntity) ~> as(alice) ~> routes ~> check {
        response.status shouldEqual StatusCodes.OK
        response.asJson shouldEqual
          json"""
            {
              "error" : {
                "@context" : "https://bluebrain.github.io/nexus/contexts/error.json",
                "@type" : "ReservedResourceId",
                "reason" : "Resource identifier 'https://bluebrain.github.io/nexus/vocabulary/invalid' is reserved for the platform."
              }
           }"""
      }
    }

    "fail to generate a resource passing a new schema" in {
      val payload = json"""{ "schema": $schemaSource, "resource": $invalidSource }"""
      Post(s"/v1/trial/resources/$projectRef/", payload.toEntity) ~> as(alice) ~> routes ~> check {
        response.status shouldEqual StatusCodes.OK
        val jsonResponse = response.asJsonObject
        jsonResponse("schema") should not be empty
        jsonResponse("result") shouldBe empty
        jsonResponse("error") should not be empty
      }
    }

    "fail to validate for a user without access" in {
      Get(s"/v1/resources/$projectRef/_/myId/validate") ~> routes ~> check {
        response.shouldBeForbidden
      }
    }

    s"successfully validate $myId for a user with access against the unconstrained schema" in {
      val unconstrained = encodeUriPath(schemas.resources.toString)
      Get(s"/v1/resources/$projectRef/$unconstrained/myId/validate") ~> as(alice) ~> routes ~> check {
        response.status shouldEqual StatusCodes.OK
        response.asJson shouldEqual
          json"""{
                   "@context" : "https://bluebrain.github.io/nexus/contexts/validation.json",
                   "@type" : "NoValidation",
                   "project": "myorg/myproj",
                   "schema" : "https://bluebrain.github.io/nexus/schemas/unconstrained.json?rev=1"
                 }"""
      }
    }

    s"successfully validate $myId for a user with access against its latest schema" in {
      Get(s"/v1/resources/$projectRef/_/myId/validate") ~> as(alice) ~> routes ~> check {
        response.status shouldEqual StatusCodes.OK
        response.asJson shouldEqual
          json"""{
                   "@context" : [
                     "https://bluebrain.github.io/nexus/contexts/shacl-20170720.json",
                     "https://bluebrain.github.io/nexus/contexts/validation.json"
                   ],
                   "@type" : "Validated",
                   "project": "myorg/myproj",
                   "schema" : "https://bluebrain.github.io/nexus/vocabulary/myschema?rev=1",
                   "report": {
                     "conforms" : "true"
                   }
                 }"""
      }
    }

    "fail to validate an unknown resource" in {
      val unknownResource = nxv + "unknown"
      val unknownEncoded  = encodeUriPath(unknownResource.toString)
      Get(s"/v1/resources/$projectRef/_/$unknownEncoded/validate") ~> as(alice) ~> routes ~> check {
        response.status shouldEqual StatusCodes.NotFound
        response.asJson shouldEqual jsonContentOf(
          "resources/errors/not-found.json",
          "id"   -> unknownResource.toString,
          "proj" -> projectRef.toString
        )
      }
    }
  }

}
