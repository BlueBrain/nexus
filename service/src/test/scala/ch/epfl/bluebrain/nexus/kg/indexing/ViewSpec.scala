package ch.epfl.bluebrain.nexus.kg.indexing

import java.time.{Clock, Instant, ZoneId}
import java.util.UUID
import java.util.regex.Pattern.quote

import akka.actor.ActorSystem
import akka.testkit.TestKit
import cats.effect.IO
import ch.epfl.bluebrain.nexus.commons.search.FromPagination
import ch.epfl.bluebrain.nexus.commons.search.QueryResult.UnscoredQueryResult
import ch.epfl.bluebrain.nexus.commons.search.QueryResults.UnscoredQueryResults
import ch.epfl.bluebrain.nexus.commons.sparql.client.{BlazegraphClient, SparqlResults}
import ch.epfl.bluebrain.nexus.commons.test.io.IOValues
import ch.epfl.bluebrain.nexus.commons.test.{CirceEq, Resources}
import ch.epfl.bluebrain.nexus.iam.client.config.IamClientConfig
import ch.epfl.bluebrain.nexus.iam.client.types.Identity.Anonymous
import ch.epfl.bluebrain.nexus.kg.TestHelper
import ch.epfl.bluebrain.nexus.kg.config.Contexts._
import ch.epfl.bluebrain.nexus.kg.config.{KgConfig, Settings}
import ch.epfl.bluebrain.nexus.kg.config.Vocabulary._
import ch.epfl.bluebrain.nexus.kg.indexing.View.CompositeView.Projection.{ElasticSearchProjection, SparqlProjection}
import ch.epfl.bluebrain.nexus.kg.indexing.View.CompositeView.Source.{CrossProjectEventStream, ProjectEventStream}
import ch.epfl.bluebrain.nexus.kg.indexing.View.CompositeView.{Interval, Projection, Source}
import ch.epfl.bluebrain.nexus.kg.indexing.View._
import ch.epfl.bluebrain.nexus.kg.indexing.View.Filter
import ch.epfl.bluebrain.nexus.kg.indexing.ViewEncoder._
import ch.epfl.bluebrain.nexus.kg.resources.Rejection.InvalidResourceFormat
import ch.epfl.bluebrain.nexus.kg.resources.Id
import ch.epfl.bluebrain.nexus.kg.resources.syntax._
import ch.epfl.bluebrain.nexus.kg.resources.ProjectIdentifier.{ProjectLabel, ProjectRef}
import ch.epfl.bluebrain.nexus.rdf.Iri.AbsoluteIri
import ch.epfl.bluebrain.nexus.rdf.implicits._
import io.circe.Json
import org.mockito.{ArgumentMatchersSugar, IdiomaticMockito}
import org.mockito.Mockito.when
import org.scalatest.{BeforeAndAfter, Inspectors, OptionValues}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

import scala.concurrent.duration._

