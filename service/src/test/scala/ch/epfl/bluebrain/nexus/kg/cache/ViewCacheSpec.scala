package ch.epfl.bluebrain.nexus.kg.cache

import akka.actor.ExtendedActorSystem
import akka.serialization.Serialization
import akka.testkit._
import ch.epfl.bluebrain.nexus.kg.TestHelper
import ch.epfl.bluebrain.nexus.kg.indexing.View
import ch.epfl.bluebrain.nexus.kg.indexing.View.CompositeView.Projection.{ElasticSearchProjection, SparqlProjection}
import ch.epfl.bluebrain.nexus.kg.indexing.View.CompositeView.Source.ProjectEventStream
import ch.epfl.bluebrain.nexus.kg.indexing.View._
import ch.epfl.bluebrain.nexus.kg.resources.ProjectIdentifier.{ProjectLabel, ProjectRef}
import ch.epfl.bluebrain.nexus.service.config.Vocabulary.nxv
import ch.epfl.bluebrain.nexus.service.config.{AppConfig, Settings}
import ch.epfl.bluebrain.nexus.util.ActorSystemFixture
import io.circe.Json
import monix.eval.Task
import monix.execution.Scheduler.Implicits.global
import org.scalatest.concurrent.{Eventually, ScalaFutures}
import org.scalatest.matchers.should.Matchers
import org.scalatest.{Inspectors, TryValues}

import scala.concurrent.duration._

//noinspection NameBooleanParameters
class ViewCacheSpec
    extends ActorSystemFixture("ViewCacheSpec", true)
    with Matchers
    with Inspectors
    with ScalaFutures
    with TryValues
    with TestHelper
    with Eventually {

  implicit override def patienceConfig: PatienceConfig = PatienceConfig(3.seconds.dilated, 5.milliseconds)

  private def genJson: Json = Json.obj("key" -> Json.fromString(genString()))

  implicit private val appConfig: AppConfig = Settings(system).appConfig
  implicit private val keyValueStoreCfg     = appConfig.keyValueStore.keyValueStoreConfig
  val ref1                                  = ProjectRef(genUUID)
  val ref2                                  = ProjectRef(genUUID)

  val esView        = ElasticSearchView(Json.obj(), Filter(), false, true, ref1, genIri, genUUID, 1L, false)
  val aggRefsView   = AggregateElasticSearchView(
    Set(ViewRef(ProjectRef(genUUID), genIri)),
    ProjectRef(genUUID),
    genUUID,
    genIri,
    1L,
    false
  )
  val aggLabelsView = AggregateElasticSearchView(
    Set(ViewRef(ProjectLabel(genString(), genString()), genIri)),
    ProjectRef(genUUID),
    genUUID,
    genIri,
    1L,
    false
  )

  val sparqlView = SparqlView(Filter(), true, ref1, nxv.defaultSparqlIndex.value, genUUID, 1L, false)

  val esViewsProj1: Set[ElasticSearchView] =
    List.fill(5)(esView.copy(mapping = genJson, id = genIri + "elasticSearch1", uuid = genUUID)).toSet
  val sparqlViewsProj1: Set[SparqlView]    = List.fill(5)(sparqlView.copy(id = genIri + "sparql1", uuid = genUUID)).toSet
  val esViewsProj2: Set[ElasticSearchView] =
    List.fill(5)(esView.copy(mapping = genJson, id = genIri + "elasticSearch2", uuid = genUUID, ref = ref2)).toSet
  val sparqlViewsProj2: Set[SparqlView]    =
    List.fill(5)(sparqlView.copy(id = genIri + "sparql2", uuid = genUUID, ref = ref2)).toSet
  val compositeView                        = CompositeView(
    Set(ProjectEventStream(genIri, Filter())),
    Set(ElasticSearchProjection("", esViewsProj1.head, Json.obj()), SparqlProjection("", sparqlViewsProj1.head)),
    None,
    ref1,
    genIri,
    genUUID,
    1L,
    false
  )

  private val cache = ViewCache[Task]

  "ViewCache" should {

    "index views" in {
      val list = (esViewsProj1 ++ sparqlViewsProj1 ++ esViewsProj2 ++ sparqlViewsProj2 ++ Set(compositeView)).toList
      forAll(list) { view =>
        cache.put(view).runToFuture.futureValue
        cache.getBy[View](view.ref, view.id).runToFuture.futureValue shouldEqual Some(view)
      }
    }

    "get views" in {
      forAll(esViewsProj1) { view =>
        cache.put(view).runToFuture.futureValue
        cache.getBy[View](view.ref, view.id).runToFuture.futureValue shouldEqual Some(view)
        cache.getBy[ElasticSearchView](view.ref, view.id).runToFuture.futureValue shouldEqual Some(view)
        cache.getBy[SparqlView](view.ref, view.id).runToFuture.futureValue shouldEqual None
      }
    }

    "get projections" in {
      val esView      = esViewsProj1.head
      val sparqlView  = sparqlViewsProj1.head
      val defaultView = compositeView.defaultSparqlView
      cache.getProjectionBy[ElasticSearchView](ref1, compositeView.id, esView.id).runToFuture.futureValue shouldEqual
        Some(esView)
      cache.getProjectionBy[View](ref1, compositeView.id, esView.id).runToFuture.futureValue shouldEqual
        Some(esView)
      cache.getProjectionBy[SparqlView](ref1, compositeView.id, esView.id).runToFuture.futureValue shouldEqual
        None
      cache.getProjectionBy[SparqlView](ref1, compositeView.id, sparqlView.id).runToFuture.futureValue shouldEqual
        Some(sparqlView)
      cache.getProjectionBy[SparqlView](ref1, compositeView.id, defaultView.id).runToFuture.futureValue shouldEqual
        None
    }

    "list views" in {
      cache.get(ref1).runToFuture.futureValue shouldEqual (esViewsProj1 ++ sparqlViewsProj1 ++ Set(compositeView))
      cache.get(ref2).runToFuture.futureValue shouldEqual (esViewsProj2 ++ sparqlViewsProj2)
    }

    "list filtering by type" in {
      cache.getBy[ElasticSearchView](ref1).runToFuture.futureValue shouldEqual esViewsProj1
      cache.getBy[SparqlView](ref2).runToFuture.futureValue shouldEqual sparqlViewsProj2
    }

    "deprecate view" in {
      val view        = esViewsProj1.head
      cache.put(view.copy(deprecated = true, rev = 2L)).runToFuture.futureValue
      cache.getBy[View](view.ref, view.id).runToFuture.futureValue shouldEqual None
      val expectedSet = esViewsProj1.filterNot(_ == view) ++ sparqlViewsProj1 ++ Set(compositeView)
      cache.get(ref1).runToFuture.futureValue shouldEqual expectedSet
    }

    "serialize an AggregateElasticSearchView" when {
      val serialization = new Serialization(system.asInstanceOf[ExtendedActorSystem])
      "parameterized with ProjectRef" in {
        val bytes = serialization.serialize(aggRefsView).success.value
        val out   = serialization.deserialize(bytes, classOf[AggregateElasticSearchView]).success.value
        out shouldEqual aggRefsView
      }

      "parameterized with ProjectLabel" in {
        val bytes = serialization.serialize(aggLabelsView).success.value
        val out   = serialization.deserialize(bytes, classOf[AggregateElasticSearchView]).success.value
        out shouldEqual aggLabelsView
      }
    }
  }
}
