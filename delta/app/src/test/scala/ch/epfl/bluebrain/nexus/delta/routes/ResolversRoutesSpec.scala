package ch.epfl.bluebrain.nexus.delta.routes

import akka.http.scaladsl.model.MediaRanges.`*/*`
import akka.http.scaladsl.model.MediaTypes.`text/event-stream`
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.model.headers.{`Last-Event-ID`, Accept, OAuth2BearerToken}
import akka.http.scaladsl.server.Route
import ch.epfl.bluebrain.nexus.delta.kernel.utils.{UUIDF, UrlUtils}
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.{contexts, nxv, schema, schemas}
import ch.epfl.bluebrain.nexus.delta.sdk._
import ch.epfl.bluebrain.nexus.delta.sdk.generators.{ProjectGen, ResourceGen, SchemaGen}
import ch.epfl.bluebrain.nexus.delta.sdk.model.ResourceRef.{Latest, Revision}
import ch.epfl.bluebrain.nexus.delta.sdk.model.acls.AclAddress
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Identity.{Anonymous, Authenticated, Group, Subject}
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.{Caller, Identity}
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.{ApiMappings, ProjectRef}
import ch.epfl.bluebrain.nexus.delta.sdk.model.resolvers.ResolverResolutionRejection.ResourceNotFound
import ch.epfl.bluebrain.nexus.delta.sdk.model.resolvers.ResolverType.{CrossProject, InProject}
import ch.epfl.bluebrain.nexus.delta.sdk.model.resolvers.{MultiResolution, ResolverContextResolution, ResourceResolutionReport}
import ch.epfl.bluebrain.nexus.delta.sdk.model.resources.Resource
import ch.epfl.bluebrain.nexus.delta.sdk.model.schemas.Schema
import ch.epfl.bluebrain.nexus.delta.sdk.model.{Label, ResourceRef}
import ch.epfl.bluebrain.nexus.delta.sdk.syntax._
import ch.epfl.bluebrain.nexus.delta.sdk.testkit.{AclSetup, IdentitiesDummy, ProjectSetup, ResolversDummy}
import ch.epfl.bluebrain.nexus.delta.sdk.utils.RouteHelpers
import ch.epfl.bluebrain.nexus.delta.utils.RouteFixtures
import ch.epfl.bluebrain.nexus.testkit._
import io.circe.Json
import io.circe.syntax._
import monix.bio.IO
import org.scalatest.matchers.should.Matchers
import org.scalatest.{CancelAfterFailure, Inspectors, OptionValues}

import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

