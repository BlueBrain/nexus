package ai.senscience.nexus.delta.routes

import akka.http.scaladsl.model.MediaTypes.`text/html`
import akka.http.scaladsl.model.headers.{Accept, Location}
import akka.http.scaladsl.model.{StatusCodes, Uri}
import akka.http.scaladsl.server.Route
import cats.effect.IO
import ch.epfl.bluebrain.nexus.delta.kernel.utils.UUIDF
import ch.epfl.bluebrain.nexus.delta.kernel.utils.UrlUtils.encodeUriPath
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.{contexts, nxv, schema, schemas}
import ch.epfl.bluebrain.nexus.delta.sdk.acls.AclSimpleCheck
import ch.epfl.bluebrain.nexus.delta.sdk.acls.model.AclAddress
import ch.epfl.bluebrain.nexus.delta.sdk.directives.DeltaSchemeDirectives
import ch.epfl.bluebrain.nexus.delta.sdk.generators.{ProjectGen, ResourceGen, SchemaGen}
import ch.epfl.bluebrain.nexus.delta.sdk.identities.IdentitiesDummy
import ch.epfl.bluebrain.nexus.delta.sdk.implicits.*
import ch.epfl.bluebrain.nexus.delta.sdk.jsonld.JsonLdContent
import ch.epfl.bluebrain.nexus.delta.sdk.model.ResourceAccess
import ch.epfl.bluebrain.nexus.delta.sdk.permissions.Permissions
import ch.epfl.bluebrain.nexus.delta.sdk.projects.FetchContextDummy
import ch.epfl.bluebrain.nexus.delta.sdk.projects.model.ApiMappings
import ch.epfl.bluebrain.nexus.delta.sdk.resolvers.*
import ch.epfl.bluebrain.nexus.delta.sdk.resolvers.model.ResolverType
import ch.epfl.bluebrain.nexus.delta.sdk.resolvers.model.ResolverType.{CrossProject, InProject}
import ch.epfl.bluebrain.nexus.delta.sdk.resources.model.Resource
import ch.epfl.bluebrain.nexus.delta.sdk.schemas.model.Schema
import ch.epfl.bluebrain.nexus.delta.sdk.utils.BaseRouteSpec
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.{Anonymous, Subject}
import ch.epfl.bluebrain.nexus.delta.sourcing.model.ResourceRef.{Latest, Revision}
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{Label, ProjectRef, ResourceRef}
import io.circe.Json
import io.circe.syntax.*

import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

class ResolversRoutesSpec extends BaseRouteSpec {

  private val uuid                  = UUID.randomUUID()
  implicit private val uuidF: UUIDF = UUIDF.fixed(uuid)

  private val org      = Label.unsafe("org")
  private val am       = ApiMappings("nxv" -> nxv.base, "Person" -> schema.Person, "resolver" -> schemas.resolvers)
  private val projBase = nxv.base
  private val project  =
    ProjectGen.project("org", "project", uuid = uuid, orgUuid = uuid, base = projBase, mappings = am)
  private val project2 =
    ProjectGen.project("org", "project2", uuid = uuid, orgUuid = uuid, base = projBase, mappings = am)

  private val identities = IdentitiesDummy.fromUsers(alice, bob)

  val resolverContextResolution: ResolverContextResolution = ResolverContextResolution(rcr)

  private val resourceId = nxv + "resource"
  private val resource   =
    ResourceGen.resource(resourceId, project.ref, jsonContentOf("resources/resource.json", "id" -> resourceId))
  private val resourceFR = ResourceGen.resourceFor(resource, types = Set(nxv + "Custom"))

  private val schemaId       = nxv + "schemaId"
  private val schemaResource = SchemaGen.schema(
    schemaId,
    project.ref,
    jsonContentOf("resources/schema.json")
      .addContext(contexts.shacl, contexts.schemasMetadata) deepMerge json"""{"@id": "$schemaId"}"""
  )
  private val resourceFS     = SchemaGen.resourceFor(schemaResource)

  def fetchResource: (ResourceRef, ProjectRef) => IO[Option[JsonLdContent[Resource]]] =
    (ref: ResourceRef, _: ProjectRef) =>
      ref match {
        case Latest(`resourceId`) =>
          IO.pure(Some(JsonLdContent(resourceFR, resourceFR.value.source, resourceFR.value.tags)))
        case _                    => IO.none
      }

