package ch.epfl.bluebrain.nexus.delta.plugins.search

import akka.actor.ActorSystem
import akka.http.scaladsl.model.Uri.Query
import akka.testkit.TestKit
import cats.data.NonEmptyList
import cats.effect.IO
import cats.implicits._
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.indexing._
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.model.CompositeView
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.model.CompositeViewProjection.ElasticSearchProjection
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.model.CompositeViewSource.ProjectSource
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.client.ElasticSearchAction
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.client.ElasticSearchClient.Refresh
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.client.IndexLabel.IndexGroup
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.model.permissions
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.{Fixtures, ScalaTestElasticSearchClientSetup}
import ch.epfl.bluebrain.nexus.delta.plugins.search.Search.{ListProjections, TargetProjection}
import ch.epfl.bluebrain.nexus.delta.plugins.search.model.SearchRejection.UnknownSuite
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.nxv
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.ContextValue.ContextObject
import ch.epfl.bluebrain.nexus.delta.rdf.query.SparqlQuery.SparqlConstructQuery
import ch.epfl.bluebrain.nexus.delta.rdf.syntax._
import ch.epfl.bluebrain.nexus.delta.sdk.ConfigFixtures
import ch.epfl.bluebrain.nexus.delta.sdk.acls.AclSimpleCheck
import ch.epfl.bluebrain.nexus.delta.sdk.acls.model.AclAddress
import ch.epfl.bluebrain.nexus.delta.sdk.generators.ResourceGen
import ch.epfl.bluebrain.nexus.delta.sdk.identities.model.Caller
import ch.epfl.bluebrain.nexus.delta.sdk.model.{BaseUri, Tags}
import ch.epfl.bluebrain.nexus.delta.sdk.permissions.model.Permission
import ch.epfl.bluebrain.nexus.delta.sdk.views.IndexingRev
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.{Group, User}
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{IriFilter, Label, ProjectRef}
import ch.epfl.bluebrain.nexus.testkit.elasticsearch.ElasticSearchDocker
import ch.epfl.bluebrain.nexus.testkit.scalatest.ce.CatsEffectSpec
import io.circe.{Json, JsonObject}
import org.scalatest.CancelAfterFailure
import org.scalatest.concurrent.Eventually

import java.time.Instant
import java.util.UUID
import scala.concurrent.duration._

