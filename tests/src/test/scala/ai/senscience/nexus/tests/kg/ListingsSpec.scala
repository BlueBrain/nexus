package ai.senscience.nexus.tests.kg

import ai.senscience.nexus.tests.Identity.listings.{Alice, Bob}
import ai.senscience.nexus.tests.Identity.{Anonymous, Delta}
import ai.senscience.nexus.tests.Optics.{`@id` as atId, filterSearchMetadata, filterSearchMetadataAndLinks, listing}
import ai.senscience.nexus.tests.iam.types.Permission.{Organizations, Resources, Views}
import ai.senscience.nexus.tests.resources.SimpleResource
import ai.senscience.nexus.tests.{BaseIntegrationSpec, SchemaPayload}
import akka.http.scaladsl.model.StatusCodes
import cats.effect.IO
import ch.epfl.bluebrain.nexus.delta.kernel.utils.UrlUtils.{encodeUri, encodeUriPath, encodeUriQuery}
import ch.epfl.bluebrain.nexus.testkit.scalatest.ResourceMatchers.*
import io.circe.Json
import io.circe.optics.JsonPath.root
import org.scalatest.Assertion
import org.scalatest.LoneElement.*

import java.util.UUID

final class ListingsSpec extends BaseIntegrationSpec {

  private val org1   = genId()
  private val proj11 = genId()
  private val proj12 = genId()
  private val proj13 = genId()
  private val ref11  = s"$org1/$proj11"
  private val ref12  = s"$org1/$proj12"
  private val ref13  = s"$org1/$proj13"

  private val org2   = genId()
  private val proj21 = genId()
  private val ref21  = s"$org2/$proj21"

  private val tag1 = "v1.0.0"
  private val tag2 = "v1.0.1"
  private val tag3 = "v1.0.2"

  private val resource11Id = s"${config.deltaUri}/resources/$proj11/_/resource11"

  private val resourceType = s"http://schema.org/Type-${UUID.randomUUID()}"

  override def beforeAll(): Unit = {
    super.beforeAll()

    val setupProjects = for {
      _ <- aclDsl.addPermission("/", Bob, Organizations.Create)
      // First org and projects
      _ <- adminDsl.createOrganization(org1, org1, Bob)
      _ <- adminDsl.createProjectWithName(org1, proj11, name = proj11, Bob)
      _ <- adminDsl.createProjectWithName(org1, proj12, name = proj12, Bob)
      _ <- adminDsl.createProjectWithName(org1, proj13, name = proj13, Bob)
      // Second org and projects
      _ <- adminDsl.createOrganization(org2, org2, Bob)
      _ <- adminDsl.createProjectWithName(org2, proj21, name = proj21, Bob)
      _ <- aclDsl.addPermission(s"/$ref12", Alice, Resources.Read)
      _ <- aclDsl.addPermission(s"/$ref12", Alice, Views.Query)
    } yield succeed

    val resourcePayload = SimpleResource.sourcePayloadWithType(resourceType, 5).accepted
    val postResource    = for {
      schemaPayload <- SchemaPayload.loadSimple(resourceType)
      // Creation
      _             <- deltaClient.put[Json](s"/resources/$ref11/_/resource11", resourcePayload, Bob)(expectCreated)
      _             <- deltaClient.put[Json](s"/schemas/$ref11/test-schema", schemaPayload, Bob)(expectCreated)
      _             <- deltaClient.put[Json](s"/resources/$ref11/test-schema/resource11_with_schema", resourcePayload, Bob)(
                         expectCreated
                       )
      _             <- deltaClient.put[Json](s"/resources/$ref12/_/resource12", resourcePayload, Bob)(expectCreated)
      _             <- deltaClient.put[Json](s"/resources/$ref13/_/resource13", resourcePayload, Bob)(expectCreated)
      _             <- deltaClient.put[Json](s"/resources/$ref21/_/resource21", resourcePayload, Bob)(expectCreated)
      // Tag
      _             <-
        deltaClient.post[Json](s"/resources/$ref11/_/resource11/tags?rev=1", tag(tag1, 1), Bob)(expectCreated)
      _             <-
        deltaClient.post[Json](s"/resources/$ref13/_/resource13/tags?rev=1", tag(tag1, 1), Bob)(expectCreated)
      _             <-
        deltaClient.post[Json](s"/resources/$ref13/_/resource13/tags?rev=2", tag(tag2, 2), Bob)(expectCreated)
      _             <-
        deltaClient.post[Json](s"/resources/$ref21/_/resource21/tags?rev=1", tag(tag3, 1), Bob)(expectCreated)
      // Deprecate
      _             <- deltaClient.delete[Json](s"/resources/$ref12/_/resource12?rev=1", Bob)(expectOk)
    } yield succeed

    (setupProjects >> postResource).accepted
    ()
  }

