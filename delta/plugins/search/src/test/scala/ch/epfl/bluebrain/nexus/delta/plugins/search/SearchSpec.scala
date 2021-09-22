package ch.epfl.bluebrain.nexus.delta.plugins.search

import akka.actor.ActorSystem
import akka.http.scaladsl.model.Uri.Query
import akka.testkit.TestKit
import cats.implicits._
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.client.SparqlQuery.SparqlConstructQuery
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.CompositeViews
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.model.CompositeView
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.model.CompositeViewProjection.ElasticSearchProjection
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.model.CompositeViewSource.ProjectSource
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.client.ElasticSearchBulk
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.client.ElasticSearchClient.Refresh
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.model.permissions
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.{ElasticSearchClientSetup, Fixtures}
import ch.epfl.bluebrain.nexus.delta.plugins.search.Search.{ListProjections, TargetProjection}
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.nxv
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.ContextValue.ContextObject
import ch.epfl.bluebrain.nexus.delta.rdf.syntax._
import ch.epfl.bluebrain.nexus.delta.sdk.generators.{ProjectGen, ResourceGen}
import ch.epfl.bluebrain.nexus.delta.sdk.model.acls.AclAddress
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Caller
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Identity.{Group, User}
import ch.epfl.bluebrain.nexus.delta.sdk.model.permissions.Permission
import ch.epfl.bluebrain.nexus.delta.sdk.model.{BaseUri, Label, NonEmptySet}
import ch.epfl.bluebrain.nexus.delta.sdk.testkit.{AclSetup, ConfigFixtures}
import ch.epfl.bluebrain.nexus.delta.sourcing.config.ExternalIndexingConfig
import ch.epfl.bluebrain.nexus.testkit._
import com.whisk.docker.scalatest.DockerTestKit
import io.circe.{Json, JsonObject}
import monix.bio.UIO
import org.scalatest.concurrent.Eventually
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike
import org.scalatest.{CancelAfterFailure, Inspectors}

import java.time.Instant
import java.util.UUID
import scala.concurrent.duration._

class SearchSpec
    extends TestKit(ActorSystem("SearchSpec"))
    with AnyWordSpecLike
    with Matchers
    with EitherValuable
    with CirceLiteral
    with TestHelpers
    with CancelAfterFailure
    with Inspectors
    with ElasticSearchClientSetup
    with ConfigFixtures
    with IOValues
    with Eventually
    with Fixtures
    with ElasticSearchDocker
    with DockerTestKit {
  implicit override def patienceConfig: PatienceConfig = PatienceConfig(6.seconds, 100.millis)

  implicit private def externalConfig: ExternalIndexingConfig = externalIndexing
  implicit private val baseUri: BaseUri                       = BaseUri("http://localhost", Label.unsafe("v1"))

  private val realm                  = Label.unsafe("myrealm")
  implicit private val alice: Caller = Caller(User("Alice", realm), Set(User("Alice", realm), Group("users", realm)))
  private val bob: Caller            = Caller(User("Bob", realm), Set(User("Bob", realm), Group("users", realm)))

  private val project1        = ProjectGen.project("org", "proj")
  private val project2        = ProjectGen.project("org2", "proj2")
  private val queryPermission = Permission.unsafe("views/query")

  private val acls = AclSetup
    .init(
      (alice.subject, AclAddress.Project(project1.ref), Set(queryPermission)),
      (bob.subject, AclAddress.Root, Set(queryPermission))
    )
    .accepted

  private val mappings = jsonObjectContentOf("test-mapping.json")

  private val compViewProj1   = CompositeView(
    nxv + "searchView",
    project1.ref,
    NonEmptySet.of(ProjectSource(nxv + "searchSource", UUID.randomUUID(), Set.empty, Set.empty, None, false)),
    NonEmptySet.of(
      ElasticSearchProjection(
        nxv + "searchProjection",
        UUID.randomUUID(),
        SparqlConstructQuery.unsafe(""),
        Set.empty,
        Set.empty,
        None,
        false,
        false,
        permissions.query,
        mappings,
        None,
        ContextObject(JsonObject())
      )
    ),
    None,
    UUID.randomUUID(),
    Map.empty,
    Json.obj()
  )
  private val compViewProj2   = compViewProj1.copy(project = project2.ref, uuid = UUID.randomUUID())
  private val projectionProj1 =
    TargetProjection(compViewProj1.projections.value.head.asElasticSearch.value, compViewProj1, 1L)
  private val projectionProj2 =
    TargetProjection(compViewProj2.projections.value.head.asElasticSearch.value, compViewProj2, 1L)

  private val projections = Seq(
    projectionProj1,
    projectionProj2
  )

  private val tpe1 = nxv + "Type1"

  private val listViews: ListProjections = () => UIO.pure(projections)

  private def createDocuments(proj: TargetProjection): Seq[Json] =
    (0 until 3).map { idx =>
      val resource =
        ResourceGen.resource(iri"https://example.com/${proj.view.uuid}" / idx.toString, proj.view.project, Json.obj())
      ResourceGen
        .resourceFor(resource, types = Set(nxv + idx.toString, tpe1), rev = idx.toLong)
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

  "Search" should {
    val search = Search(listViews, acls, esClient, externalConfig)

    "index documents" in {
      val bulkSeq = projections.foldLeft(Seq.empty[ElasticSearchBulk]) { (bulk, p) =>
        val index   = CompositeViews.index(p.projection, p.view, p.rev, externalConfig.prefix)
        esClient.createIndex(index, Some(mappings), None).accepted
        val newBulk = createDocuments(p).zipWithIndex.map { case (json, idx) =>
          ElasticSearchBulk.Index(index, idx.toString, json)
        }
        bulk ++ newBulk
      }
      esClient.bulk(bulkSeq, Refresh.WaitFor).accepted
    }

    "search all indices" in {
      val results = search.query(jobj"""{"size": 100}""", Query.Empty)(bob).accepted
      extractSources(results).toSet shouldEqual projections.flatMap(createDocuments).toSet
    }

    "search only indices the user has access to" in {
      val results = search.query(jobj"""{"size": 100}""", Query.Empty)(alice).accepted
      extractSources(results).toSet shouldEqual createDocuments(projectionProj1).toSet
    }

  }
}
