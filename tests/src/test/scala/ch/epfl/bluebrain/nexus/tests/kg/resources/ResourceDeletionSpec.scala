package ch.epfl.bluebrain.nexus.tests.kg.resources

import akka.http.scaladsl.model.StatusCodes
import cats.effect.IO
import ch.epfl.bluebrain.nexus.delta.kernel.utils.UrlUtils
import ch.epfl.bluebrain.nexus.tests.Identity.listings.Bob
import ch.epfl.bluebrain.nexus.tests.Optics.listing._total
import ch.epfl.bluebrain.nexus.tests.iam.types.Permission
import ch.epfl.bluebrain.nexus.tests.resources.SimpleResource
import ch.epfl.bluebrain.nexus.tests.{BaseIntegrationSpec, Optics}
import io.circe.Json
import org.scalactic.source.Position

class ResourceDeletionSpec extends BaseIntegrationSpec {

  private val orgId   = genId()
  private val projId  = genId()
  private val project = s"$orgId/$projId"

  // To check if the resource from that id is still in the triple store
  private def query(id: String) =
    s"""
      |SELECT (COUNT(DISTINCT ?s) as ?count) where {
      |  VALUES ?s { <$id> } .
      |  ?s ?p ?o
      |}
      """.stripMargin

  override def beforeAll(): Unit = {
    super.beforeAll()
    createProjects(Bob, orgId, projId).accepted
  }

  private def createResource(payload: Json) = {
    val resourceId = s"http://localhost/$project/${genString()}"
    deltaClient
      .put[Json](s"/resources/$project/_/${UrlUtils.encode(resourceId)}", payload, Bob) { expectCreated }
      .as(resourceId)
  }

  private def assertListing(encodedId: String, exists: Boolean)(implicit position: Position) =
    eventually {
      deltaClient.get[Json](s"/resources/$project?locate=$encodedId", Bob) { (json, response) =>
        response.status shouldEqual StatusCodes.OK
        _total.getOption(json).value shouldEqual (if (exists) 1 else 0)
      } >> IO.unit
    }

  private def countResponse(exists: Boolean) = {
    val count = if (exists) 1 else 0
    json"""
        {
         "head" : {
           "vars" : [
             "count"
           ]
         },
         "results" : {
           "bindings" : [
             {
               "count" : {
                 "datatype" : "http://www.w3.org/2001/XMLSchema#integer",
                 "type" : "literal",
                 "value" : "$count"
               }
             }
           ]
         }
       }"""
  }

  private def assertGraph(id: String, exists: Boolean)(implicit position: Position) = eventually {
    deltaClient.sparqlQuery[Json](s"/views/$project/graph/sparql", query(id), Bob) { (json, response) =>
      response.status shouldEqual StatusCodes.OK
      json shouldEqual countResponse(exists)
    } >> IO.unit
  }

  private def assertSearchGraph(id: String, exists: Boolean)(implicit position: Position) = eventually {
    deltaClient.sparqlQuery[Json](s"/views/$project/search/sparql", query(id), Bob) { (json, response) =>
      response.status shouldEqual StatusCodes.OK
      json shouldEqual countResponse(exists)
    } >> IO.unit
  }

  "Deleting a resource" should {

    "fail when the user has not the right permission" in {
      for {
        payload    <- SimpleResource.sourcePayload(42)
        resourceId <- createResource(payload)
        encodedId   = UrlUtils.encode(resourceId)
        _          <- deltaClient.delete[Json](s"/resources/$project/_/$encodedId?prune=true", Bob) {
                        expectForbidden
                      }
        _          <- deltaClient.getJson[Json](s"/resources/$project/_/$encodedId", Bob)
      } yield succeed
    }

    "succeed when the user has not the right permission" in {
      for {
        _          <- aclDsl.addPermission("/", Bob, Permission.Resources.Delete)
        payload    <- SimpleResource.sourcePayload(42)
        resourceId <- createResource(payload)
        encodedId   = UrlUtils.encode(resourceId)
        // Checking that the resource is indexed before deleting it
        _          <- assertListing(encodedId, exists = true)
        _          <- assertGraph(resourceId, exists = true)
        _          <- assertSearchGraph(resourceId, exists = true)
        // Deleting the resource
        _          <- deltaClient.deleteStatus(s"/resources/$project/_/$encodedId?prune=true", Bob) {
                        _.status shouldEqual StatusCodes.NoContent
                      }
        // Resource can't be fetched
        _          <- deltaClient.get[Json](s"/resources/$project/_/$encodedId", Bob) { expectNotFound }
        // Resource is not indexed in the triple store and Elasticsearch anymore
        _          <- assertListing(encodedId, exists = false)
        _          <- assertGraph(resourceId, exists = false)
        _          <- assertSearchGraph(resourceId, exists = false)
        // History is not available
        _          <- eventually {
                        deltaClient.get[Json](s"/history/resources/$project/$encodedId", Bob) { (json, response) =>
                          response.status shouldEqual StatusCodes.OK
                          Optics._total.getOption(json).value shouldEqual 0L
                        } >> IO.unit
                      }
      } yield succeed
    }
  }
}
