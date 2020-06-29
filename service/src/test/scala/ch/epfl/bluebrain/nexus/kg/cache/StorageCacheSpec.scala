package ch.epfl.bluebrain.nexus.kg.cache

import java.nio.file.Paths
import java.time.Clock

import akka.testkit._
import ch.epfl.bluebrain.nexus.commons.test.ActorSystemFixture
import ch.epfl.bluebrain.nexus.kg.TestHelper
import ch.epfl.bluebrain.nexus.kg.resources.ProjectIdentifier.ProjectRef
import ch.epfl.bluebrain.nexus.kg.storage.Storage.DiskStorage
import ch.epfl.bluebrain.nexus.rdf.implicits._
import ch.epfl.bluebrain.nexus.service.config.{ServiceConfig, Settings}
import monix.eval.Task
import monix.execution.Scheduler.Implicits.global
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.matchers.should.Matchers
import org.scalatest.{Inspectors, TryValues}

import scala.concurrent.duration._

//noinspection NameBooleanParameters
class StorageCacheSpec
    extends ActorSystemFixture("StorageCacheSpec", true)
    with Matchers
    with Inspectors
    with ScalaFutures
    with TryValues
    with TestHelper {

  implicit override def patienceConfig: PatienceConfig = PatienceConfig(3.seconds.dilated, 5.milliseconds)

  implicit private val clock: Clock             = Clock.systemUTC
  implicit private val appConfig: ServiceConfig = Settings(system).serviceConfig
  implicit private val keyValueStoreCfg         = appConfig.kg.keyValueStore.keyValueStoreConfig

  val ref1 = ProjectRef(genUUID)
  val ref2 = ProjectRef(genUUID)

  val time   = clock.instant()
  val lastId = url"http://example.com/lastA"
  // initialInstant.minusSeconds(1L + genInt().toLong)

  val tempStorage = DiskStorage(ref1, genIri, 1L, false, true, "alg", Paths.get("/tmp"), read, write, 1024L)

  val lastStorageProj1 = tempStorage.copy(id = lastId)
  val lastStorageProj2 = tempStorage.copy(ref = ref2, id = lastId)

  val storagesProj1: List[DiskStorage] = List.fill(5)(tempStorage.copy(id = genIri)) :+ lastStorageProj1
  val storagesProj2: List[DiskStorage] = List.fill(5)(tempStorage.copy(ref = ref2, id = genIri)) :+ lastStorageProj2

  private val cache = StorageCache[Task]

  "StorageCache" should {

    "index storages" in {
      forAll((storagesProj1 ++ storagesProj2).zipWithIndex) {
        case (storage, index) =>
          implicit val instant = time.plusSeconds(index.toLong)
          cache.put(storage).runToFuture.futureValue
          cache.get(storage.ref, storage.id).runToFuture.futureValue shouldEqual Some(storage)
      }
    }

    "get latest default storage" in {
      cache.getDefault(ref1).runToFuture.futureValue shouldEqual Some(lastStorageProj1)
      cache.getDefault(ref2).runToFuture.futureValue shouldEqual Some(lastStorageProj2)
      cache.getDefault(ProjectRef(genUUID)).runToFuture.futureValue shouldEqual None
    }

    "list storages" in {
      cache.get(ref1).runToFuture.futureValue should contain theSameElementsAs storagesProj1
      cache.get(ref2).runToFuture.futureValue should contain theSameElementsAs storagesProj2
    }

    "deprecate storage" in {
      val storage          = storagesProj1.head
      implicit val instant = time.plusSeconds(30L)
      cache.put(storage.copy(deprecated = true, rev = 2L)).runToFuture.futureValue
      cache.get(storage.ref, storage.id).runToFuture.futureValue shouldEqual None
      cache.get(ref1).runToFuture.futureValue should contain theSameElementsAs storagesProj1.filterNot(_ == storage)
    }
  }
}
