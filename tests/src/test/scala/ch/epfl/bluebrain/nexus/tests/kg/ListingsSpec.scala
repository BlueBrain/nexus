package ch.epfl.bluebrain.nexus.tests.kg

import akka.http.scaladsl.model.StatusCodes
import cats.implicits._
import ch.epfl.bluebrain.nexus.testkit.{CirceEq, EitherValuable}
import ch.epfl.bluebrain.nexus.tests.BaseSpec
import ch.epfl.bluebrain.nexus.tests.Identity.resources.{Morty, Rick}
import ch.epfl.bluebrain.nexus.tests.Identity.{Anonymous, Delta}
import ch.epfl.bluebrain.nexus.tests.Optics.{filterMetadataKeys, filterSearchMetadata, listing}
import ch.epfl.bluebrain.nexus.tests.iam.types.Permission.{Organizations, Resources}
import io.circe.Json
import org.scalatest.Inspectors

import java.net.URLEncoder

final class ListingsSpec extends BaseSpec with Inspectors with EitherValuable with CirceEq {

  private val org1   = genId()
  private val proj11 = genId()
  private val proj12 = genId()
  private val ref11  = s"$org1/$proj11"
  private val ref12  = s"$org1/$proj12"

  private val org2   = genId()
  private val proj21 = genId()
  private val ref21  = s"$org2/$proj21"

  "Setting up" should {
    "succeed in setting up orgs, projects and acls" in {
      for {
        _ <- aclDsl.addPermission("/", Rick, Organizations.Create)
        // First org and projects
        _ <- adminDsl.createOrganization(org1, org1, Rick)
        _ <- adminDsl.createProject(org1, proj11, kgDsl.projectJson(name = proj11), Rick)
        _ <- adminDsl.createProject(org1, proj12, kgDsl.projectJson(name = proj12), Rick)
        // Second org and projects
        _ <- adminDsl.createOrganization(org2, org2, Rick)
        _ <- adminDsl.createProject(org2, proj21, kgDsl.projectJson(name = proj21), Rick)
        _ <- aclDsl.addPermission(s"/$ref12", Morty, Resources.Read)
      } yield succeed
    }

    "add additional resources" in {
      val resourcePayload =
        jsonContentOf(
          "/kg/resources/simple-resource.json",
          "priority" -> "5"
        )
      val schemaPayload   = jsonContentOf("/kg/schemas/simple-schema.json")
      for {
        // Creation
        _ <- deltaClient.put[Json](s"/resources/$ref11/_/resource11", resourcePayload, Rick)(expectCreated)
        _ <- deltaClient.put[Json](s"/schemas/$ref11/test-schema", schemaPayload, Rick)(expectCreated)
        _ <- deltaClient.put[Json](s"/resources/$ref11/test-schema/resource11_with_schema", resourcePayload, Rick)(
               expectCreated
             )
        _ <- deltaClient.put[Json](s"/resources/$ref12/_/resource12", resourcePayload, Rick)(expectCreated)
        _ <- deltaClient.put[Json](s"/resources/$ref21/_/resource21", resourcePayload, Rick)(expectCreated)
        // Tag
        _ <-
          deltaClient.post[Json](s"/resources/$ref11/_/resource11/tags?rev=1", tag("v1.0.0", 1L), Rick)(expectCreated)
        _ <-
          deltaClient.post[Json](s"/resources/$ref21/_/resource21/tags?rev=1", tag("v1.0.1", 1L), Rick)(expectCreated)
        // Deprecate
        _ <- deltaClient.delete[Json](s"/resources/$ref12/_/resource12?rev=1", Rick)(expectOk)
      } yield succeed
    }
  }