class ResolversRoutesSpec
    extends RouteHelpers
    with Matchers
    with CirceLiteral
    with CirceEq
    with CancelAfterFailure
    with IOFixedClock
    with IOValues
    with OptionValues
    with TestMatchers
    with Inspectors
    with RouteFixtures {

  private val uuid                  = UUID.randomUUID()
  implicit private val uuidF: UUIDF = UUIDF.fixed(uuid)

  private val asAlice = addCredentials(OAuth2BearerToken(alice.subject))
  private val asBob   = addCredentials(OAuth2BearerToken(bob.subject))

  private val org                = Label.unsafe("org")
  private val defaultApiMappings = Resources.mappings
  private val am                 = ApiMappings("nxv" -> nxv.base, "Person" -> schema.Person, "resolver" -> schemas.resolvers)
  private val projBase           = nxv.base
  private val project            =
    ProjectGen.project("org", "project", uuid = uuid, orgUuid = uuid, base = projBase, mappings = am)
  private val project2           =
    ProjectGen.project("org", "project2", uuid = uuid, orgUuid = uuid, base = projBase, mappings = am)

  private val (orgs, projects) = {
    implicit val subject: Subject = Identity.Anonymous
    ProjectSetup
      .init(
        orgsToCreate = List(org),
        projectsToCreate = List(project, project2)
      )
      .accepted
  }

  private val identities = IdentitiesDummy(
    Caller(alice, Set(alice, Anonymous, Authenticated(realm), Group("group", realm))),
    Caller(bob, Set(bob))
  )

  private val acls = AclSetup
    .init(
      (Anonymous, AclAddress.Root, Set(Permissions.events.read)),
      (alice, AclAddress.Organization(org), Set(Permissions.resolvers.read, Permissions.resolvers.write)),
      (bob, AclAddress.Project(project.ref), Set(Permissions.resolvers.read, Permissions.resolvers.write))
    )
    .accepted

  val resolverContextResolution: ResolverContextResolution = new ResolverContextResolution(
    rcr,
    (_, _, _) => IO.raiseError(ResourceResolutionReport())
  )

  private val resourceId = nxv + "resource"
  private val resource   =
    ResourceGen.resource(resourceId, project.ref, jsonContentOf("resources/resource.json", "id" -> resourceId))
  private val resourceFR = ResourceGen.resourceFor(resource, types = Set(nxv + "Custom"), am = defaultApiMappings)

  private val schemaId       = nxv + "schemaId"
  private val schemaResource = SchemaGen.schema(
    schemaId,
    project.ref,
    jsonContentOf("resources/schema.json")
      .addContext(contexts.shacl, contexts.schemasMetadata) deepMerge json"""{"@id": "$schemaId"}"""
  )
  private val resourceFS     = SchemaGen.resourceFor(schemaResource)

  def fetchResource: (ResourceRef, ProjectRef) => IO[ResourceNotFound, DataResource] =
    (ref: ResourceRef, p: ProjectRef) =>
      ref match {
        case Latest(i) if i == resourceId => IO.pure(resourceFR)
        case _                            => IO.raiseError(ResourceNotFound(ref.iri, p))
      }

  def fetchSchema: (ResourceRef, ProjectRef) => IO[ResourceNotFound, SchemaResource] =
    (ref: ResourceRef, p: ProjectRef) =>
      ref match {
        case Revision(_, i, 5L) if i == schemaId => IO.pure(resourceFS)
        case _                                   => IO.raiseError(ResourceNotFound(ref.iri, p))
      }

  private val resolvers = ResolversDummy(orgs, projects, resolverContextResolution, (_, _) => IO.unit).accepted

  private val resolverResolution = ResolverResolution(
    acls,
    resolvers,
    List(
      ReferenceExchange[Resource](fetchResource, _.source),
      ReferenceExchange[Schema](fetchSchema, _.source)
    )
  )

  private val multiResolution = MultiResolution(projects, resolverResolution)

  private val routes = Route.seal(ResolversRoutes(identities, acls, orgs, projects, resolvers, multiResolution))

  private def withId(id: String, payload: Json) =
    payload.deepMerge(Json.obj("@id" -> id.asJson))

  private val authorizationFailedResponse       = jsonContentOf("errors/authorization-failed.json")

  private val inProjectPayload                    = jsonContentOf("resolvers/in-project-success.json")
  private val crossProjectUseCurrentPayload       = jsonContentOf("resolvers/cross-project-use-current-caller-success.json")
  private val crossProjectProvidedEntitiesPayload = jsonContentOf(
    "resolvers/cross-project-provided-entities-success.json"
  )

  "The Resolvers route" when {

    val tagPayload              = json"""{"tag": "my-tag", "rev": 1}"""
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
          request ~> asBob ~> routes ~> check {
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
          request ~> asAlice ~> routes ~> check {
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
          request ~> asAlice ~> routes ~> check {
            status shouldEqual StatusCodes.Conflict
            response.asJson shouldEqual jsonContentOf(
              "/resolvers/errors/already-exists.json",
              "id"      -> id,
              "projRef" -> project.ref
            )
          }
        }
      }

      "fail with a 400 if decoding fails" in {
        forAll(
          create("resolver-failed", project.ref, jsonContentOf("/resolvers/no-resolver-type-error.json"))
            ++ create("resolver-failed", project.ref, jsonContentOf("/resolvers/two-resolver-types-error.json"))
            ++ create("resolver-failed", project.ref, jsonContentOf("/resolvers/unknown-resolver-error.json"))
            ++ create(
              "resolver-failed",
              project.ref,
              jsonContentOf("/resolvers/cross-project-no-resolution-error.json")
            )
            ++ create(
              "resolver-failed",
              project.ref,
              jsonContentOf("/resolvers/cross-project-both-resolution-error.json")
            )
        ) { case (_, request) =>
          request ~> asAlice ~> routes ~> check {
            status shouldEqual StatusCodes.BadRequest
          }
        }
      }

      "fail if it there are no resolver/write permissions" in {
        forAll(
          create(genString(), project2.ref, inProjectPayload) ++ create(genString(), project2.ref, inProjectPayload)
        ) { case (_, request) =>
          request ~> asBob ~> routes ~> check {
            status shouldEqual StatusCodes.Forbidden
            response.asJson shouldEqual authorizationFailedResponse
          }

          request ~> routes ~> check {
            status shouldEqual StatusCodes.Forbidden
            response.asJson shouldEqual authorizationFailedResponse
          }
        }
      }

    }

    "updating a resolver" should {

      "succeed for a in-project resolver" in {
        Put(
          s"/v1/resolvers/${project.ref}/in-project-put?rev=1",
          inProjectPayload.deepMerge(newPriority).toEntity
        ) ~> asBob ~> routes ~> check {
          status shouldEqual StatusCodes.OK
          response.asJson shouldEqual
            resolverMetadata(nxv + "in-project-put", InProject, project.ref, rev = 2L, createdBy = bob, updatedBy = bob)
        }
      }

      "succeed for a cross-project resolver" in {
        Put(
          s"/v1/resolvers/${project2.ref}/cross-project-use-current-put?rev=1",
          crossProjectUseCurrentPayload.deepMerge(newPriority).toEntity
        ) ~> asAlice ~> routes ~> check {
          status shouldEqual StatusCodes.OK
          response.asJson shouldEqual resolverMetadata(
            nxv + "cross-project-use-current-put",
            CrossProject,
            project2.ref,
            rev = 2L,
            createdBy = alice,
            updatedBy = alice
          )
        }

        Put(
          s"/v1/resolvers/${project2.ref}/cross-project-provided-entities-put?rev=1",
          crossProjectProvidedEntitiesPayload.deepMerge(newPriority).toEntity
        ) ~> asAlice ~> routes ~> check {
          status shouldEqual StatusCodes.OK
          response.asJson shouldEqual resolverMetadata(
            nxv + "cross-project-provided-entities-put",
            CrossProject,
            project2.ref,
            rev = 2L,
            createdBy = alice,
            updatedBy = alice
          )
        }
      }

      "fail if the resolver doesn't exist" in {
        Put(
          s"/v1/resolvers/${project.ref}/in-project-put?rev=5",
          inProjectPayload.deepMerge(newPriority).toEntity
        ) ~> asBob ~> routes ~> check {
          status shouldEqual StatusCodes.Conflict
          response.asJson shouldEqual jsonContentOf(
            "/resolvers/errors/incorrect-rev.json",
            "provided" -> 5L,
            "expected" -> 2L
          )
        }
      }

      "fail if the revision is incorrect" in {
        Put(s"/v1/resolvers/${project.ref}/xxxx?rev=1", inProjectPayload.toEntity) ~> asAlice ~> routes ~> check {
          status shouldEqual StatusCodes.NotFound
          response.asJson shouldEqual jsonContentOf(
            "/resolvers/errors/not-found.json",
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
            ) ~> asBob ~> routes
          )
        ) { request =>
          request ~> check {
            status shouldEqual StatusCodes.Forbidden
            response.asJson shouldEqual authorizationFailedResponse
          }
        }
      }
    }

    "tagging a resolver" should {

      "succeed" in {
        Post(
          s"/v1/resolvers/${project.ref}/in-project-put/tags?rev=2",
          tagPayload.toEntity
        ) ~> asAlice ~> routes ~> check {
          status shouldEqual StatusCodes.Created
          response.asJson shouldEqual resolverMetadata(
            nxv + "in-project-put",
            InProject,
            project.ref,
            rev = 3L,
            createdBy = bob,
            updatedBy = alice
          )
        }
      }

      "fail if it there are no resolver/write permissions" in {
        Post(
          s"/v1/resolvers/${project2.ref}/in-project-put/tags?rev=2",
          tagPayload.toEntity
        ) ~> asBob ~> routes ~> check {
          status shouldEqual StatusCodes.Forbidden
          response.asJson shouldEqual authorizationFailedResponse
        }
      }
    }

    "deprecating a resolver" should {

      "succeed" in {
        Delete(s"/v1/resolvers/${project.ref}/in-project-put?rev=3") ~> asAlice ~> routes ~> check {
          status shouldEqual StatusCodes.OK
          response.asJson shouldEqual
            resolverMetadata(
              nxv + "in-project-put",
              InProject,
              project.ref,
              rev = 4L,
              deprecated = true,
              createdBy = bob,
              updatedBy = alice
            )
        }
      }

      "fail if resolver has already been deprecated" in {
        Delete(s"/v1/resolvers/${project.ref}/in-project-put?rev=4") ~> asAlice ~> routes ~> check {
          status shouldEqual StatusCodes.BadRequest
          response.asJson shouldEqual
            jsonContentOf("/resolvers/errors/resolver-deprecated.json", "id" -> (nxv + "in-project-put"))
        }
      }

      "fail if no revision is provided" in {
        Delete(s"/v1/resolvers/${project.ref}/in-project-put") ~> asAlice ~> routes ~> check {
          status shouldEqual StatusCodes.BadRequest
          response.asJson shouldEqual
            jsonContentOf("/errors/missing-query-param.json", "field" -> "rev")
        }
      }

      "prevent further updates" in {
        Put(
          s"/v1/resolvers/${project.ref}/in-project-put?rev=4",
          inProjectPayload.toEntity
        ) ~> asBob ~> routes ~> check {
          status shouldEqual StatusCodes.BadRequest
          response.asJson shouldEqual jsonContentOf(
            "/resolvers/errors/resolver-deprecated.json",
            "id" -> (nxv + "in-project-put")
          )
        }
      }

      "prevent adding new tags" in {
        Post(
          s"/v1/resolvers/${project.ref}/in-project-put/tags?rev=4",
          tagPayload.toEntity
        ) ~> asAlice ~> routes ~> check {
          status shouldEqual StatusCodes.BadRequest
          response.asJson shouldEqual jsonContentOf(
            "/resolvers/errors/resolver-deprecated.json",
            "id" -> (nxv + "in-project-put")
          )
        }
      }

      "fail if it there are no resolver/write permissions" in {
        forAll(
          List(
            Delete(s"/v1/resolvers/${project.ref}/in-project-put?rev=1") ~> routes,
            Delete(s"/v1/resolvers/${project2.ref}/cross-project-use-current-put?rev=1") ~> asBob ~> routes
          )
        ) { request =>
          request ~> check {
            status shouldEqual StatusCodes.Forbidden
            response.asJson shouldEqual authorizationFailedResponse
          }
        }
      }
    }

    def inProject(
        id: Iri,
        priority: Long,
        rev: Long = 1,
        deprecated: Boolean = false,
        createdBy: Subject = bob,
        updatedBy: Subject = bob
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

    val inProjectLast = inProject(nxv + "in-project-put", 34, 4, deprecated = true, updatedBy = alice)

    val crossProjectUseCurrentLast = crossProjectUseCurrentPayload
      .deepMerge(json"""{"priority": 35}""")
      .deepMerge(
        resolverMetadata(
          nxv + "cross-project-use-current-put",
          CrossProject,
          project2.ref,
          rev = 2L,
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
          rev = 2L,
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
          Get(endpoint) ~> asBob ~> routes ~> check {
            status shouldEqual StatusCodes.OK
            response.asJson shouldEqual inProjectLast.deepMerge(resolverMetaContext)
          }
        }
      }

      "get the latest version of an cross-project resolver using current caller" in {
        Get(s"/v1/resolvers/${project2.ref}/cross-project-use-current-put") ~> asAlice ~> routes ~> check {
          status shouldEqual StatusCodes.OK
          response.asJson shouldEqual crossProjectUseCurrentLast.deepMerge(resolverMetaContext)
        }
      }

      "get the latest version of an cross-project resolver using provided entities" in {
        val ctx = json""" {"@context": [{"nxv" : "${nxv.base}"}, "${contexts.resolvers}", "${contexts.metadata}"]}"""
        Get(s"/v1/resolvers/${project2.ref}/cross-project-provided-entities-put") ~> asAlice ~> routes ~> check {
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
          Get(endpoint) ~> asBob ~> routes ~> check {
            status shouldEqual StatusCodes.OK
            val id       = nxv + "in-project-put"
            val expected = inProjectPayload
              .deepMerge(resolverMetadata(id, InProject, project.ref, createdBy = bob, updatedBy = bob))
              .deepMerge(resolverMetaContext)
            response.asJson shouldEqual expected
          }
        }

      }

      "get the version by tag" in {
        val endpoints = List(
          s"/v1/resolvers/${project.ref}/in-project-put?tag=my-tag",
          s"/v1/resources/${project.ref}/_/in-project-put?tag=my-tag",
          s"/v1/resources/${project.ref}/resolver/in-project-put?tag=my-tag"
        )
        forAll(endpoints) { endpoint =>
          Get(endpoint) ~> asBob ~> routes ~> check {
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
          Get(endpoint) ~> asBob ~> routes ~> check {
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
          Get(endpoint) ~> asBob ~> routes ~> check {
            status shouldEqual StatusCodes.OK
            val expected = inProjectPayload
            response.asJson shouldEqual expected
          }
        }
      }

      "get the original payload by tag" in {
        Get(s"/v1/resolvers/${project.ref}/in-project-put/source?tag=my-tag") ~> asBob ~> routes ~> check {
          status shouldEqual StatusCodes.OK
          val expected = inProjectPayload
          response.asJson shouldEqual expected
        }
      }

      "get the resolver tags" in {
        val endpoints = List(
          s"/v1/resolvers/${project.ref}/in-project-put/tags",
          s"/v1/resources/${project.ref}/_/in-project-put/tags",
          s"/v1/resources/${project.ref}/resolver/in-project-put/tags"
        )
        forAll(endpoints) { endpoint =>
          Get(endpoint) ~> asBob ~> routes ~> check {
            status shouldEqual StatusCodes.OK
            response.asJson shouldEqual json"""{"tags": [{"rev": 1, "tag": "my-tag"}]}""".addContext(contexts.tags)
          }
        }
      }

      "fail if the resolver does not exist" in {
        Get(s"/v1/resolvers/${project.ref}/xxxx") ~> asBob ~> routes ~> check {
          status shouldEqual StatusCodes.NotFound
          response.asJson shouldEqual jsonContentOf(
            "/resolvers/errors/not-found.json",
            "id"         -> (nxv + "xxxx"),
            "projectRef" -> project.ref
          )
        }
      }

      "fail if the revision is not found" in {
        Get(s"/v1/resolvers/${project.ref}/in-project-put?rev=10") ~> asBob ~> routes ~> check {
          status shouldEqual StatusCodes.NotFound
          response.asJson shouldEqual jsonContentOf(
            "/errors/revision-not-found.json",
            "provided" -> 10L,
            "current"  -> 4L
          )
        }
      }

      "fail if it there are no resolver/read permissions" in {
        forAll(
          List(
            Get(s"/v1/resolvers/${project.ref}/in-project-put") ~> routes,
            Get(s"/v1/resolvers/${project2.ref}/cross-project-use-current-put") ~> asBob ~> routes
          )
        ) { request =>
          request ~> check {
            status shouldEqual StatusCodes.Forbidden
            response.asJson shouldEqual authorizationFailedResponse
          }
        }
      }
    }

    "listing the resolvers" should {

      def expectedResults(results: Json*): Json = {
        val ctx = json"""{"@context": ["${contexts.metadata}", "${contexts.search}", "${contexts.resolvers}"]}"""
        Json.obj("_total" -> Json.fromInt(results.size), "_results" -> Json.arr(results: _*)) deepMerge ctx
      }

      "return the deprecated resolvers the user has access to" in {
        Get(s"/v1/resolvers/${project.ref}/caches?deprecated=true") ~> asBob ~> routes ~> check {
          status shouldEqual StatusCodes.OK
          response.asJson shouldEqual expectedResults(inProjectLast)
        }
      }

      "return the in project resolvers" in {
        val encodedResolver          = UrlUtils.encode(nxv.Resolver.toString)
        val encodedInProjectResolver = UrlUtils.encode(nxv.InProject.toString)
        Get(
          s"/v1/resolvers/${project.ref}/caches?type=$encodedResolver&type=$encodedInProjectResolver"
        ) ~> asBob ~> routes ~> check {
          status shouldEqual StatusCodes.OK
          response.asJson shouldEqual expectedResults(
            inProjectLast,
            inProject(nxv + "in-project-put2", 3),
            inProject(nxv + "in-project-post", 1)
          )
        }
      }

      "return the resolvers with revision 2" in {
        Get(s"/v1/resolvers/${project2.ref}/caches?rev=2") ~> asAlice ~> routes ~> check {
          status shouldEqual StatusCodes.OK
          response.asJson should equalIgnoreArrayOrder(
            expectedResults(
              crossProjectUseCurrentLast,
              crossProjectProvidedIdentitiesLast.replace(
                Json.arr("nxv:Schema".asJson, "nxv:Custom".asJson),
                Json.arr(nxv.Schema.asJson, (nxv + "Custom").asJson)
              )
            )
          )
        }
      }

      "fail to list resolvers if the user has not access resolvers/read on the project" in {
        forAll(
          List(
            Get(s"/v1/resolvers/${project.ref}/caches?deprecated=true") ~> routes,
            Get(s"/v1/resolvers/${project2.ref}/caches") ~> asBob ~> routes
          )
        ) { request =>
          request ~> check {
            status shouldEqual StatusCodes.Forbidden
          }
        }
      }
    }

    "getting the events" should {

      "succeed from the given offset" in {
        val endpoints = List("/v1/resolvers/events", "/v1/resolvers/org/events")
        forAll(endpoints) { endpoint =>
          Get(endpoint) ~> Accept(`*/*`) ~> `Last-Event-ID`("2") ~> routes ~> check {
            mediaType shouldBe `text/event-stream`
            response.asString.strip shouldEqual contentOf("resolvers/eventstream-2-14.txt", "uuid" -> uuid).strip
          }
        }
      }

      "fail to get event stream without permission" in {
        val endpoints = List("/v1/resolvers/events", "/v1/resolvers/org/events", "/v1/resolvers/org/project/events")
        forAll(endpoints) { endpoint =>
          Get(endpoint) ~> Accept(`*/*`) ~> `Last-Event-ID`("1") ~> asBob ~> routes ~> check {
            response.status shouldEqual StatusCodes.Forbidden
            response.asJson shouldEqual jsonContentOf("errors/authorization-failed.json")
          }
        }
      }
    }

    val idResourceEncoded      = UrlUtils.encode(resourceId.toString)
    val idSchemaEncoded        = UrlUtils.encode(schemaId.toString)
    val unknownResourceEncoded = UrlUtils.encode((nxv + "xxx").toString)

    "resolve the resources/schemas" should {
      "succeed as a resource for the given id" in {
        // First we resolve with a in-project resolver, the second one with a cross-project resolver
        forAll(List(project, project2)) { p =>
          Get(s"/v1/resolvers/${p.ref}/_/$idResourceEncoded") ~> asAlice ~> routes ~> check {
            response.status shouldEqual StatusCodes.OK
            response.asJson shouldEqual jsonContentOf("resolvers/resource-resolved.json")
          }
        }
      }

      "succeed as a resource and return the resolution report" in {
        Get(s"/v1/resolvers/${project.ref}/_/$idResourceEncoded?showReport=true") ~> asAlice ~> routes ~> check {
          response.status shouldEqual StatusCodes.OK
          response.asJson shouldEqual jsonContentOf("resolvers/resource-resolved-resource-resolution-report.json")
        }
      }

      "succeed as a resource for the given id using the given resolver" in {
        forAll(List(project -> "in-project-post", project2 -> "cross-project-provided-entities-post")) {
          case (p, resolver) =>
            Get(s"/v1/resolvers/${p.ref}/$resolver/$idResourceEncoded") ~> asAlice ~> routes ~> check {
              response.status shouldEqual StatusCodes.OK
              response.asJson shouldEqual jsonContentOf("resolvers/resource-resolved.json")
            }
        }
      }

      "succeed as a resource and return the resolution report for the given resolver" in {
        Get(
          s"/v1/resolvers/${project.ref}/in-project-post/$idResourceEncoded?showReport=true"
        ) ~> asAlice ~> routes ~> check {
          response.status shouldEqual StatusCodes.OK
          response.asJson shouldEqual jsonContentOf("resolvers/resource-resolved-resolver-resolution-report.json")
        }
      }

      "succeed as a schema for the given id" in {
        // First we resolve with a in-project resolver, the second one with a cross-project resolver
        forAll(List(project, project2)) { p =>
          Get(s"/v1/resolvers/${p.ref}/_/$idSchemaEncoded?rev=5") ~> asAlice ~> routes ~> check {
            response.status shouldEqual StatusCodes.OK
            response.asJson shouldEqual jsonContentOf("resolvers/schema-resolved.json")
          }
        }
      }

      "succeed as a schema and return the resolution report" in {
        Get(s"/v1/resolvers/${project.ref}/_/$idSchemaEncoded?rev=5&showReport=true") ~> asAlice ~> routes ~> check {
          response.status shouldEqual StatusCodes.OK
          response.asJson shouldEqual jsonContentOf("resolvers/schema-resolved-resource-resolution-report.json")
        }
      }

      "succeed as a schema for the given id using the given resolver" in {
        forAll(List(project -> "in-project-post", project2 -> "cross-project-provided-entities-post")) {
          case (p, resolver) =>
            Get(s"/v1/resolvers/${p.ref}/$resolver/$idSchemaEncoded?rev=5") ~> asAlice ~> routes ~> check {
              response.status shouldEqual StatusCodes.OK
              response.asJson shouldEqual jsonContentOf("resolvers/schema-resolved.json")
            }
        }
      }

      "succeed as a schema and return the resolution report for the given resolver" in {
        Get(
          s"/v1/resolvers/${project.ref}/in-project-post/$idSchemaEncoded?rev=5&showReport=true"
        ) ~> asAlice ~> routes ~> check {
          response.status shouldEqual StatusCodes.OK
          response.asJson shouldEqual jsonContentOf("resolvers/schema-resolved-resolver-resolution-report.json")
        }
      }

      "fail for an unknown resource id" in {
        Get(s"/v1/resolvers/${project.ref}/_/$unknownResourceEncoded") ~> asAlice ~> routes ~> check {
          response.status shouldEqual StatusCodes.NotFound
          response.asJson shouldEqual jsonContentOf("resolvers/unknown-resource-resource-resolution-report.json")
        }
      }

      "fail for an unknown resource id using the given resolver" in {
        Get(s"/v1/resolvers/${project.ref}/in-project-post/$unknownResourceEncoded") ~> asAlice ~> routes ~> check {
          response.status shouldEqual StatusCodes.NotFound
          response.asJson shouldEqual jsonContentOf("resolvers/unknown-resource-resolver-resolution-report.json")
        }
      }

      "fail if the user does not have the right permission" in {
        Get(s"/v1/resolvers/${project.ref}/in-project-post/$idSchemaEncoded") ~> routes ~> check {
          response.status shouldEqual StatusCodes.Forbidden
          response.asJson shouldEqual jsonContentOf("errors/authorization-failed.json")
        }
      }
    }
  }
}
