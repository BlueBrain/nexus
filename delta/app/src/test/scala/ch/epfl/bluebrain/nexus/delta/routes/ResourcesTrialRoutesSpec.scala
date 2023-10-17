package ch.epfl.bluebrain.nexus.delta.routes

import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.model.headers.OAuth2BearerToken
import akka.http.scaladsl.server.Route
import ch.epfl.bluebrain.nexus.delta.kernel.utils.UrlUtils
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.{contexts, nxv, schemas}
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.JsonLdContext.keywords
import ch.epfl.bluebrain.nexus.delta.routes.ResourcesTrialRoutes.GenerateSchema
import ch.epfl.bluebrain.nexus.delta.sdk.acls.AclSimpleCheck
import ch.epfl.bluebrain.nexus.delta.sdk.directives.DeltaSchemeDirectives
import ch.epfl.bluebrain.nexus.delta.sdk.generators.SchemaGen
import ch.epfl.bluebrain.nexus.delta.sdk.identities.IdentitiesDummy
import ch.epfl.bluebrain.nexus.delta.sdk.identities.model.Caller
import ch.epfl.bluebrain.nexus.delta.sdk.implicits._
import ch.epfl.bluebrain.nexus.delta.sdk.model.IdSegment.{IriSegment, StringSegment}
import ch.epfl.bluebrain.nexus.delta.sdk.model.{IdSegment, IdSegmentRef, ResourceF, Tags}
import ch.epfl.bluebrain.nexus.delta.sdk.permissions.Permissions
import ch.epfl.bluebrain.nexus.delta.sdk.projects.FetchContextDummy
import ch.epfl.bluebrain.nexus.delta.sdk.resources.NexusSource.DecodingOption
import ch.epfl.bluebrain.nexus.delta.sdk.resources.ValidationResult._
import ch.epfl.bluebrain.nexus.delta.sdk.resources._
import ch.epfl.bluebrain.nexus.delta.sdk.resources.model.ResourceRejection.{ProjectContextRejection, ReservedResourceId, ResourceNotFound}
import ch.epfl.bluebrain.nexus.delta.sdk.resources.model.{ResourceGenerationResult, ResourceRejection, ResourceState}
import ch.epfl.bluebrain.nexus.delta.sdk.schemas.model.Schema
import ch.epfl.bluebrain.nexus.delta.sdk.schemas.model.SchemaRejection._
import ch.epfl.bluebrain.nexus.delta.sdk.utils.BaseRouteSpec
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.{Anonymous, Authenticated, Group}
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{ProjectRef, ResourceRef}
import ch.epfl.bluebrain.nexus.delta.sourcing.model.ResourceRef.Revision
import monix.bio.{IO, UIO}

import java.time.Instant

class ResourcesTrialRoutesSpec extends BaseRouteSpec with ResourceInstanceFixture with ValidateResourceFixture {

  implicit private val caller: Caller =
    Caller(alice, Set(alice, Anonymous, Authenticated(realm), Group("group", realm)))

  private val asAlice = addCredentials(OAuth2BearerToken("alice"))