  "Listing resources within a project" should {

    "get default resources" in {
      val mapping = replacements(
        Delta,
        "project-label" -> ref11,
        "project"       -> s"${config.deltaUri}/projects/$ref11"
      )

      val endpoints = List(
        s"/resolvers/$ref11" -> jsonContentOf("/kg/listings/default-resolver.json", mapping: _*),
        s"/views/$ref11"     -> jsonContentOf("/kg/listings/default-view.json", mapping: _*),
        s"/storages/$ref11"  -> jsonContentOf("/kg/listings/default-storage.json", mapping: _*)
      )

      endpoints.parTraverse { case (endpoint, expected) =>
        deltaClient.get[Json](endpoint, Rick) { (json, response) =>
          response.status shouldEqual StatusCodes.OK
          filterSearchMetadata(json) should equalIgnoreArrayOrder(expected)
        }
      }
    }

    "get the resources with schema" in {
      val expected = jsonContentOf(
        "/kg/listings/project/resource-by-schema.json",
        replacements(
          Rick,
          "org"  -> org1,
          "proj" -> proj11
        ): _*
      )
      eventually {
        deltaClient.get[Json](s"/resources/$ref11/test-schema", Rick) { (json, response) =>
          response.status shouldEqual StatusCodes.OK
          filterSearchMetadata(json) should equalIgnoreArrayOrder(expected)
        }
      }
    }

    "get an error if the user has no access" in {
      deltaClient.get[Json](s"/resources/$ref11", Morty) { (_, response) =>
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
          .fold(Vector.empty[Json]) { _.map(filterMetadataKeys) }

      val result = deltaClient
        .stream(
          s"/resources/$ref11?type=nxv:TestResource&size=2",
          next,
          lens,
          Rick
        )
        .compile
        .toList

      val expected = listing._results
        .getOption(
          jsonContentOf(
            "/kg/listings/project/resource-by-type.json",
            replacements(
              Rick,
              "org"  -> org1,
              "proj" -> proj11
            ): _*
          )
        )
        .value

      result.map(_.flatten shouldEqual expected)
    }

  }

  "Listing resources within an org" should {
    val projectType = URLEncoder.encode("https://bluebrain.github.io/nexus/vocabulary/Project", "UTF-8")
    "get resources from both projects in the org for user with appropriate acls" in {
      val expected = jsonContentOf(
        "/kg/listings/org/filter-project-2.json",
        replacements(
          Rick,
          "org"   -> org1,
          "proj1" -> proj11,
          "proj2" -> proj12
        ): _*
      )

      deltaClient.get[Json](s"/resources/$org1?type=$projectType", Rick) { (json, response) =>
        response.status shouldEqual StatusCodes.OK
        filterSearchMetadata(json) should equalIgnoreArrayOrder(expected)
      }
    }

    "get resources from only one project in the org for user with restricted acls" in {
      val expected = jsonContentOf(
        "/kg/listings/org/filter-project-1.json",
        replacements(
          Rick,
          "org"  -> org1,
          "proj" -> proj12
        ): _*
      )

      deltaClient.get[Json](s"/resources/$org1?type=$projectType", Morty) { (json, response) =>
        response.status shouldEqual StatusCodes.OK
        filterSearchMetadata(json) should equalIgnoreArrayOrder(expected)
      }
    }

    "get an empty list for anonymous" in {
      deltaClient.get[Json](s"/resources/$org1?type=$projectType", Anonymous) { (json, response) =>
        response.status shouldEqual StatusCodes.OK
        listing._total.getOption(json).value shouldEqual 0L
        listing._results.getOption(json).value.size shouldEqual 0
      }
    }
  }

  "Listing resources within all accessible projects in the system" should {
    val testResourceType = URLEncoder.encode("https://bluebrain.github.io/nexus/vocabulary/TestResource", "UTF-8")

    "get resources from all projects for user with appropriate acls" in {
      val expected = jsonContentOf(
        "/kg/listings/all/resource-by-type-4.json",
        replacements(
          Rick,
          "org1"  -> org1,
          "org2"  -> org2,
          "proj1" -> proj11,
          "proj2" -> proj12,
          "proj3" -> proj21
        ): _*
      )

      deltaClient.get[Json](s"/resources?type=$testResourceType", Rick) { (json, response) =>
        response.status shouldEqual StatusCodes.OK
        filterSearchMetadata(json) should equalIgnoreArrayOrder(expected)
      }
    }

    "get resources from only one project for user with restricted acls" in {
      val expected = jsonContentOf(
        "/kg/listings/all/resource-by-type-1.json",
        replacements(
          Rick,
          "org"  -> org1,
          "proj" -> proj12
        ): _*
      )

      deltaClient.get[Json](s"/resources?type=$testResourceType", Morty) { (json, response) =>
        response.status shouldEqual StatusCodes.OK
        filterSearchMetadata(json) should equalIgnoreArrayOrder(expected)
      }
    }

    "get an empty list for anonymous" in {
      deltaClient.get[Json](s"/resources?type=$testResourceType", Anonymous) { (json, response) =>
        response.status shouldEqual StatusCodes.OK
        listing._total.getOption(json).value shouldEqual 0L
        listing._results.getOption(json).value.size shouldEqual 0
      }
    }

  }

  "Listing common errors" should {
    val endpoints = List("/resources", s"/resources/$org1", s"/resources/$ref11", s"/resources/$ref11/test-schema")

    "return 400 when using both 'from' and 'after'" in {
      forAll(endpoints) { endpoint =>
        eventually {
          deltaClient.get[Json](s"$endpoint?from=10&after=%5B%22test%22%5D", Rick) { (json, response) =>
            response.status shouldEqual StatusCodes.BadRequest
            json shouldEqual jsonContentOf("/kg/listings/from-and-after-error.json")
          }
        }
      }
    }

    "return 400 if 'from' is bigger than the limit" in {
      forAll(endpoints) { endpoint =>
        eventually {
          deltaClient.get[Json](s"$endpoint?from=10001", Rick) { (json, response) =>
            response.status shouldEqual StatusCodes.BadRequest
            json shouldEqual jsonContentOf("/kg/listings/from-over-limit-error.json")
          }
        }
      }
    }

  }

}
