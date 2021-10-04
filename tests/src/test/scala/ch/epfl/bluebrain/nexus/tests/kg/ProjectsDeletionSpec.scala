package ch.epfl.bluebrain.nexus.tests.kg

import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.model.headers.Location
import ch.epfl.bluebrain.nexus.testkit.{CirceEq, EitherValuable}
import ch.epfl.bluebrain.nexus.tests.BaseSpec
import ch.epfl.bluebrain.nexus.tests.Identity.Anonymous
import ch.epfl.bluebrain.nexus.tests.Identity.projects.Bojack
import ch.epfl.bluebrain.nexus.tests.Optics.{admin, listing}
import ch.epfl.bluebrain.nexus.tests.iam.types.Permission.{Events, Organizations, Projects, Resources}
import io.circe.Json
import io.circe.optics.JsonPath.root
import org.scalatest.AppendedClues

import java.net.URLEncoder
import java.nio.charset.StandardCharsets

final class ProjectsDeletionSpec extends BaseSpec with CirceEq with EitherValuable with AppendedClues {

  private val org   = genId()
  private val proj1 = genId()
  private val proj2 = genId()
  private val ref1  = s"$org/$proj1"
  private val ref2  = s"$org/$proj2"

  private val ref1Iri = s"${config.deltaUri}/projects/$ref1"

  private var elasticsearchViewsRef1Uuids          = List.empty[String]
  private var blazegraphViewsRef1Uuids             = List.empty[String]
  private var compositeViewsRef1Uuids              = List.empty[String]

  private def graphAnalyticsIndex(project: String) =
    s"${URLEncoder.encode(project, StandardCharsets.UTF_8).toLowerCase}_graph_analytics"

  "Setting up" should {
    "succeed in setting up orgs, projects and acls" in {
      for {
        _ <- aclDsl.addPermissions("/", Bojack, Set(Organizations.Create, Projects.Delete, Resources.Read, Events.Read))
        // First org and projects
        _ <- adminDsl.createOrganization(org, org, Bojack)
        _ <- adminDsl.createProject(org, proj1, kgDsl.projectJson(name = proj1), Bojack)
        _ <- adminDsl.createProject(org, proj2, kgDsl.projectJson(name = proj2), Bojack)
      } yield succeed
    }

    "wait for elasticsearch views to be created" in eventually {
      for {
        uuids   <- deltaClient
                     .getJson[Json](s"/views/$ref1?type=nxv:ElasticSearchView", Bojack)
                     .map(listing.eachResult._uuid.string.getAll(_))
        indices <- elasticsearchDsl.allIndices
      } yield {
        uuids should not be empty
        uuids.forall { uuid => indices.exists(_.contains(uuid)) } shouldEqual true
        elasticsearchViewsRef1Uuids = uuids
        succeed
      }
    }

    "wait for blazegraph views to be created" in eventually {
      for {
        uuids      <- deltaClient
                        .getJson[Json](s"/views/$ref1?type=nxv:SparqlView", Bojack)
                        .map(listing.eachResult._uuid.string.getAll(_))
        namespaces <- blazegraphDsl.allNamespaces
      } yield {
        uuids should not be empty
        uuids.forall { uuid => namespaces.exists(_.contains(uuid)) } shouldEqual true
        blazegraphViewsRef1Uuids = uuids
        succeed
      }
    }

    "wait for composite views to be created" in eventually {
      for {
        uuids      <- deltaClient
                        .getJson[Json](s"/views/$ref1?type=nxv:CompositeView", Bojack)
                        .map(listing.eachResult._uuid.string.getAll(_))
        indices    <- elasticsearchDsl.allIndices
        namespaces <- blazegraphDsl.allNamespaces
      } yield {
        uuids should not be empty
        uuids.forall { uuid =>
          namespaces.exists(_.contains(uuid)) && indices.exists(_.contains(uuid))
        } shouldEqual true
        compositeViewsRef1Uuids = uuids
        succeed
      }
    }

    "wait for graph analytics index to be created" in eventually {
      elasticsearchDsl.allIndices.map { indices =>
        indices.exists(_.contains(graphAnalyticsIndex(ref1))) shouldEqual true
      }
    }

    "add additional resources" in {
      val resourcePayload        =
        jsonContentOf(
          "/kg/resources/simple-resource.json",
          "priority" -> "5"
        )
      val schemaPayload          = jsonContentOf("/kg/schemas/simple-schema.json")
      val resolverPayload        =
        jsonContentOf(
          "/kg/resources/cross-project-resolver.json",
          replacements(Bojack, "project" -> ref2): _*
        )
      val aggregateSparqlPayload =
        jsonContentOf("/kg/views/agg-sparql-view.json", "project1" -> ref1, "project2" -> ref2)

      for {
        _ <- deltaClient.put[Json](s"/resources/$ref1/_/resource11", resourcePayload, Bojack)(expectCreated)
        _ <- deltaClient.put[Json](s"/schemas/$ref1/test-schema", schemaPayload, Bojack)(expectCreated)
        _ <- deltaClient.post[Json](s"/resolvers/$ref1", resolverPayload, Bojack)(expectCreated)
        _ <- deltaClient.put[Json](s"/views/$ref1/sparqlAggView", aggregateSparqlPayload, Bojack)(expectCreated)
        _ <- elasticsearchViewsDsl.aggregate(
               "esAggView",
               ref1,
               Bojack,
               ref1 -> "https://bluebrain.github.io/nexus/vocabulary/defaultElasticSearchIndex",
               ref2 -> "https://bluebrain.github.io/nexus/vocabulary/defaultElasticSearchIndex"
             )
      } yield succeed
    }
  }

