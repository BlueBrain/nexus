package ch.epfl.bluebrain.nexus.kg.cache

import akka.actor.ExtendedActorSystem
import akka.serialization.Serialization
import akka.testkit._
import ch.epfl.bluebrain.nexus.iam.types.Identity.Anonymous
import ch.epfl.bluebrain.nexus.kg.TestHelper
import ch.epfl.bluebrain.nexus.kg.resolve.Resolver._
import ch.epfl.bluebrain.nexus.kg.resources.ProjectIdentifier.{ProjectLabel, ProjectRef}
import ch.epfl.bluebrain.nexus.delta.config.{AppConfig, Settings}
import ch.epfl.bluebrain.nexus.util.ActorSystemFixture
import monix.eval.Task
import monix.execution.Scheduler.Implicits.global
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.matchers.should.Matchers
import org.scalatest.{Inspectors, TryValues}

import scala.concurrent.duration._

//noinspection NameBooleanParameters
class ResolverCacheSpec
    extends ActorSystemFixture("ResolverCacheSpec", true)
    with Matchers
    with Inspectors
    with ScalaFutures
    with TryValues
    with TestHelper {

  implicit override def patienceConfig: PatienceConfig = PatienceConfig(3.seconds.dilated, 5.milliseconds)

  implicit private val appConfig: AppConfig = Settings(system).appConfig
  implicit private val keyValueStoreCfg     = appConfig.keyValueStore.keyValueStoreConfig

  val ref1 = ProjectRef(genUUID)
  val ref2 = ProjectRef(genUUID)

  val label1 = ProjectLabel(genString(), genString())
  val label2 = ProjectLabel(genString(), genString())

  val resolver: InProjectResolver       = InProjectResolver(ref1, genIri, 1L, false, 10)
  val crossRefs: CrossProjectResolver   =
    CrossProjectResolver(Set(genIri), List(ref1, ref2), Set(Anonymous), ref1, genIri, 0L, false, 1)
  val crossLabels: CrossProjectResolver =
    CrossProjectResolver(Set(genIri), List(label1, label2), Set(Anonymous), ref1, genIri, 0L, false, 1)

  val resolverProj1: Set[InProjectResolver] = List.fill(5)(resolver.copy(id = genIri)).toSet
  val resolverProj2: Set[InProjectResolver] = List.fill(5)(resolver.copy(id = genIri, ref = ref2)).toSet

  private val cache = ResolverCache[Task]

  "ResolverCache" should {

    "index resolvers" in {
      val list = (resolverProj1 ++ resolverProj2).toList
      forAll(list) { resolver =>
        cache.put(resolver).runToFuture.futureValue
        cache.get(resolver.ref, resolver.id).runToFuture.futureValue shouldEqual Some(resolver)
      }
    }

    "list resolvers" in {
      cache.get(ref1).runToFuture.futureValue should contain theSameElementsAs resolverProj1
      cache.get(ref2).runToFuture.futureValue should contain theSameElementsAs resolverProj2
    }

    "deprecate resolver" in {
      val resolver = resolverProj1.head
      cache.put(resolver.copy(deprecated = true, rev = 2L)).runToFuture.futureValue
      cache.get(resolver.ref, resolver.id).runToFuture.futureValue shouldEqual None
      cache.get(ref1).runToFuture.futureValue should contain theSameElementsAs resolverProj1.filterNot(_ == resolver)
    }

    "serialize cross project resolver" when {
      val serialization = new Serialization(system.asInstanceOf[ExtendedActorSystem])
      "parameterized with ProjectRef" in {
        val bytes = serialization.serialize(crossRefs).success.value
        val out   = serialization.deserialize(bytes, classOf[CrossProjectResolver]).success.value
        out shouldEqual crossRefs
      }
      "parameterized with ProjectLabel" in {
        val bytes = serialization.serialize(crossLabels).success.value
        val out   = serialization.deserialize(bytes, classOf[CrossProjectResolver]).success.value
        out shouldEqual crossLabels
      }
    }
  }
}