  def fetchSchema: (ResourceRef, ProjectRef) => IO[Option[JsonLdContent[Schema]]] =
    (ref: ResourceRef, _: ProjectRef) =>
      ref match {
        case Revision(_, `schemaId`, 5) =>
          IO.pure(Some(JsonLdContent(resourceFS, resourceFS.value.source, resourceFS.value.tags)))
        case _                          => IO.none
      }

  private lazy val validatePriority = ValidatePriority.priorityAlreadyExists(xas)

  private lazy val resolvers = ResolversImpl(
    fetchContext,
    resolverContextResolution,
    validatePriority,
    eventLogConfig,
    xas,
    clock
  )

  private val aclCheck = AclSimpleCheck(
    (alice, AclAddress.Organization(org), Set(Permissions.resolvers.read, Permissions.resolvers.write)),
    (bob, AclAddress.Project(project.ref), Set(Permissions.resolvers.read, Permissions.resolvers.write))
  ).accepted

  private lazy val resolverResolution = ResolverResolution(
    aclCheck,
    resolvers,
    (ref: ResourceRef, project: ProjectRef) =>
      fetchResource(ref, project).flatMap {
        case Some(c) => IO.pure(Some(c))
        case None    => fetchSchema(ref, project)
      },
    excludeDeprecated = false
  )

  private val fetchContext    = FetchContextDummy(List(project, project2))
  private val groupDirectives = DeltaSchemeDirectives(fetchContext)

  private lazy val multiResolution = MultiResolution(fetchContext, resolverResolution)

  private lazy val routes =
    Route.seal(ResolversRoutes(identities, aclCheck, resolvers, multiResolution, groupDirectives))

  private def withId(id: String, payload: Json) =
    payload.deepMerge(Json.obj("@id" -> id.asJson))

  private val inProjectPayload                    = jsonContentOf("resolvers/in-project-success.json")
  private val crossProjectUseCurrentPayload       = jsonContentOf("resolvers/cross-project-use-current-caller-success.json")
  private val crossProjectProvidedEntitiesPayload = jsonContentOf(
    "resolvers/cross-project-provided-entities-success.json"
  )