  "Listing resources within a project" should {

    "get default resolver" in {
      val defaultResolverId = "https://bluebrain.github.io/nexus/vocabulary/defaultInProject"

      val mapping = replacements(
        Delta,
        "project" -> ref11,
        "id"      -> defaultResolverId,
        "self"    -> resolverSelf(ref11, defaultResolverId)
      )

      val expected = jsonContentOf("kg/listings/default-resolver.json", mapping*)

      eventually {
        deltaClient.get[Json](s"/resolvers/$ref11", Bob) { (json, response) =>
          response.status shouldEqual StatusCodes.OK
          filterSearchMetadata(json) should equalIgnoreArrayOrder(expected)
        }
      }
    }

    "get default views" ignore {
      val defaultSparqlView = "https://bluebrain.github.io/nexus/vocabulary/defaultSparqlIndex"
      val searchView        = "https://bluebrain.github.io/nexus/vocabulary/searchView"

      val mapping = replacements(
        Delta,
        "project"               -> ref11,
        "defaultSparqlView"     -> defaultSparqlView,
        "defaultSparqlViewSelf" -> viewSelf(ref11, defaultSparqlView),
        "searchView"            -> searchView,
        "searchViewSelf"        -> viewSelf(ref11, searchView)
      )

      val expected = jsonContentOf("kg/listings/default-view.json", mapping*)
      eventually {
        deltaClient.get[Json](s"/views/$ref11", Bob) { (json, response) =>
          response.status shouldEqual StatusCodes.OK
          filterSearchMetadata(json) should equalIgnoreArrayOrder(expected)
        }
      }
    }

    val resource11WithSchemaId     = s"${config.deltaUri}/resources/$proj11/_/resource11_with_schema"
    val resource11WithSchemaSelf   = resourceSelf(ref11, resource11WithSchemaId)
    val resource11WithSchemaResult = jsonContentOf(
      "kg/listings/project/resource11-schema.json",
      replacements(
        Bob,
        "org"          -> org1,
        "proj"         -> proj11,
        "resourceType" -> resourceType,
        "id"           -> resource11WithSchemaId,
        "self"         -> resource11WithSchemaSelf
      )*
    )

    "get the resources with schema" in eventually {
      deltaClient.get[Json](s"/resources/$ref11/test-schema", Bob) { (json, response) =>
        response.status shouldEqual StatusCodes.OK
        filterSearchMetadata(json) should equalIgnoreArrayOrder(resource11WithSchemaResult)
      }
    }

    "get the resource via locate with an id and id parameters" in {
      val encodedId = encodeUriPath(resource11WithSchemaId)
      deltaClient.get[Json](s"/resources?id=$encodedId", Bob) { (json, response) =>
        response.status shouldEqual StatusCodes.OK
        filterSearchMetadata(json) should equalIgnoreArrayOrder(resource11WithSchemaResult)
      }

      deltaClient.get[Json](s"/resources?locate=$encodedId", Bob) { (json, response) =>
        response.status shouldEqual StatusCodes.OK
        filterSearchMetadata(json) should equalIgnoreArrayOrder(resource11WithSchemaResult)
      }
    }

    "get the resource via locate with a self " in {
      val encodedSelf = encodeUri(resource11WithSchemaSelf)

      deltaClient.get[Json](s"/resources?locate=$encodedSelf", Bob) { (json, response) =>
        response.status shouldEqual StatusCodes.OK
        filterSearchMetadata(json) should equalIgnoreArrayOrder(resource11WithSchemaResult)
      }
    }

    "get an error if the user has no access" in {
      deltaClient.get[Json](s"/resources/$ref11", Alice) { (_, response) =>
        response.status shouldEqual StatusCodes.Forbidden
      }
    }

    "get responses using after" in eventually {
      // Building the next results, replace the public url by the one used by the tests
      def next(json: Json) = {
        listing._next.getOption(json).map { url =>
          url.replace(config.deltaUri.toString(), "")
        }
      }

      // Get results though a lens and filtering out some fields
      def lens(json: Json) =
        listing._results
          .getOption(json)
          .fold(Vector.empty[String]) { _.flatMap(atId.getOption) }

      val result = deltaClient
        .stream(
          s"/resources/$ref11?type=$resourceType&size=2",
          next,
          lens,
          Bob
        )
        .compile
        .toList

      val expected = Vector(resource11Id, resource11WithSchemaId)

      result.map(_.flatten shouldEqual expected)
    }

  }