  "Deleting projects" should {
    "fail if permission for the user is missing" in {
      deltaClient.delete[Json](s"/projects/$ref1?rev=1", Anonymous)(expectForbidden)
    }

    "fail if the project is referenced by resources in other projects" in {
      deltaClient.delete[Json](s"/projects/$ref2?rev=1&prune=true", Bojack) { (json, response) =>
        response.status shouldEqual StatusCodes.BadRequest
        json should equalIgnoreArrayOrder(
          json"""
                  {
                    "@context" : "https://bluebrain.github.io/nexus/contexts/error.json",
                    "@type" : "ProjectIsReferenced",
                    "reason" : "Project $ref2 can't be deleted as it is referenced by projects '$ref1'.",
                    "referencedBy" : {
                      "$ref1" : [
                        "http://localhost/resolver",
                        "${config.deltaUri}/resources/$proj1/_/sparqlAggView",
                        "${config.deltaUri}/resources/$proj1/_/esAggView"
                      ]
                    }
                 }
              """
        )

      }
    }

    "succeed for a non-referenced project" in {
      deltaClient.delete[Json](s"/projects/$ref1?rev=1&prune=true", Bojack) { (deleteJson, deleteResponse) =>
        deleteResponse.status shouldEqual StatusCodes.SeeOther
        admin._markedForDeletion.getOption(deleteJson).value shouldEqual true
        admin._rev.getOption(deleteJson).value shouldEqual 2L
        val deletionProgress =
          deleteResponse.header[Location].value.uri.toString().replace(config.deltaUri.toString(), "")
        runTask {
          deltaClient.get[Json](deletionProgress, Bojack) { (progressJson, progressResponse) =>
            progressResponse.status shouldEqual StatusCodes.OK
            admin.progress.getOption(progressJson).value shouldEqual "Deleting"
            admin._finished.getOption(progressJson).value shouldEqual false
          } >> {
            // Now, we check for deletion completion

            def next(json: Json) =
              admin._finished.getOption(json).flatMap {
                case true  => None
                case false => Some(deletionProgress)
              }

            def lens(json: Json) = admin.progress.getOption(json)

            val progress = deltaClient
              .stream(
                deletionProgress,
                next,
                lens,
                Bojack
              )
              .compile
              .toList

            progress.map { p =>
              p.lastOption.value.value shouldEqual "ResourcesDeleted"
            }
          }
        }
      }
    }

    "return the project in the deletions endpoint" in {
      deltaClient.get[Json](s"/projects/deletions", Bojack) { (json, _) =>
        listing.eachResult._project.string.exist(_ == ref1)(json) shouldEqual true
      }
    }

    "return a not found when fetching deleted project" in {
      deltaClient.get[Json](s"/projects/$ref1", Bojack)(expect(StatusCodes.NotFound))
    }

    "not return the deleted project in the project list" in {
      deltaClient.get[Json](s"/projects/$org", Bojack) { (json, _) =>
        listing._total.getOption(json).value shouldEqual 1L
        listing.eachResult._label.string.exist(_ == proj1)(json) shouldEqual false
      }
    }

    "not return any resource from this project" in {
      deltaClient.get[Json](s"/resources", Bojack) { (json, _) =>
        listing.eachResult._project.string.exist(_ == ref1Iri)(json) shouldEqual false
      }
    }

    "not return any sse event from the deleted project" in {
      deltaClient.sseEvents(s"/resources/$org/events", Bojack, None) { events =>
        events.foreach {
          case (_, Some(json)) =>
            root._projectId.string.exist(_ == ref1Iri)(json) shouldEqual false withClue events
            root._project.string.exist(_ == ref1Iri)(json) shouldEqual false withClue events
          case (_, None)       =>
            fail("Every event should have a payload")
        }
        succeed
      }
    }

    "have deleted elasticsearch indices for es views for the project" in {
      elasticsearchDsl.allIndices.map { indices =>
        elasticsearchViewsRef1Uuids.forall { uuid => indices.exists(_.contains(uuid)) } shouldEqual false
      }
    }

    "have deleted blazegraph namespaces for blazegraph views for the project" in {
      blazegraphDsl.allNamespaces.map { indices =>
        blazegraphViewsRef1Uuids.forall { uuid => indices.exists(_.contains(uuid)) } shouldEqual false
      }
    }

    "have deleted elasticsearch indices and blazegraph namespaces for composite views for the project" in {
      for {
        _ <- elasticsearchDsl.allIndices.map { indices =>
               compositeViewsRef1Uuids.forall { uuid => indices.exists(_.contains(uuid)) } shouldEqual false
             }
        _ <- blazegraphDsl.allNamespaces.map { indices =>
               compositeViewsRef1Uuids.forall { uuid => indices.exists(_.contains(uuid)) } shouldEqual false
             }
      } yield succeed
    }

    "have deleted elasticsearch indices for graph analytics for the project" in eventually {
      elasticsearchDsl.allIndices.map { indices =>
        indices.exists(_.contains(graphAnalyticsIndex(ref1))) shouldEqual false
      }
    }

    "succeed for a previously referenced project" in {
      deltaClient.delete[Json](s"/projects/$ref2?rev=1&prune=true", Bojack) { (deleteJson, deleteResponse) =>
        deleteResponse.status shouldEqual StatusCodes.SeeOther
        admin._markedForDeletion.getOption(deleteJson).value shouldEqual true
      }
    }

//    "succeed in creating the project again" in {
//      adminDsl.createProject(org, proj1, kgDsl.projectJson(name = proj1), Bojack)
//    }
  }

}
