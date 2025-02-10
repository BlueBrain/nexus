package ch.epfl.bluebrain.nexus.tests.kg

import akka.http.scaladsl.model.StatusCodes
import ch.epfl.bluebrain.nexus.delta.kernel.utils.UrlUtils
import ch.epfl.bluebrain.nexus.tests.Identity.projects.{Bojack, PrincessCarolyn}
import ch.epfl.bluebrain.nexus.tests.Identity.{Anonymous, ServiceAccount}
import ch.epfl.bluebrain.nexus.tests.Optics.{admin, listing, supervision}
import ch.epfl.bluebrain.nexus.tests.iam.types.Permission.{Events, Organizations, Projects, Resources}
import ch.epfl.bluebrain.nexus.tests.kg.files.model.FileInput
import ch.epfl.bluebrain.nexus.tests.resources.SimpleResource
import ch.epfl.bluebrain.nexus.tests.{BaseIntegrationSpec, Identity, SchemaPayload}
import io.circe.Json
import io.circe.optics.JsonPath.root

import java.io.File
import scala.reflect.io.Directory

final class ProjectsDeletionSpec extends BaseIntegrationSpec {

  private val org   = genId()
  private val proj1 = genId()
  private val proj2 = genId()
  private val ref1  = s"$org/$proj1"
  private val ref2  = s"$org/$proj2"

  private val ref1Iri = s"${config.deltaUri}/projects/$ref1"

  private val elasticsearchViewId        = "http://localhost/nexus/default-view"
  private val encodedElasticsearchViewId = UrlUtils.encode(elasticsearchViewId)

  private var elasticsearchViewsRef1Uuids = List.empty[String]
  private var blazegraphViewsRef1Uuids    = List.empty[String]
  private var compositeViewsRef1Uuids     = List.empty[String]

  private def graphAnalyticsIndex(org: String, project: String) =
    s"delta_ga_${org}_$project"

  "Setting up" should {
    "succeed in setting up orgs, projects and acls" in {
      for {
        _            <- aclDsl.addPermissions("/", Bojack, Set(Organizations.Create, Projects.Delete, Resources.Read, Events.Read))
        // First org and projects
        _            <- adminDsl.createOrganization(org, org, Bojack)
        _            <- adminDsl.createProjectWithName(org, proj1, name = proj1, Bojack)
        _            <- adminDsl.createProjectWithName(org, proj2, name = proj2, Bojack)
        _            <- aclDsl.addPermission(s"/$ref1", PrincessCarolyn, Resources.Read)
        esViewPayload = jsonContentOf("kg/views/elasticsearch/pipeline.json", "withTag" -> false)
        _            <- deltaClient.put[Json](s"/views/$ref1/$encodedElasticsearchViewId", esViewPayload, Bojack) { expectCreated }
        _            <- deltaClient.put[Json](s"/views/$ref2/$encodedElasticsearchViewId", esViewPayload, Bojack) { expectCreated }
      } yield succeed
    }

    s"have resources for $ref1" in eventually {
      deltaClient.get[Json](s"/resources/$ref1", Bojack) { (json, _) =>
        listing._total.getOption(json).value should be > 0L
      }
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
        indices.exists(_.contains(graphAnalyticsIndex(org, proj1))) shouldEqual true
      }
    }