class ViewSpec
    extends TestKit(ActorSystem("ViewSpec"))
    with AnyWordSpecLike
    with Matchers
    with OptionValues
    with Resources
    with TestHelper
    with Inspectors
    with BeforeAndAfter
    with CirceEq
    with IdiomaticMockito
    with ArgumentMatchersSugar
    with IOValues {

  implicit private val clock: Clock                     = Clock.fixed(Instant.ofEpochSecond(3600), ZoneId.systemDefault())
  implicit private val appConfig: KgConfig              = Settings(system).appConfig
  implicit private val iamClientConfig: IamClientConfig = IamClientConfig(genIri, genIri, "prefix")
  implicit private val client: BlazegraphClient[IO]     = mock[BlazegraphClient[IO]]

  "A View" when {

    def compositeview(
        id1: AbsoluteIri = url"http://example.com/es",
        id2: AbsoluteIri = url"http://example.com/sparql",
        source1Id: AbsoluteIri = url"http://example.com/source1",
        source2Id: AbsoluteIri = url"http://example.com/source2"
    ) =
      jsonContentOf(
        "/view/composite-view.json",
        Map(
          quote("{projection1_id}") -> id1.asString,
          quote("{projection2_id}") -> id2.asString,
          quote("{source1_id}")     -> source1Id.asString,
          quote("{source2_id}")     -> source2Id.asString
        )
      ).appendContextOf(viewCtx)

    val mapping              = jsonContentOf("/elasticsearch/mapping.json")
    val iri                  = url"http://example.com/id"
    val elasticSearchIri     = url"http://example.com/es"
    val sparqlIri            = url"http://example.com/sparql"
    val projectRef           = ProjectRef(genUUID)
    val id                   = Id(projectRef, iri)
    val sparqlview           = jsonContentOf("/view/sparqlview.json").appendContextOf(viewCtx)
    val sparqlview2          = jsonContentOf("/view/sparqlview-tag-schema.json").appendContextOf(viewCtx)
    val elasticSearchview    = jsonContentOf("/view/elasticview.json").appendContextOf(viewCtx)
    val aggElasticSearchView = jsonContentOf("/view/aggelasticview.json").appendContextOf(viewCtx)
    val aggSparqlView        = jsonContentOf("/view/aggsparql.json").appendContextOf(viewCtx)
    val tpe1                 = nxv.withSuffix("MyType").value
    val tpe2                 = nxv.withSuffix("MyType2").value
    val context              = Json.obj(
      "@base"  -> Json.fromString("http://example.com/base/"),
      "@vocab" -> Json.fromString("http://example.com/vocab/")
    )
    val sourceFilter         = Filter(Set(nxv.Resource, nxv.Schema), Set(tpe1, tpe2), Some("one"))
    val localS               = ProjectEventStream(url"http://example.com/source1", sourceFilter)
    val crossS               = CrossProjectEventStream(
      url"http://example.com/source2",
      Filter(),
      ProjectLabel("account1", "project1"),
      Set(Anonymous)
    )

    val source: Set[Source] = Set(localS, crossS)

    val esProjection = ElasticSearchProjection(
      "CONSTRUCT {{resource_id} ?p ?o} WHERE {?s ?p ?o}",
      ElasticSearchView(
        mapping,
        Filter(Set(nxv.Schema), Set(tpe1), Some("two"), includeDeprecated = false),
        includeMetadata = false,
        sourceAsText = true,
        projectRef,
        elasticSearchIri,
        UUID.fromString("247d223b-1d38-4c6e-8fed-f9a8c2ccb4a2"),
        1L,
        deprecated = false
      ),
      context
    )

    val sparqlProjection = SparqlProjection(
      "CONSTRUCT {{resource_id} ?p ?o} WHERE {?ss ?pp ?oo}",
      SparqlView(
        Filter(includeDeprecated = true),
        includeMetadata = true,
        projectRef,
        sparqlIri,
        UUID.fromString("247d223b-1d38-4c6e-8fed-f9a8c2ccb4a3"),
        1L,
        deprecated = false
      )
    )

    "constructing" should {

      "return a CompositeView" in {
        val resource = simpleV(id, compositeview(), types = Set(nxv.View, nxv.CompositeView, nxv.Beta))
        View(resource).rightValue shouldEqual
          CompositeView(
            source,
            Set[Projection](esProjection, sparqlProjection),
            Some(Interval(20.minutes)),
            projectRef,
            iri,
            UUID.fromString("247d223b-1d38-4c6e-8fed-f9a8c2ccb4a1"),
            resource.rev,
            resource.deprecated
          )
      }

      "return an ElasticSearchView" in {
        val resource = simpleV(id, elasticSearchview, types = Set(nxv.View, nxv.ElasticSearchView))
        View(resource).rightValue shouldEqual ElasticSearchView(
          mapping,
          Filter(Set(nxv.Schema, nxv.Resource), Set(tpe1, tpe2), Some("one")),
          false,
          true,
          projectRef,
          iri,
          UUID.fromString("3aa14a1a-81e7-4147-8306-136d8270bb01"),
          resource.rev,
          resource.deprecated
        )
      }

      "return an SparqlView" in {
        val resource = simpleV(id, sparqlview, types = Set(nxv.View, nxv.SparqlView))
        View(resource).rightValue shouldEqual SparqlView(
          Filter(),
          false,
          projectRef,
          iri,
          UUID.fromString("247d223b-1d38-4c6e-8fed-f9a8c2ccb4a1"),
          resource.rev,
          resource.deprecated
        )
      }

      "return an SparqlView with tag, schema and types" in {
        val resource = simpleV(id, sparqlview2, types = Set(nxv.View, nxv.SparqlView))
        View(resource).rightValue shouldEqual SparqlView(
          Filter(Set(nxv.Schema, nxv.Resource), Set(tpe1, tpe2), Some("one"), includeDeprecated = false),
          true,
          projectRef,
          iri,
          UUID.fromString("247d223b-1d38-4c6e-8fed-f9a8c2ccb4a1"),
          resource.rev,
          resource.deprecated
        )

      }

      "return an AggregateElasticSearchView from ProjectLabel ViewRef" in {
        val resource =
          simpleV(id, aggElasticSearchView, types = Set(nxv.View, nxv.AggregateElasticSearchView))
        val views    = Set(
          ViewRef(ProjectLabel("account1", "project1"), url"http://example.com/id2"),
          ViewRef(ProjectLabel("account1", "project2"), url"http://example.com/id3")
        )
        View(resource).rightValue shouldEqual AggregateElasticSearchView(
          views,
          projectRef,
          UUID.fromString("3aa14a1a-81e7-4147-8306-136d8270bb01"),
          iri,
          resource.rev,
          resource.deprecated
        )
      }

      "return an AggregateSparqlView from ProjectLabel ViewRef" in {
        val resource =
          simpleV(id, aggSparqlView, types = Set(nxv.View, nxv.AggregateSparqlView))
        val views    = Set(
          ViewRef(ProjectLabel("account1", "project1"), url"http://example.com/id2"),
          ViewRef(ProjectLabel("account1", "project2"), url"http://example.com/id3")
        )
        View(resource).rightValue shouldEqual AggregateSparqlView(
          views,
          projectRef,
          UUID.fromString("3aa14a1a-81e7-4147-8306-136d8270bb01"),
          iri,
          resource.rev,
          resource.deprecated
        )
      }

      "return an AggregateElasticSearchView from ProjectRef ViewRef" in {
        val aggElasticSearchViewRefs = jsonContentOf("/view/aggelasticviewrefs.json").appendContextOf(viewCtx)

        val resource =
          simpleV(id, aggElasticSearchViewRefs, types = Set(nxv.View, nxv.AggregateElasticSearchView))
        val views    = Set(
          ViewRef(
            ProjectRef(UUID.fromString("64b202b4-1060-42b5-9b4f-8d6a9d0d9113")),
            url"http://example.com/id2"
          ),
          ViewRef(
            ProjectRef(UUID.fromString("d23d9578-255b-4e46-9e65-5c254bc9ad0a")),
            url"http://example.com/id3"
          )
        )
        View(resource).rightValue shouldEqual AggregateElasticSearchView(
          views,
          projectRef,
          UUID.fromString("3aa14a1a-81e7-4147-8306-136d8270bb01"),
          iri,
          resource.rev,
          resource.deprecated
        )
      }

      "run incoming method on a SparqlView" in {
        val view  = SparqlView(Filter(includeDeprecated = false), true, projectRef, iri, UUID.randomUUID(), 1L, false)
        when(client.copy(namespace = view.index)).thenReturn(client)
        val query =
          contentOf(
            "/blazegraph/incoming.txt",
            Map(quote("{id}") -> "http://example.com/id", quote("{size}") -> "100", quote("{offset}") -> "0")
          )
        client.queryRaw(query, any[Throwable => Boolean]) shouldReturn IO(SparqlResults.empty)
        view.incoming[IO](url"http://example.com/id", FromPagination(0, 100)).ioValue shouldEqual
          UnscoredQueryResults(0, List.empty[UnscoredQueryResult[SparqlLink]])
      }

      "run outgoing method (including external links) on a SparqlView" in {
        val view  = SparqlView(Filter(includeDeprecated = false), true, projectRef, iri, UUID.randomUUID(), 1L, false)
        when(client.copy(namespace = view.index)).thenReturn(client)
        val query =
          contentOf(
            "/blazegraph/outgoing_include_external.txt",
            Map(
              quote("{id}")     -> "http://example.com/id2",
              quote("{graph}")  -> "http://example.com/id2/graph",
              quote("{size}")   -> "100",
              quote("{offset}") -> "10"
            )
          )
        client.queryRaw(query, any[Throwable => Boolean]) shouldReturn IO(SparqlResults.empty)
        view
          .outgoing[IO](url"http://example.com/id2", FromPagination(10, 100), includeExternalLinks = true)
          .ioValue shouldEqual
          UnscoredQueryResults(0, List.empty[UnscoredQueryResult[SparqlLink]])
      }

      "run outgoing method (excluding external links) on a SparqlView" in {
        val view  = SparqlView(Filter(includeDeprecated = false), true, projectRef, iri, UUID.randomUUID(), 1L, false)
        when(client.copy(namespace = view.index)).thenReturn(client)
        val query =
          contentOf(
            "/blazegraph/outgoing_scoped.txt",
            Map(
              quote("{id}")     -> "http://example.com/id2",
              quote("{graph}")  -> "http://example.com/id2/graph",
              quote("{size}")   -> "100",
              quote("{offset}") -> "10"
            )
          )
        client.queryRaw(query, any[Throwable => Boolean]) shouldReturn IO(SparqlResults.empty)
        view
          .outgoing[IO](url"http://example.com/id2", FromPagination(10, 100), includeExternalLinks = false)
          .ioValue shouldEqual
          UnscoredQueryResults(0, List.empty[UnscoredQueryResult[SparqlLink]])
      }

      "fail on AggregateElasticSearchView when types are wrong" in {
        val resource = simpleV(id, aggElasticSearchView, types = Set(nxv.View))
        View(resource).leftValue shouldBe a[InvalidResourceFormat]
      }

      "fail on AggregateSparqlView when types are wrong" in {
        val resource = simpleV(id, aggSparqlView, types = Set(nxv.View))
        View(resource).leftValue shouldBe a[InvalidResourceFormat]
      }

      "fail on AggregateElasticSearchView when ViewRef collection are wrong" in {
        val wrongAggElasticSearchView = jsonContentOf("/view/aggelasticviewwrong.json").appendContextOf(viewCtx)

        val resource =
          simpleV(id, wrongAggElasticSearchView, types = Set(nxv.View, nxv.AggregateElasticSearchView))
        View(resource).leftValue shouldBe a[InvalidResourceFormat]
      }

      "fail on ElasticSearchView when types are wrong" in {
        val resource = simpleV(id, elasticSearchview, types = Set(nxv.View))
        View(resource).leftValue shouldBe a[InvalidResourceFormat]
      }

      "fail on CompositeView when types are wrong" in {
        val resource = simpleV(id, compositeview(), types = Set(nxv.View))
        View(resource).leftValue shouldBe a[InvalidResourceFormat]
      }

      "fail on CompositeView when duplicated projection @id" in {
        val resource =
          simpleV(id, compositeview(genIri, nxv.defaultSparqlIndex), types = Set(nxv.View, nxv.CompositeView))
        View(resource).leftValue shouldBe a[InvalidResourceFormat]
      }

      "fail on ElasticSearchView when invalid payload" in {
        val wrong = List.tabulate(3) { i =>
          jsonContentOf(s"/view/elasticview-wrong-${i + 1}.json").appendContextOf(viewCtx)
        }
        forAll(wrong) { json =>
          val resource = simpleV(id, json, types = Set(nxv.View, nxv.ElasticSearchView))
          View(resource).leftValue shouldBe a[InvalidResourceFormat]
        }
      }

      "fail on CompositeView when invalid payload" in {
        val wrong = List
          .tabulate(2) { i =>
            jsonContentOf(s"/view/composite-view-wrong-${i + 1}.json").appendContextOf(viewCtx)
          }
          .toSet + compositeview().removeKeys("sources") + compositeview().removeKeys("projections")
        forAll(wrong) { json =>
          val resource = simpleV(id, json, types = Set(nxv.View, nxv.CompositeView, nxv.Beta))
          View(resource).leftValue shouldBe a[InvalidResourceFormat]
        }
      }

      "fail on SparqlView when types are wrong" in {
        val resource = simpleV(id, sparqlview, types = Set(nxv.Schema))
        View(resource).leftValue shouldBe a[InvalidResourceFormat]
      }

      "fail on SparqlView when invalid payload" in {
        val resource =
          simpleV(
            id,
            jsonContentOf("/view/sparqlview-wrong.json").appendContextOf(viewCtx),
            types = Set(nxv.View, nxv.ElasticSearchView)
          )
        View(resource).leftValue shouldBe a[InvalidResourceFormat]
      }
    }

    "converting into json (from Graph)" should {
      val views = Set(
        ViewRef(ProjectLabel("account1", "project1"), url"http://example.com/id2"),
        ViewRef(ProjectLabel("account1", "project2"), url"http://example.com/id3")
      )

      "return the json representation" in {
        // format: off
        val esAgg: View = AggregateElasticSearchView(views, projectRef, UUID.fromString("3aa14a1a-81e7-4147-8306-136d8270bb01"), iri, 1L, deprecated = false)
        val sparqlAgg: View = AggregateSparqlView(views, projectRef, UUID.fromString("3aa14a1a-81e7-4147-8306-136d8270bb01"), iri, 1L, deprecated = false)
        val composite1: View = CompositeView(Set(localS), Set(esProjection), None, projectRef, iri, UUID.fromString("247d223b-1d38-4c6e-8fed-f9a8c2ccb4a1"), 1L, deprecated = false)
        val composite2: View = CompositeView(Set(crossS), Set(sparqlProjection), Some(Interval(20.minutes)), projectRef, iri, UUID.fromString("247d223b-1d38-4c6e-8fed-f9a8c2ccb4a1"), 1L, deprecated = false)
        val es: View = ElasticSearchView(mapping, Filter(Set(nxv.Schema, nxv.Resource), Set.empty, Some("one")), false, true, projectRef, iri, UUID.fromString("3aa14a1a-81e7-4147-8306-136d8270bb01"), 1L, false)
        val sparql: View = SparqlView(Filter(), true, projectRef, iri, UUID.fromString("247d223b-1d38-4c6e-8fed-f9a8c2ccb4a1"), 1L, false)
        // format: on
        val replaceIdentityBase = Map(quote("{base}") -> iamClientConfig.basePublicIri.asString)
        val results             =
          List(
            esAgg      -> jsonContentOf("/view/aggelasticview-meta.json"),
            sparqlAgg  -> jsonContentOf("/view/aggsparqlview-meta.json"),
            composite1 -> jsonContentOf("/view/composite-view-meta-1.json"),
            composite2 -> jsonContentOf("/view/composite-view-meta-2.json", replaceIdentityBase),
            es         -> jsonContentOf("/view/elasticsearchview-meta.json"),
            sparql     -> jsonContentOf("/view/sparqlview-meta.json")
          )

        forAll(results) {
          case (view, expectedJson) =>
            val json = view.asGraph.toJson(viewCtx.appendContextOf(resourceCtx)).rightValue.removeNestedKeys("@context")
            json should equalIgnoreArrayOrder(expectedJson.removeNestedKeys("@context"))

        }
      }
    }
  }
}