  private val permissions  = Set(Permissions.resources.write)
  private val aclCheck     = AclSimpleCheck((alice, projectRef, permissions)).accepted
  private val fetchContext = FetchContextDummy(List.empty, ProjectContextRejection)

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
    remoteContextRefs,
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
    ): UIO[ResourceGenerationResult] =
      generate(source, None)

    override def generate(project: ProjectRef, schema: ResourceF[Schema], source: NexusSource)(implicit
        caller: Caller
    ): UIO[ResourceGenerationResult] =
      generate(source, Some(schema))

    // Successfully generates a resource if `validSource` is passed, fails otherwise
    private def generate(source: NexusSource, schemaOpt: Option[ResourceF[Schema]]): UIO[ResourceGenerationResult] =
      UIO.pure {
        source match {
          case NexusSource(`validSource`) => ResourceGenerationResult(schemaOpt, Right(resourceF))
          case _                          => ResourceGenerationResult(schemaOpt, Left(expectedError))
        }
      }

    override def validate(id: IdSegmentRef, project: ProjectRef, schemaOpt: Option[IdSegment])(implicit
        caller: Caller
    ): IO[ResourceRejection, ValidationResult] =
      (id.value, schemaOpt) match {
        // Returns a validated result for myId when no schema is provided
        case (StringSegment("myId") | IriSegment(`myId`), None)                                =>
          UIO.pure(Validated(projectRef, ResourceRef.Revision(schemaId, defaultSchemaRevision), defaultReport))
        // Returns no validation result for myId for `schemas.resources`
        case (StringSegment("myId") | IriSegment(`myId`), Some(IriSegment(schemas.resources))) =>
          UIO.pure(NoValidation(projectRef))
        case (IriSegment(iri), None)                                                           => IO.raiseError(ResourceNotFound(iri, project))
        case _                                                                                 => IO.terminate(new IllegalStateException("Should not happen !"))
      }
  }

  private val generateSchema: GenerateSchema = {
    case (_, `schemaSource`, _) => UIO.pure(SchemaGen.resourceFor(schema1))
    case _                      => IO.raiseError(SchemaShaclEngineRejection(nxv + "invalid", "Invalid schema"))
  }

  implicit val decodingOption: DecodingOption = DecodingOption.Strict

  private lazy val routes =
    Route.seal(
      new ResourcesTrialRoutes(
        IdentitiesDummy(caller),
        aclCheck,
        generateSchema,
        resourcesTrial,
        DeltaSchemeDirectives(fetchContext)
      ).routes
    )

  "A resource trial route" should {

    "fail to generate a resource for a user without access" in {
      val payload = json"""{ "resource": $validSource }"""
      Post(s"/v1/trial/resources/$projectRef/", payload.toEntity) ~> routes ~> check {
        response.status shouldEqual StatusCodes.Forbidden
        response.asJson shouldEqual jsonContentOf("errors/authorization-failed.json")
      }
    }

    "generate a resource without passing a schema" in {
      val payload = json"""{ "resource": $validSource }"""
      Post(s"/v1/trial/resources/$projectRef/", payload.toEntity) ~> asAlice ~> routes ~> check {
        response.status shouldEqual StatusCodes.OK
        val jsonResponse = response.asJsonObject
        jsonResponse("schema") shouldBe empty
        jsonResponse("result") shouldEqual Some(jsonContentOf("trial/generated-resource.json"))
        jsonResponse("error") shouldBe empty
      }
    }

    "generate a resource passing a new schema and using post" in {
      val payload = json"""{ "schema": $schemaSource, "resource": $validSource }"""
      Post(s"/v1/trial/resources/$projectRef/", payload.toEntity) ~> asAlice ~> routes ~> check {
        response.status shouldEqual StatusCodes.OK
        val jsonResponse = response.asJsonObject
        jsonResponse("schema") should not be empty
        jsonResponse("result") should not be empty
        jsonResponse("error") shouldBe empty
      }
    }

    "fails to generate a resource when passing an invalid new schema" in {
      val payload = json"""{ "schema": { "invalid":  "xxx" }, "resource": $validSource }"""
      Post(s"/v1/trial/resources/$projectRef/", payload.toEntity) ~> asAlice ~> routes ~> check {
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
      Post(s"/v1/trial/resources/$projectRef/", payload.toEntity) ~> asAlice ~> routes ~> check {
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
      Post(s"/v1/trial/resources/$projectRef/", payload.toEntity) ~> asAlice ~> routes ~> check {
        response.status shouldEqual StatusCodes.OK
        val jsonResponse = response.asJsonObject
        jsonResponse("schema") should not be empty
        jsonResponse("result") shouldBe empty
        jsonResponse("error") should not be empty
      }
    }

    "fail to validate for a user without access" in {
      Get(s"/v1/resources/$projectRef/_/myId/validate") ~> routes ~> check {
        response.status shouldEqual StatusCodes.Forbidden
        response.asJson shouldEqual jsonContentOf("errors/authorization-failed.json")
      }
    }

    s"successfully validate $myId for a user with access against the unconstrained schema" in {
      val unconstrained = UrlUtils.encode(schemas.resources.toString)
      Get(s"/v1/resources/$projectRef/$unconstrained/myId/validate") ~> asAlice ~> routes ~> check {
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
      Get(s"/v1/resources/$projectRef/_/myId/validate") ~> asAlice ~> routes ~> check {
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
      val unknownEncoded  = UrlUtils.encode(unknownResource.toString)
      Get(s"/v1/resources/$projectRef/_/$unknownEncoded/validate") ~> asAlice ~> routes ~> check {
        response.status shouldEqual StatusCodes.NotFound
        response.asJson shouldEqual jsonContentOf(
          "/resources/errors/not-found.json",
          "id"   -> unknownResource.toString,
          "proj" -> projectRef.toString
        )
      }
    }
  }

}