    "add additional resources" in {
      val resourcePayload             = SimpleResource.sourcePayload(5).accepted
      val schemaPayload               = SchemaPayload.loadSimple().accepted
      val resolverPayload             =
        jsonContentOf(
          "kg/resources/cross-project-resolver.json",
          replacements(Bojack, "project" -> ref2): _*
        )
      val aggregateSparqlPayload      =
        jsonContentOf("kg/views/agg-sparql-view.json", "project1" -> ref1, "project2" -> ref2)

      implicit val identity: Identity = Bojack

      for {
        _ <- deltaClient.put[Json](s"/resources/$ref1/_/resource11", resourcePayload, Bojack)(expectCreated)
        _ <- deltaClient.put[Json](s"/schemas/$ref1/test-schema", schemaPayload, Bojack)(expectCreated)
        _ <- deltaClient.post[Json](s"/resolvers/$ref1", resolverPayload, Bojack)(expectCreated)
        _ <- deltaClient.put[Json](s"/views/$ref1/sparqlAggView", aggregateSparqlPayload, Bojack)(expectCreated)
        _ <- elasticsearchViewsDsl.aggregate(
               "esAggView",
               ref1,
               Bojack,
               ref1 -> elasticsearchViewId,
               ref2 -> elasticsearchViewId
             )
        _ <- deltaClient.uploadFile(ref1, None, FileInput.randomTextFile, None)(expectCreated)
        _ <- deltaClient.uploadFile(ref2, None, FileInput.randomTextFile, None)(expectCreated)
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
                    "reason" : "Project '$ref2' can't be deleted as it is referenced by projects '$ref1'.",
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
        deleteResponse.status shouldEqual StatusCodes.OK
        admin._markedForDeletion.getOption(deleteJson).value shouldEqual true
        admin._rev.getOption(deleteJson).value shouldEqual 2L
      }
    }

    "return a not found when fetching deleted project" in eventually {
      deltaClient.get[Json](s"/projects/$ref1", Bojack)(expect(StatusCodes.NotFound))
    }

    "not return the deleted project in the project list" in eventually {
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

    "not return any acl under the project path" in {
      aclDsl.fetch(s"/*/*", Identity.ServiceAccount, ancestors = true) { acls =>
        acls._results.foreach { acl =>
          acl._path should not equal s"/$ref1"
        }
        succeed
      }
    }

    "not return any resource sse event from the deleted project" in {
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

    "not return any acl sse event from the deleted project" in {
      deltaClient.sseEvents(s"/acls/events", ServiceAccount, None) { events =>
        events.foreach {
          case (_, Some(json)) =>
            root._path.string.exist(_ == s"/$ref1")(json) shouldEqual false withClue events
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
      blazegraphDsl.allNamespaces.map { namespaces =>
        blazegraphViewsRef1Uuids.forall { uuid => namespaces.exists(_.contains(uuid)) } shouldEqual false
      }
    }

    "have deleted elasticsearch indices and blazegraph namespaces for composite views for the project" in {
      for {
        _ <- elasticsearchDsl.allIndices.map { indices =>
               compositeViewsRef1Uuids.forall { uuid =>
                 indices.exists(_.contains(uuid))
               } shouldEqual false
             }
        _ <- blazegraphDsl.allNamespaces.map { namespaces =>
               compositeViewsRef1Uuids.forall { uuid => namespaces.exists(_.contains(uuid)) } shouldEqual false
             }
      } yield succeed
    }

    "have deleted elasticsearch indices for graph analytics for the project" in {
      elasticsearchDsl.allIndices.map { indices =>
        indices.exists(_.contains(graphAnalyticsIndex(org, proj1))) shouldEqual false
      }
    }

    "have deleted the default storage folder" in {
      val proj1Directory = new Directory(new File(s"/tmp/$ref1"))
      proj1Directory.exists shouldEqual false
      val proj2Directory = new Directory(new File(s"/tmp/$ref2"))
      proj2Directory.exists shouldEqual true
    }

    "have stopped all the projections related to the project" in {
      deltaClient.get[Json](s"/supervision/projections", ServiceAccount) { (json, response) =>
        response.status shouldEqual StatusCodes.OK
        supervision.allProjects.string.getAll(json) should not contain ref1
        supervision.allProjects.string.getAll(json) should contain(ref2)
      }
    }

    "succeed for a previously referenced project" in eventually {
      deltaClient.delete[Json](s"/projects/$ref2?rev=1&prune=true", Bojack) { (deleteJson, deleteResponse) =>
        deleteResponse.status shouldEqual StatusCodes.OK
        admin._markedForDeletion.getOption(deleteJson).value shouldEqual true
      }
    }

    "succeed in creating the project again" in {
      adminDsl.createProjectWithName(org, proj1, name = proj1, Bojack)
    }
  }

}