class SearchSpec
    extends TestKit(ActorSystem("SearchSpec"))
    with CatsEffectSpec
    with CancelAfterFailure
    with ScalaTestElasticSearchClientSetup
    with ConfigFixtures
    with Eventually
    with Fixtures
    with ElasticSearchDocker {

  override val docker: ElasticSearchDocker = this

  implicit override def patienceConfig: PatienceConfig = PatienceConfig(6.seconds, 100.millis)

  implicit private val baseUri: BaseUri = BaseUri("http://localhost", Label.unsafe("v1"))

  private val realm                  = Label.unsafe("myrealm")
  implicit private val alice: Caller = Caller(User("Alice", realm), Set(User("Alice", realm), Group("users", realm)))
  private val bob: Caller            = Caller(User("Bob", realm), Set(User("Bob", realm), Group("users", realm)))

  private val project1        = ProjectRef.unsafe("org", "proj")
  private val project2        = ProjectRef.unsafe("org2", "proj2")
  private val queryPermission = Permission.unsafe("views/query")

  private val aclCheck = AclSimpleCheck(
    (alice.subject, AclAddress.Project(project1), Set(queryPermission)),
    (bob.subject, AclAddress.Root, Set(queryPermission))
  ).accepted

  private val mappings = jsonObjectContentOf("test-mapping.json")

  private val esProjection = ElasticSearchProjection(
    nxv + "searchProjection",
    UUID.randomUUID(),
    IndexingRev.init,
    SparqlConstructQuery.unsafe("CONSTRUCT ..."),
    IriFilter.None,
    IriFilter.None,
    false,
    false,
    false,
    permissions.query,
    Some(IndexGroup.unsafe("search")),
    mappings,
    None,
    ContextObject(JsonObject())
  )

  private val compViewProj1   = CompositeView(
    nxv + "searchView",
    project1,
    NonEmptyList.of(
      ProjectSource(nxv + "searchSource", UUID.randomUUID(), IriFilter.None, IriFilter.None, None, false)
    ),
    NonEmptyList.of(esProjection),
    None,
    UUID.randomUUID(),
    Tags.empty,
    Json.obj(),
    Instant.EPOCH
  )
  private val compViewProj2   = compViewProj1.copy(project = project2, uuid = UUID.randomUUID())
  private val projectionProj1 = TargetProjection(esProjection, compViewProj1)
  private val projectionProj2 = TargetProjection(esProjection, compViewProj2)

  private val projections = Seq(projectionProj1, projectionProj2)

  private val listViews: ListProjections = () => IO.pure(projections)

  private val allSuite   = Label.unsafe("allSuite")
  private val proj1Suite = Label.unsafe("proj1Suite")
  private val proj2Suite = Label.unsafe("proj2Suite")
  private val allSuites  = Map(
    allSuite   -> Set(project1, project2),
    proj1Suite -> Set(project1),
    proj2Suite -> Set(project2)
  )

  private val tpe1 = nxv + "Type1"

  private def createDocuments(proj: TargetProjection): Seq[Json] =
    (0 until 3).map { idx =>
      val resource =
        ResourceGen.resource(iri"https://example.com/${proj.view.uuid}" / idx.toString, proj.view.project, Json.obj())
      ResourceGen
        .resourceFor(resource, types = Set(nxv + idx.toString, tpe1), rev = idx)
        .copy(createdAt = Instant.EPOCH.plusSeconds(idx.toLong))
        .toCompactedJsonLd
        .accepted
        .json
    }

  private def extractSources(json: Json) = {
    json.hcursor
      .downField("hits")
      .get[Vector[Json]]("hits")
      .flatMap(seq => seq.traverse(_.hcursor.get[Json]("_source")))
      .rightValue
  }

  val project1Documents = createDocuments(projectionProj1).toSet
  val project2Documents = createDocuments(projectionProj2).toSet
  val allDocuments      = project1Documents ++ project2Documents

  override def beforeAll(): Unit = {
    super.beforeAll()
    val bulkSeq = projections.foldLeft(Seq.empty[ElasticSearchAction]) { (bulk, p) =>
      val index   = projectionIndex(p.projection, p.view.uuid, prefix)
      esClient.createIndex(index, Some(mappings), None).accepted
      val newBulk = createDocuments(p).zipWithIndex.map { case (json, idx) =>
        ElasticSearchAction.Index(index, idx.toString, json)
      }
      bulk ++ newBulk
    }
    esClient.bulk(bulkSeq, Refresh.WaitFor).void.accepted
  }

  private val prefix = "prefix"

  "Search" should {
    lazy val search = Search(listViews, aclCheck, esClient, prefix, allSuites)

    val matchAll     = jobj"""{"size": 100}"""
    val noParameters = Query.Empty

    "search all indices accordingly to Bob's full access" in {
      val results = search.query(matchAll, noParameters)(bob).accepted
      extractSources(results).toSet shouldEqual allDocuments
    }

    "search only the project 1 index accordingly to Alice's restricted access" in {
      val results = search.query(matchAll, noParameters)(alice).accepted
      extractSources(results).toSet shouldEqual project1Documents
    }

    "search within an unknown suite" in {
      search.query(Label.unsafe("xxx"), Set.empty, matchAll, noParameters)(bob).rejectedWith[UnknownSuite]
    }

    List(
      (allSuite, allDocuments),
      (proj2Suite, project2Documents)
    ).foreach { case (suite, expected) =>
      s"search within suite $suite accordingly to Bob's full access" in {
        val results = search.query(suite, Set.empty, matchAll, noParameters)(bob).accepted
        extractSources(results).toSet shouldEqual expected
      }
    }

    List(
      (allSuite, project1Documents),
      (proj2Suite, Set.empty)
    ).foreach { case (suite, expected) =>
      s"search within suite $suite accordingly to Alice's restricted access" in {
        val results = search.query(suite, Set.empty, matchAll, noParameters)(alice).accepted
        extractSources(results).toSet shouldEqual expected
      }
    }

    "Search on proj2Suite and add project1 as an extra project accordingly to Bob's full access" in {
      val results = search.query(proj2Suite, Set(project1), matchAll, noParameters)(bob).accepted
      extractSources(results).toSet shouldEqual allDocuments
    }

    "Search on proj1Suite and add project2 as an extra project accordingly to Alice's restricted access" in {
      val results = search.query(proj1Suite, Set(project2), matchAll, noParameters)(alice).accepted
      extractSources(results).toSet shouldEqual project1Documents
    }

    "Search on proj2Suite and add project1 as an extra project accordingly to Alice's restricted access" in {
      val results = search.query(proj2Suite, Set(project1), matchAll, noParameters)(alice).accepted
      extractSources(results).toSet shouldEqual project1Documents
    }
  }
}
