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
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.client.ElasticSearchBulk
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
import ch.epfl.bluebrain.nexus.delta.sdk.generators.{ProjectGen, ResourceGen}
import ch.epfl.bluebrain.nexus.delta.sdk.identities.model.Caller
import ch.epfl.bluebrain.nexus.delta.sdk.model.{BaseUri, Tags}
import ch.epfl.bluebrain.nexus.delta.sdk.permissions.model.Permission
import ch.epfl.bluebrain.nexus.delta.sdk.views.IndexingRev
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.{Group, User}
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Label
import ch.epfl.bluebrain.nexus.testkit._
import ch.epfl.bluebrain.nexus.testkit.ce.{CatsEffectScalaTestAssertions, CatsIOValues}
import ch.epfl.bluebrain.nexus.testkit.elasticsearch.ElasticSearchDocker
import io.circe.{Json, JsonObject}
import org.scalatest.concurrent.Eventually
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike
import org.scalatest.{CancelAfterFailure, Inspectors, OptionValues}

import java.time.Instant
import java.util.UUID
import scala.concurrent.duration._

class SearchSpec
    extends TestKit(ActorSystem("SearchSpec"))
    with AnyWordSpecLike
    with Matchers
    with EitherValuable
    with OptionValues
    with CirceLiteral
    with TestHelpers
    with CancelAfterFailure
    with Inspectors
    with ScalaTestElasticSearchClientSetup
    with ConfigFixtures
    with CatsIOValues
    with CatsEffectScalaTestAssertions
    with IOValues
    with Eventually
    with Fixtures
    with ElasticSearchDocker {

  override val docker: ElasticSearchDocker = this

  implicit override def patienceConfig: PatienceConfig = PatienceConfig(6.seconds, 100.millis)

  implicit private val baseUri: BaseUri = BaseUri("http://localhost", Label.unsafe("v1"))

  private val realm                  = Label.unsafe("myrealm")
  implicit private val alice: Caller = Caller(User("Alice", realm), Set(User("Alice", realm), Group("users", realm)))
  private val bob: Caller            = Caller(User("Bob", realm), Set(User("Bob", realm), Group("users", realm)))

  private val project1        = ProjectGen.project("org", "proj")
  private val project2        = ProjectGen.project("org2", "proj2")
  private val queryPermission = Permission.unsafe("views/query")

  private val aclCheck = AclSimpleCheck(
    (alice.subject, AclAddress.Project(project1.ref), Set(queryPermission)),
    (bob.subject, AclAddress.Root, Set(queryPermission))
  ).accepted

  private val mappings = jsonObjectContentOf("test-mapping.json")

  private val esProjection = ElasticSearchProjection(
    nxv + "searchProjection",
    UUID.randomUUID(),
    IndexingRev.init,
    SparqlConstructQuery.unsafe("CONSTRUCT ..."),
    Set.empty,
    Set.empty,
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
    project1.ref,
    NonEmptyList.of(ProjectSource(nxv + "searchSource", UUID.randomUUID(), Set.empty, Set.empty, None, false)),
    NonEmptyList.of(esProjection),
    None,
    UUID.randomUUID(),
    Tags.empty,
    Json.obj(),
    Instant.EPOCH
  )
  private val compViewProj2   = compViewProj1.copy(project = project2.ref, uuid = UUID.randomUUID())
  private val projectionProj1 = TargetProjection(esProjection, compViewProj1)
  private val projectionProj2 = TargetProjection(esProjection, compViewProj2)

  private val projections = Seq(projectionProj1, projectionProj2)

  private val listViews: ListProjections = () => IO.pure(projections)

  private val allSuite   = Label.unsafe("allSuite")
  private val proj2Suite = Label.unsafe("proj2Suite")
  private val allSuites  = Map(
    allSuite   -> Set(project1.ref, project2.ref),
    proj2Suite -> Set(project2.ref)
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

  private val prefix = "prefix"

  "Search" should {
    lazy val search = Search(listViews, aclCheck, esClient, prefix, allSuites)

    val matchAll     = jobj"""{"size": 100}"""
    val noParameters = Query.Empty

    val project1Documents = createDocuments(projectionProj1).toSet
    val project2Documents = createDocuments(projectionProj2).toSet
    val allDocuments      = project1Documents ++ project2Documents

    "index documents" in {
      val bulkSeq = projections.foldLeft(Seq.empty[ElasticSearchBulk]) { (bulk, p) =>
        val index   = projectionIndex(p.projection, p.view.uuid, prefix)
        esClient.createIndex(index, Some(mappings), None).accepted
        val newBulk = createDocuments(p).zipWithIndex.map { case (json, idx) =>
          ElasticSearchBulk.Index(index, idx.toString, json)
        }
        bulk ++ newBulk
      }
      esClient.bulk(bulkSeq, Refresh.WaitFor).accepted
    }

    "search all indices accordingly to Bob's full access" in {
      val results = search.query(matchAll, noParameters)(bob).accepted
      extractSources(results).toSet shouldEqual allDocuments
    }

    "search only the project 1 index accordingly to Alice's restricted access" in {
      val results = search.query(matchAll, noParameters)(alice).accepted
      extractSources(results).toSet shouldEqual project1Documents
    }

    "search within an unknown suite" in {
      search.query(Label.unsafe("xxx"), matchAll, noParameters)(bob).rejectedWith[UnknownSuite]
    }

    List(
      (allSuite, allDocuments),
      (proj2Suite, project2Documents)
    ).foreach { case (suite, expected) =>
      s"search within suite $suite accordingly to Bob's full access" in {
        val results = search.query(suite, matchAll, noParameters)(bob).accepted
        extractSources(results).toSet shouldEqual expected
      }
    }

    List(
      (allSuite, project1Documents),
      (proj2Suite, Set.empty)
    ).foreach { case (suite, expected) =>
      s"search within suite $suite accordingly to Alice's restricted access" in {
        val results = search.query(suite, matchAll, noParameters)(alice).accepted
        extractSources(results).toSet shouldEqual expected
      }
    }
  }
}