  private def assertProjects(json: Json, projects: String*) = {
    val obtained = root._results.each._project.string.getAll(json).toSet

    obtained should contain theSameElementsAs projects
  }

  "Listing resources within an org" should {
    "get resources from the 3 projects in the org for user with appropriate acls" in {
      eventually {
        deltaClient.get[Json](s"/resources/$org1", Bob) { (json, response) =>
          response.status shouldEqual StatusCodes.OK
          assertProjects(json, ref11, ref12, ref13)
        }
      }
    }

    "get resources from only one project in the org for user with restricted acls" in {
      eventually {
        deltaClient.get[Json](s"/resources/$org1", Alice) { (json, response) =>
          response.status shouldEqual StatusCodes.OK
          assertProjects(json, ref12)
        }
      }
    }

    "get the latest revision of previously tagged resources when queried by tag" in {
      val resource11Self = resourceSelf(ref11, resource11Id)
      val resource13Id   = s"${config.deltaUri}/resources/$proj13/_/resource13"
      val resource13Self = resourceSelf(ref13, resource13Id)
      val expected       = jsonContentOf(
        "kg/listings/project/resources-tagged.json",
        replacements(
          Bob,
          "org"          -> org1,
          "proj1"        -> proj11,
          "resourceType" -> resourceType,
          "id1"          -> resource11Id,
          "self1"        -> resource11Self,
          "proj2"        -> proj13,
          "id2"          -> resource13Id,
          "self2"        -> resource13Self
        )*
      )

      eventually {
        deltaClient.get[Json](s"/resources/$org1?tag=$tag1", Bob) { (json, response) =>
          response.status shouldEqual StatusCodes.OK
          filterSearchMetadata(json) should equalIgnoreArrayOrder(expected)
        }
      }
    }

    "get an error for anonymous" in {
      deltaClient.get[Json](s"/resources/$org1", Anonymous) { (_, response) =>
        response.status shouldEqual StatusCodes.Forbidden
      }
    }
  }

  "Listing resources within all accessible projects in the system" should {
    val testResourceType = encodeUriQuery(resourceType)

    "get resources from all projects for user with appropriate acls" in {
      val expected = jsonContentOf(
        "kg/listings/all/resource-by-type-4.json",
        replacements(
          Bob,
          "org1"         -> org1,
          "org2"         -> org2,
          "proj1"        -> proj11,
          "proj2"        -> proj12,
          "proj3"        -> proj13,
          "proj4"        -> proj21,
          "resourceType" -> resourceType
        )*
      )

      eventually {
        deltaClient.get[Json](s"/resources?type=$testResourceType", Bob) { (json, response) =>
          response.status shouldEqual StatusCodes.OK
          filterSearchMetadataAndLinks(json) should equalIgnoreArrayOrder(expected)
        }
      }
    }

    "get resources from only one project for user with restricted acls" in {
      val expected = jsonContentOf(
        "kg/listings/all/resource-by-type-1.json",
        replacements(
          Bob,
          "org"          -> org1,
          "proj"         -> proj12,
          "resourceType" -> resourceType
        )*
      )

      deltaClient.get[Json](s"/resources?type=$testResourceType", Alice) { (json, response) =>
        response.status shouldEqual StatusCodes.OK
        filterSearchMetadataAndLinks(json) should equalIgnoreArrayOrder(expected)
      }
    }

    "get an error for anonymous" in {
      deltaClient.get[Json](s"/resources?type=$testResourceType", Anonymous) { (_, response) =>
        response.status shouldEqual StatusCodes.Forbidden
      }
    }

  }

