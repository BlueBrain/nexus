package ch.epfl.bluebrain.nexus.storage.attributes

import akka.actor.ActorSystem
import akka.http.scaladsl.model.MediaTypes.{`application/octet-stream`, `image/jpeg`}
import akka.testkit.TestKit
import akka.util.Timeout
import cats.effect.IO
import cats.effect.unsafe.implicits._
import ch.epfl.bluebrain.nexus.storage.File.{Digest, FileAttributes}
import ch.epfl.bluebrain.nexus.storage._
import ch.epfl.bluebrain.nexus.storage.config.AppConfig.DigestConfig
import ch.epfl.bluebrain.nexus.storage.utils.Randomness
import ch.epfl.bluebrain.nexus.testkit.scalatest.ce.CatsEffectSpec
import org.mockito.{IdiomaticMockito, Mockito}
import org.scalatest.concurrent.{Eventually, ScalaFutures}
import org.scalatest.{BeforeAndAfter, Ignore, Inspectors}

import java.nio.file.{Path, Paths}
import java.time.{Clock, Instant, ZoneId}
import java.util.concurrent.atomic.AtomicInteger
import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}

@Ignore
class AttributesCacheSpec
    extends TestKit(ActorSystem("AttributesCacheSpec"))
    with CatsEffectSpec
    with IdiomaticMockito
    with BeforeAndAfter
    with Inspectors
    with Randomness
    with Eventually
    with ScalaFutures {

  implicit override def patienceConfig: PatienceConfig = PatienceConfig(20.second, 100.milliseconds)

  implicit val config: DigestConfig                       =
    DigestConfig("SHA-256", maxInMemory = 10, concurrentComputations = 3, 20, 5.seconds)
  implicit val computation: AttributesComputation[String] = mock[AttributesComputation[String]]
  implicit val timeout: Timeout                           = Timeout(1.minute)
  implicit val executionContext: ExecutionContext         = ExecutionContext.global

  before {
    Mockito.reset(computation)
  }

  trait Ctx {
    val path: Path                      = Paths.get(randomString())
    val digest                          = Digest(config.algorithm, randomString())
    val attributes                      = FileAttributes(s"file://$path", genInt().toLong, digest, `image/jpeg`)
    def attributesEmpty(p: Path = path) = FileAttributes(p.toAkkaUri, 0L, Digest.empty, `application/octet-stream`)
    val counter                         = new AtomicInteger(0)

    implicit val clock: Clock = new Clock {
      override def getZone: ZoneId                 = ZoneId.systemDefault()
      override def withZone(zoneId: ZoneId): Clock = Clock.systemUTC()
      // For every attribute computation done, it passes one second
      override def instant(): Instant              = Instant.ofEpochSecond(counter.get + 1L)
    }
    val attributesCache       = AttributesCache[String]
    computation(path, config.algorithm) shouldReturn
      IO { counter.incrementAndGet(); attributes }
  }

  "An AttributesCache" should {

    "trigger a computation and fetch file after" in new Ctx {
      attributesCache.asyncComputePut(path, config.algorithm)
      eventually(counter.get shouldEqual 1)
      computation(path, config.algorithm) wasCalled once
      attributesCache.get(path).unsafeToFuture().futureValue shouldEqual attributes
      computation(path, config.algorithm) wasCalled once
    }

    "get file that triggers attributes computation" in new Ctx {
      attributesCache.get(path).unsafeToFuture().futureValue shouldEqual attributesEmpty()
      eventually(counter.get shouldEqual 1)
      computation(path, config.algorithm) wasCalled once
      attributesCache.get(path).unsafeToFuture().futureValue shouldEqual attributes
      computation(path, config.algorithm) wasCalled once
    }

    //FIXME Flaky test
    "verify 2 concurrent computations" ignore new Ctx {
      val list = List.tabulate(10) { i =>
        val path   = Paths.get(i.toString)
        val digest = Digest(config.algorithm, i.toString)
        path -> FileAttributes(path.toAkkaUri, i.toLong, digest, `image/jpeg`)
      }
      val time = System.currentTimeMillis()

      forAll(list) { case (path, attr) =>
        computation(path, config.algorithm) shouldReturn
          IO.fromFuture(IO.pure(Future {
            Thread.sleep(1000)
            counter.incrementAndGet()
            attr
          }))
        attributesCache.get(path).unsafeToFuture().futureValue shouldEqual attributesEmpty(path)
      }

      eventually(counter.get() shouldEqual 10)

      forAll(list) { case (path, _) =>
        eventually(computation(path, config.algorithm) wasCalled once)
      }

      val diff = System.currentTimeMillis() - time
      diff should be > 4000L
      diff should be < 6500L

      forAll(list) { case (path, attr) =>
        attributesCache.get(path).unsafeToFuture().futureValue shouldEqual attr
      }
    }

    //FIXME Flaky test
    "verify remove oldest" ignore new Ctx {
      val list = List.tabulate(20) { i =>
        val path   = Paths.get(i.toString)
        val digest = Digest(config.algorithm, i.toString)
        path -> FileAttributes(path.toAkkaUri, i.toLong, digest, `image/jpeg`)
      }

      forAll(list) { case (path, attr) =>
        computation(path, config.algorithm) shouldReturn
          IO { counter.incrementAndGet(); attr }
        attributesCache.get(path).unsafeToFuture().futureValue shouldEqual attributesEmpty(path)
      }

      eventually(counter.get() shouldEqual 20)

      forAll(list.takeRight(10)) { case (path, attr) =>
        attributesCache.get(path).unsafeToFuture().futureValue shouldEqual attr
      }

      forAll(list.take(10)) { case (path, _) =>
        attributesCache.get(path).unsafeToFuture().futureValue shouldEqual attributesEmpty(path)
      }
    }

    "verify failure is skipped" in new Ctx {
      val list = List.tabulate(5) { i =>
        val path   = Paths.get(i.toString)
        val digest = Digest(config.algorithm, i.toString)
        path -> FileAttributes(path.toAkkaUri, i.toLong, digest, `image/jpeg`)
      }

      forAll(list) { case (path, attr) =>
        if (attr.bytes == 0L)
          computation(path, config.algorithm) shouldReturn IO.raiseError(new RuntimeException)
        else
          computation(path, config.algorithm) shouldReturn IO(attr)

        attributesCache.get(path).unsafeToFuture().futureValue shouldEqual attributesEmpty(path)
      }

      forAll(list.drop(1)) { case (path, attr) =>
        eventually(attributesCache.get(path).unsafeToFuture().futureValue shouldEqual attr)
      }
    }
  }
}