  "The Resolvers route" when {

    val priority: AtomicInteger = new AtomicInteger(0)
    def newPriority             = json"""{"priority": ${priority.incrementAndGet()}}"""

    "creating a resolver" should {

      def create(id: String, projectRef: ProjectRef, payload: Json) = {
        List(
          iri"${nxv + id}-post" -> Post(
            s"/v1/resolvers/$projectRef",
            withId(s"${nxv + id}-post", payload.deepMerge(newPriority)).toEntity
          ),
          iri"${nxv + id}-put"  -> Put(s"/v1/resolvers/$projectRef/$id-put", payload.deepMerge(newPriority).toEntity),
          iri"${nxv + id}-put2" -> Put(
            s"/v1/resolvers/$projectRef/$id-put2",
            withId(s"${nxv + id}-put2", payload.deepMerge(newPriority)).toEntity
          )
        )
      }

      "succeed for a in-project resolver" in {
        forAll(
          create("in-project", project.ref, inProjectPayload)
        ) { case (id, request) =>
          request ~> as(bob) ~> routes ~> check {
            status shouldEqual StatusCodes.Created
            response.asJson shouldEqual
              resolverMetadata(id, InProject, project.ref, createdBy = bob, updatedBy = bob)
          }

        }
      }

      "succeed for a cross-project resolver" in {
        forAll(
          create("cross-project-use-current", project2.ref, crossProjectUseCurrentPayload)
            ++ create("cross-project-provided-entities", project2.ref, crossProjectProvidedEntitiesPayload)
        ) { case (id, request) =>
          request ~> as(alice) ~> routes ~> check {
            status shouldEqual StatusCodes.Created
            response.asJson shouldEqual
              resolverMetadata(id, CrossProject, project2.ref, createdBy = alice, updatedBy = alice)
          }

        }
      }

      "fail if it already exists" in {
        forAll(
          create("in-project", project.ref, inProjectPayload)
        ) { case (id, request) =>
          request ~> as(alice) ~> routes ~> check {
            status shouldEqual StatusCodes.Conflict
            response.asJson shouldEqual jsonContentOf(
              "resolvers/errors/already-exists.json",
              "id"      -> id,
              "projRef" -> project.ref
            )
          }
        }
      }

      "fail with a 400 if decoding fails" in {
        forAll(
          create("resolver-failed", project.ref, jsonContentOf("resolvers/no-resolver-type-error.json"))
            ++ create("resolver-failed", project.ref, jsonContentOf("resolvers/two-resolver-types-error.json"))
            ++ create("resolver-failed", project.ref, jsonContentOf("resolvers/unknown-resolver-error.json"))
            ++ create(
              "resolver-failed",
              project.ref,
              jsonContentOf("resolvers/cross-project-no-resolution-error.json")
            )
            ++ create(
              "resolver-failed",
              project.ref,
              jsonContentOf("resolvers/cross-project-both-resolution-error.json")
            )
        ) { case (_, request) =>
          request ~> as(alice) ~> routes ~> check {
            status shouldEqual StatusCodes.BadRequest
          }
        }
      }

      "fail if it there are no resolver/write permissions" in {
        forAll(
          create(genString(), project2.ref, inProjectPayload) ++ create(genString(), project2.ref, inProjectPayload)
        ) { case (_, request) =>
          request ~> as(bob) ~> routes ~> check {
            response.shouldBeForbidden
          }

          request ~> routes ~> check {
            response.shouldBeForbidden
          }
        }
      }

    }

    "updating a resolver" should {

      "succeed for a in-project resolver" in {
        Put(
          s"/v1/resolvers/${project.ref}/in-project-put?rev=1",
          inProjectPayload.deepMerge(newPriority).toEntity
        ) ~> as(bob) ~> routes ~> check {
          status shouldEqual StatusCodes.OK
          response.asJson shouldEqual
            resolverMetadata(nxv + "in-project-put", InProject, project.ref, rev = 2, createdBy = bob, updatedBy = bob)
        }
      }

      "succeed for a cross-project resolver" in {
        Put(
          s"/v1/resolvers/${project2.ref}/cross-project-use-current-put?rev=1",
          crossProjectUseCurrentPayload.deepMerge(newPriority).toEntity
        ) ~> as(alice) ~> routes ~> check {
          status shouldEqual StatusCodes.OK
          response.asJson shouldEqual resolverMetadata(
            nxv + "cross-project-use-current-put",
            CrossProject,
            project2.ref,
            rev = 2,
            createdBy = alice,
            updatedBy = alice
          )
        }

        Put(
          s"/v1/resolvers/${project2.ref}/cross-project-provided-entities-put?rev=1",
          crossProjectProvidedEntitiesPayload.deepMerge(newPriority).toEntity
        ) ~> as(alice) ~> routes ~> check {
          status shouldEqual StatusCodes.OK
          response.asJson shouldEqual resolverMetadata(
            nxv + "cross-project-provided-entities-put",
            CrossProject,
            project2.ref,
            rev = 2,
            createdBy = alice,
            updatedBy = alice
          )
        }
      }

      "fail if the resolver doesn't exist" in {
        Put(
          s"/v1/resolvers/${project.ref}/in-project-put?rev=5",
          inProjectPayload.deepMerge(newPriority).toEntity
        ) ~> as(bob) ~> routes ~> check {
          status shouldEqual StatusCodes.Conflict
          response.asJson shouldEqual jsonContentOf(
            "resolvers/errors/incorrect-rev.json",
            "provided" -> 5,
            "expected" -> 2
          )
        }
      }

      "fail if the revision is incorrect" in {
        Put(s"/v1/resolvers/${project.ref}/xxxx?rev=1", inProjectPayload.toEntity) ~> as(alice) ~> routes ~> check {
          status shouldEqual StatusCodes.NotFound
          response.asJson shouldEqual jsonContentOf(
            "resolvers/errors/not-found.json",
            "id"         -> (nxv + "xxxx"),
            "projectRef" -> project.ref
          )
        }
      }

      "fail if it there are no resolver/write permissions" in {
        forAll(
          List(
            Put(
              s"/v1/resolvers/${project.ref}/in-project-put?rev=1",
              inProjectPayload.deepMerge(newPriority).toEntity
            ) ~> routes,
            Put(
              s"/v1/resolvers/${project2.ref}/cross-project-use-current-put?rev=1",
              crossProjectUseCurrentPayload.deepMerge(newPriority).toEntity
            ) ~> as(bob) ~> routes
          )
        ) { request =>
          request ~> check {
            response.shouldBeForbidden
          }
        }
      }
    }

    "deprecating a resolver" should {

      "succeed" in {
        Delete(s"/v1/resolvers/${project.ref}/in-project-put?rev=2") ~> as(alice) ~> routes ~> check {
          status shouldEqual StatusCodes.OK
          response.asJson shouldEqual
            resolverMetadata(
              nxv + "in-project-put",
              InProject,
              project.ref,
              rev = 3,
              deprecated = true,
              createdBy = bob,
              updatedBy = alice
            )
        }
      }

      "fail if resolver has already been deprecated" in {
        Delete(s"/v1/resolvers/${project.ref}/in-project-put?rev=3") ~> as(alice) ~> routes ~> check {
          status shouldEqual StatusCodes.BadRequest
          response.asJson shouldEqual
            jsonContentOf("resolvers/errors/resolver-deprecated.json", "id" -> (nxv + "in-project-put"))
        }
      }

      "fail if no revision is provided" in {
        Delete(s"/v1/resolvers/${project.ref}/in-project-put") ~> as(alice) ~> routes ~> check {
          status shouldEqual StatusCodes.BadRequest
          response.asJson shouldEqual
            jsonContentOf("errors/missing-query-param.json", "field" -> "rev")
        }
      }

      "prevent further updates" in {
        Put(
          s"/v1/resolvers/${project.ref}/in-project-put?rev=3",
          inProjectPayload.toEntity
        ) ~> as(bob) ~> routes ~> check {
          status shouldEqual StatusCodes.BadRequest
          response.asJson shouldEqual jsonContentOf(
            "resolvers/errors/resolver-deprecated.json",
            "id" -> (nxv + "in-project-put")
          )
        }
      }

      "fail if it there are no resolver/write permissions" in {
        forAll(
          List(
            Delete(s"/v1/resolvers/${project.ref}/in-project-put?rev=1") ~> routes,
            Delete(s"/v1/resolvers/${project2.ref}/cross-project-use-current-put?rev=1") ~> as(bob) ~> routes
          )
        ) { request =>
          request ~> check {
            response.shouldBeForbidden
          }
        }
      }
    }

    def inProject(
        id: Iri,
        priority: Int,
        rev: Int,
        deprecated: Boolean,
        createdBy: Subject = bob,
        updatedBy: Subject
    ) =
      resolverMetadata(
        id,
        InProject,
        project.ref,
        rev = rev,
        deprecated = deprecated,
        createdBy = createdBy,
        updatedBy = updatedBy
      )
        .deepMerge(json"""{"priority": $priority}""")
        .removeKeys("@context")

    val inProjectLast = inProject(nxv + "in-project-put", 34, 3, deprecated = true, updatedBy = alice)

    val crossProjectUseCurrentLast = crossProjectUseCurrentPayload
      .deepMerge(json"""{"priority": 35}""")
      .deepMerge(
        resolverMetadata(
          nxv + "cross-project-use-current-put",
          CrossProject,
          project2.ref,
          rev = 2,
          createdBy = alice,
          updatedBy = alice
        )
      )
      .removeKeys("@context")

    val crossProjectProvidedIdentitiesLast = jsonContentOf("resolvers/cross-project-provided-entities-response.json")
      .deepMerge(json"""{"priority": 36}""")
      .deepMerge(
        resolverMetadata(
          nxv + "cross-project-provided-entities-put",
          CrossProject,
          project2.ref,
          rev = 2,
          createdBy = alice,
          updatedBy = alice
        )
      )
      .removeKeys("@context")

    "fetching a resolver" should {
      val resolverMetaContext = json""" {"@context": ["${contexts.resolvers}", "${contexts.metadata}"]} """

      "get the latest version of an in-project resolver" in {
        val endpoints =
          List(
            s"/v1/resolvers/${project.ref}/in-project-put",
            s"/v1/resources/${project.ref}/_/in-project-put",
            s"/v1/resources/${project.ref}/resolver/in-project-put"
          )
        forAll(endpoints) { endpoint =>
          Get(endpoint) ~> as(bob) ~> routes ~> check {
            status shouldEqual StatusCodes.OK
            response.asJson shouldEqual inProjectLast.deepMerge(resolverMetaContext)
          }
        }
      }

      "get the latest version of an cross-project resolver using current caller" in {
        Get(s"/v1/resolvers/${project2.ref}/cross-project-use-current-put") ~> as(alice) ~> routes ~> check {
          status shouldEqual StatusCodes.OK
          response.asJson shouldEqual crossProjectUseCurrentLast.deepMerge(resolverMetaContext)
        }
      }

      "get the latest version of an cross-project resolver using provided entities" in {
        val ctx = json""" {"@context": [{"nxv" : "${nxv.base}"}, "${contexts.resolvers}", "${contexts.metadata}"]}"""
        Get(s"/v1/resolvers/${project2.ref}/cross-project-provided-entities-put") ~> as(alice) ~> routes ~> check {
          status shouldEqual StatusCodes.OK
          response.asJson shouldEqual crossProjectProvidedIdentitiesLast
            .replace(
              "@id" -> (nxv + "cross-project-provided-entities-put").toString,
              "nxv:cross-project-provided-entities-put"
            )
            .deepMerge(ctx)
        }
      }

      "get the version by revision" in {
        val endpoints = List(
          s"/v1/resolvers/${project.ref}/in-project-put?rev=1",
          s"/v1/resources/${project.ref}/_/in-project-put?rev=1",
          s"/v1/resources/${project.ref}/resolver/in-project-put?rev=1"
        )
        forAll(endpoints) { endpoint =>
          Get(endpoint) ~> as(bob) ~> routes ~> check {
            status shouldEqual StatusCodes.OK
            val id       = nxv + "in-project-put"
            val expected = inProjectPayload
              .deepMerge(resolverMetadata(id, InProject, project.ref, createdBy = bob, updatedBy = bob))
              .deepMerge(resolverMetaContext)
            response.asJson shouldEqual expected
          }
        }

      }

      "get the original payload" in {
        val endpoints = List(
          s"/v1/resolvers/${project.ref}/in-project-put/source",
          s"/v1/resources/${project.ref}/_/in-project-put/source",
          s"/v1/resources/${project.ref}/resolver/in-project-put/source"
        )
        forAll(endpoints) { endpoint =>
          Get(endpoint) ~> as(bob) ~> routes ~> check {
            status shouldEqual StatusCodes.OK
            val expected = inProjectPayload.deepMerge(json"""{"priority": 34}""")
            response.asJson shouldEqual expected
          }
        }
      }

      "get the original payload by revision" in {
        val endpoints = List(
          s"/v1/resolvers/${project.ref}/in-project-put/source?rev=1",
          s"/v1/resources/${project.ref}/_/in-project-put/source?rev=1",
          s"/v1/resources/${project.ref}/resolver/in-project-put/source?rev=1"
        )
        forAll(endpoints) { endpoint =>
          Get(endpoint) ~> as(bob) ~> routes ~> check {
            status shouldEqual StatusCodes.OK
            val expected = inProjectPayload
            response.asJson shouldEqual expected
          }
        }
      }

      "fail if the resolver does not exist" in {
        Get(s"/v1/resolvers/${project.ref}/xxxx") ~> as(bob) ~> routes ~> check {
          status shouldEqual StatusCodes.NotFound
          response.asJson shouldEqual jsonContentOf(
            "resolvers/errors/not-found.json",
            "id"         -> (nxv + "xxxx"),
            "projectRef" -> project.ref
          )
        }
      }

      "fail if the revision is not found" in {
        Get(s"/v1/resolvers/${project.ref}/in-project-put?rev=10") ~> as(bob) ~> routes ~> check {
          status shouldEqual StatusCodes.NotFound
          response.asJson shouldEqual jsonContentOf(
            "errors/revision-not-found.json",
            "provided" -> 10,
            "current"  -> 3
          )
        }
      }

      "fail if attempting to fetch by tag" in {
        Get(s"/v1/resolvers/${project.ref}/in-project-put?tag=some") ~> as(bob) ~> routes ~> check {
          status shouldEqual StatusCodes.BadRequest
          response.asJson shouldEqual
            json"""
                {
                  "@context" : "https://bluebrain.github.io/nexus/contexts/error.json",
                  "@type" : "FetchByTagNotSupported",
                  "reason" : "Fetching resolvers by tag is no longer supported. Id some and tag some"
                }
                  """
        }
      }

      "fail if it there are no resolver/read permissions" in {
        forAll(
          List(
            Get(s"/v1/resolvers/${project.ref}/in-project-put") ~> routes,
            Get(s"/v1/resolvers/${project2.ref}/cross-project-use-current-put") ~> as(bob) ~> routes
          )
        ) { request =>
          request ~> check {
            response.shouldBeForbidden
          }
        }
      }
    }

    "listing resolvers" should {
      "succeed if the user has read access to the given project" in {
        Get(s"/v1/resolvers/${project.ref}") ~> as(bob) ~> routes ~> check {
          status shouldEqual StatusCodes.OK
          response.asJson.asObject.value("_total").value shouldEqual Json.fromLong(3L)
        }
      }

      "fail if the user has no read access to the given project" in {
        Get(s"/v1/resolvers/${project.ref}") ~> routes ~> check {
          response.shouldBeForbidden
        }
      }
    }

    val idResourceEncoded      = encodeUriPath(resourceId.toString)
    val idSchemaEncoded        = encodeUriPath(schemaId.toString)
    val unknownResourceEncoded = encodeUriPath((nxv + "xxx").toString)

    val resourceResolved = jsonContentOf(
      "resolvers/resource-resolved.json",
      "self" -> ResourceAccess.resource(project.ref, resourceId).uri
    )

    val schemaResolved = jsonContentOf(
      "resolvers/schema-resolved.json",
      "self" -> ResourceAccess.schema(project.ref, schemaId).uri
    )

    "resolve the resources/schemas" should {
      "succeed as a resource for the given id" in {
        // First we resolve with a in-project resolver, the second one with a cross-project resolver
        forAll(List(project, project2)) { p =>
          Get(s"/v1/resolvers/${p.ref}/_/$idResourceEncoded") ~> as(alice) ~> routes ~> check {
            response.status shouldEqual StatusCodes.OK
            response.asJson shouldEqual resourceResolved
          }
        }
      }

      "succeed as a resource and return the original payload" in {
        // First we resolve with a in-project resolver, the second one with a cross-project resolver
        forAll(List(project, project2)) { p =>
          Get(s"/v1/resolvers/${p.ref}/_/$idResourceEncoded/source") ~> as(alice) ~> routes ~> check {
            response.status shouldEqual StatusCodes.OK
            response.asJson shouldEqual resourceFR.value.source
          }
        }
      }

      "succeed as a resource and return the annotated original payload" in {
        // First we resolve with a in-project resolver, the second one with a cross-project resolver
        forAll(List(project, project2)) { p =>
          Get(s"/v1/resolvers/${p.ref}/_/$idResourceEncoded/source?annotate=true") ~> as(alice) ~> routes ~> check {
            response.status shouldEqual StatusCodes.OK
            response.asJson shouldEqual resourceResolved
          }
        }
      }

      "succeed as a resource and return the resolution report" in {
        Get(s"/v1/resolvers/${project.ref}/_/$idResourceEncoded?showReport=true") ~> as(alice) ~> routes ~> check {
          response.status shouldEqual StatusCodes.OK
          response.asJson shouldEqual jsonContentOf("resolvers/resource-resolved-resource-resolution-report.json")
        }
      }

      "succeed as a resource for the given id using the given resolver" in {
        forAll(List(project -> "in-project-post", project2 -> "cross-project-provided-entities-post")) {
          case (p, resolver) =>
            Get(s"/v1/resolvers/${p.ref}/$resolver/$idResourceEncoded") ~> as(alice) ~> routes ~> check {
              response.status shouldEqual StatusCodes.OK
              response.asJson shouldEqual resourceResolved
            }
        }
      }

      "succeed as a resource and return the resolution report for the given resolver" in {
        Get(
          s"/v1/resolvers/${project.ref}/in-project-post/$idResourceEncoded?showReport=true"
        ) ~> as(alice) ~> routes ~> check {
          response.status shouldEqual StatusCodes.OK
          response.asJson shouldEqual jsonContentOf("resolvers/resource-resolved-resolver-resolution-report.json")
        }
      }

      "succeed as a schema for the given id" in {
        // First we resolve with a in-project resolver, the second one with a cross-project resolver
        forAll(List(project, project2)) { p =>
          Get(s"/v1/resolvers/${p.ref}/_/$idSchemaEncoded?rev=5") ~> as(alice) ~> routes ~> check {
            response.status shouldEqual StatusCodes.OK
            response.asJson shouldEqual schemaResolved
          }
        }
      }

      "succeed as a schema and return the resolution report" in {
        Get(s"/v1/resolvers/${project.ref}/_/$idSchemaEncoded?rev=5&showReport=true") ~> as(alice) ~> routes ~> check {
          response.status shouldEqual StatusCodes.OK
          response.asJson shouldEqual jsonContentOf("resolvers/schema-resolved-resource-resolution-report.json")
        }
      }

      "succeed as a schema for the given id using the given resolver" in {
        forAll(List(project -> "in-project-post", project2 -> "cross-project-provided-entities-post")) {
          case (p, resolver) =>
            Get(s"/v1/resolvers/${p.ref}/$resolver/$idSchemaEncoded?rev=5") ~> as(alice) ~> routes ~> check {
              response.status shouldEqual StatusCodes.OK
              response.asJson shouldEqual schemaResolved
            }
        }
      }

      "succeed as a schema and return the resolution report for the given resolver" in {
        Get(
          s"/v1/resolvers/${project.ref}/in-project-post/$idSchemaEncoded?rev=5&showReport=true"
        ) ~> as(alice) ~> routes ~> check {
          response.status shouldEqual StatusCodes.OK
          response.asJson shouldEqual jsonContentOf("resolvers/schema-resolved-resolver-resolution-report.json")
        }
      }

      "fail for an unknown resource id" in {
        Get(s"/v1/resolvers/${project.ref}/_/$unknownResourceEncoded") ~> as(alice) ~> routes ~> check {
          response.status shouldEqual StatusCodes.NotFound
          response.asJson shouldEqual jsonContentOf("resolvers/unknown-resource-resource-resolution-report.json")
        }
      }

      "fail for an unknown resource id using the given resolver" in {
        Get(s"/v1/resolvers/${project.ref}/in-project-post/$unknownResourceEncoded") ~> as(alice) ~> routes ~> check {
          response.status shouldEqual StatusCodes.NotFound
          response.asJson shouldEqual jsonContentOf("resolvers/unknown-resource-resolver-resolution-report.json")
        }
      }

      "fail if the user does not have the right permission" in {
        Get(s"/v1/resolvers/${project.ref}/in-project-post/$idSchemaEncoded") ~> routes ~> check {
          response.shouldBeForbidden
        }
      }
    }

    "redirect to Fusion" should {
      "be returned for the latest version if the Accept header is set to text/html" in {
        Get(s"/v1/resolvers/${project.ref}/myid2") ~> Accept(`text/html`) ~> routes ~> check {
          response.status shouldEqual StatusCodes.SeeOther
          response.header[Location].value.uri shouldEqual Uri(
            s"https://bbp.epfl.ch/nexus/web/${project.ref}/resources/myid2"
          )
        }
      }
    }
  }

  def resolverMetadata(
      id: Iri,
      resolverType: ResolverType,
      projectRef: ProjectRef,
      rev: Int = 1,
      deprecated: Boolean = false,
      createdBy: Subject = Anonymous,
      updatedBy: Subject = Anonymous
  ): Json    =
    jsonContentOf(
      "resolvers/resolver-route-metadata-response.json",
      "project"    -> projectRef,
      "id"         -> id,
      "rev"        -> rev,
      "deprecated" -> deprecated,
      "createdBy"  -> createdBy.asIri,
      "updatedBy"  -> updatedBy.asIri,
      "type"       -> resolverType,
      "self"       -> ResourceAccess.resolver(projectRef, id).uri
    )
}