  "Listing common errors" should {
    val endpoints = List("/resources", s"/resources/$org1", s"/resources/$ref11", s"/resources/$ref11/test-schema")

    "return 400 when using both 'from' and 'after'" in {
      forAll(endpoints) { endpoint =>
        eventually {
          deltaClient.get[Json](s"$endpoint?from=10&after=%5B%22test%22%5D", Bob) { (json, response) =>
            response.status shouldEqual StatusCodes.BadRequest
            json shouldEqual jsonContentOf("kg/listings/from-and-after-error.json")
          }
        }
      }
    }

    "return 400 if 'from' is bigger than the limit" in {
      forAll(endpoints) { endpoint =>
        eventually {
          deltaClient.get[Json](s"$endpoint?from=10001", Bob) { (json, response) =>
            response.status shouldEqual StatusCodes.BadRequest
            json shouldEqual jsonContentOf("kg/listings/from-over-limit-error.json")
          }
        }
      }
    }

  }

  "Fulltext search" should {

    val project = ref11

    "find a match by @id" in {
      val id  = s"http://bbp.epfl.ch/${genString()}"
      val id2 = s"http://bbp.epfl.ch/${genString()}"

      val resource: String => Json = id => json"""{ "@id": "$id" }"""

      postResource(resource(id), project).accepted
      postResource(resource(id2), project).accepted

      eventually {
        fulltextListing(q = encodeUri(id), project) { json =>
          val results = listing._results.getOption(json).value
          results.loneElement should have(`@id`(id))
        }
      }
    }

    "find the match on @id first even when other docs reference it" in {
      val id = s"http://bbp.epfl.ch/${genString()}"

      val resource: String => Json = id => json"""{ "@id": "$id" }"""
      val resRef                   = json"""{ "http://schema.org/description": "$id" }"""
      val resRef2                  = json"""{ "randomField": "$id" }"""

      postResource(resource(id), project).accepted
      postResource(resRef, project).accepted
      postResource(resRef2, project).accepted

      eventually {
        fulltextListing(q = encodeUriQuery(id), project) { json =>
          val results = listing._results.getOption(json).value
          results.head should have(`@id`(id))
        }
      }
    }

    "find match by name" in {
      val id       = s"http://bbp.epfl.ch/${genString()}"
      val resource = json"""{ "@id": "$id", "http://schema.org/name": "Lorem ipsum dolor"}"""

      postResource(resource, project).accepted

      val query = encodeUriQuery("lor ip dol")

      eventually {
        fulltextListing(q = query, project) { json =>
          val results = listing._results.getOption(json).value
          results.head should have(`@id`(id))
        }
      }
    }

    "find match by description" in {
      val id       = s"http://bbp.epfl.ch/${genString()}"
      val resource =
        json"""{ "@id": "$id", "http://schema.org/description": "Northumberland is a ceremonial county bordering Scotland."}"""

      postResource(resource, project).accepted

      val query = encodeUriQuery("cere nort")

      eventually {
        fulltextListing(q = query, project) { json =>
          val results = listing._results.getOption(json).value
          results.head should have(`@id`(id))
        }
      }
    }
  }

  def postResource(json: Json, projectRef: String): IO[Assertion] =
    deltaClient.post[Json](s"/resources/$projectRef", json, Bob)(expectCreated)

  def fulltextListing(q: String, projectRef: String)(test: Json => Assertion): IO[Assertion] =
    deltaClient.get[Json](s"/resources/$projectRef?q=$q", Bob) { (json, response) =>
      response.status shouldEqual StatusCodes.OK
      test(json)
    }

}
