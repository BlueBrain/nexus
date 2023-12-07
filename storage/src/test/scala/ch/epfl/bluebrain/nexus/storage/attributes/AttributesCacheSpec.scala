package ch.epfl.bluebrain.nexus.storage.attributes

import akka.actor.ActorSystem
import akka.http.scaladsl.model.MediaTypes.{`application/octet-stream`, `image/jpeg`}
import akka.testkit.TestKit
import akka.util.Timeout
import cats.effect.{IO, Ref}
import ch.epfl.bluebrain.nexus.storage.File.{Digest, FileAttributes}
import ch.epfl.bluebrain.nexus.storage._
import ch.epfl.bluebrain.nexus.storage.config.AppConfig.DigestConfig
import ch.epfl.bluebrain.nexus.storage.utils.Randomness
import ch.epfl.bluebrain.nexus.testkit.scalatest.ce.CatsEffectSpec
import org.scalatest.concurrent.Eventually
import org.scalatest.{BeforeAndAfter, Inspectors}

import java.nio.file.{Path, Paths}
import java.time.{Clock, Instant, ZoneId}
import scala.concurrent.ExecutionContext
import scala.concurrent.duration._

class AttributesCacheSpec
    extends TestKit(ActorSystem("AttributesCacheSpec"))
    with CatsEffectSpec
    with BeforeAndAfter
    with Inspectors
    with Randomness
    with Eventually {

  implicit override def patienceConfig: PatienceConfig = PatienceConfig(20.second, 100.milliseconds)

  implicit val config: DigestConfig                       =
    DigestConfig("SHA-256", maxInMemory = 10, concurrentComputations = 3, 20, 5.seconds)

  implicit val timeout: Timeout                           = Timeout(1.minute)
  implicit val executionContext: ExecutionContext         = ExecutionContext.global

  trait Ctx {
    val path: Path                      = Paths.get(randomString())
    val digest                          = Digest(config.algorithm, randomString())
    val attributes                      = FileAttributes(s"file://$path", genInt().toLong, digest, `image/jpeg`)
    def attributesEmpty(p: Path = path) = FileAttributes(p.toAkkaUri, 0L, Digest.empty, `application/octet-stream`)
    val computedPaths                         = Ref.unsafe[IO, Set[Path]](Set.empty[Path])

    def computedPathsSize = computedPaths.get.map(_.size)

    implicit val clock: Clock = new Clock {
      override def getZone: ZoneId                 = ZoneId.systemDefault()
      override def withZone(zoneId: ZoneId): Clock = Clock.systemUTC()
      // For every attribute computation done, it passes one second
      override def instant(): Instant              = Instant.EPOCH
    }

    val defaultComputation: AttributesComputation[String] = (path: Path, _: String) =>
      computedPaths.update { seen =>
        if(seen.contains(path))
          fail(s"'$path' should have been computed no to be seen again.")
        else
          seen + path
      }.as(attributes)

    def computedAttributes(path: Path, algorithm: String) = {
      val digest = Digest(algorithm, "COMPUTED")
      FileAttributes(path.toAkkaUri, 42L, digest, `image/jpeg`)
    }

  }

  "An AttributesCache" should {

    "trigger a computation and fetch file after" in new Ctx {
      implicit val computation: AttributesComputation[String] = defaultComputation
      val attributesCache = AttributesCache[String]

      attributesCache.asyncComputePut(path, config.algorithm)
      eventually { computedPathsSize.accepted shouldEqual 1 }
      attributesCache.get(path).accepted shouldEqual attributes
    }

    "get file that triggers attributes computation" in new Ctx {
      implicit val computation: AttributesComputation[String] = defaultComputation
      val attributesCache = AttributesCache[String]
      attributesCache.get(path).accepted shouldEqual attributesEmpty()
      eventually(computedPathsSize.accepted shouldEqual 1)
      attributesCache.get(path).accepted shouldEqual attributes
    }

    "verify 2 concurrent computations" in new Ctx {
      val list = List.tabulate(10) { i =>Paths.get(i.toString) }
      val time = System.currentTimeMillis()

      implicit  val delayedComputation: AttributesComputation[String] = (path: Path, algorithm: String) =>
        IO.sleep(1000.millis) >> defaultComputation(path, algorithm) >> IO.pure(computedAttributes(path, algorithm))
      val attributesCache = AttributesCache[String]

      forAll(list) { path =>
        attributesCache.get(path).accepted shouldEqual attributesEmpty(path)
      }

      eventually(computedPathsSize.accepted shouldEqual 10)

      val diff = System.currentTimeMillis() - time
      diff should be > 4000L
      diff should be < 6500L

      forAll(list) { path =>
        attributesCache.get(path).accepted shouldEqual computedAttributes(path, config.algorithm)
      }
    }

    "verify remove oldest" in new Ctx {
      val list = List.tabulate(20) { i => Paths.get(i.toString) }

      implicit val computation: AttributesComputation[String] = (path: Path, algorithm: String) =>
        defaultComputation(path, algorithm) >> IO.pure(computedAttributes(path, algorithm))
      val attributesCache = AttributesCache[String]

      forAll(list) { path =>
        attributesCache.get(path).accepted shouldEqual attributesEmpty(path)
      }

      eventually(computedPathsSize.accepted shouldEqual 20)

      forAll(list.takeRight(10)) { path =>
        attributesCache.get(path).accepted shouldEqual computedAttributes(path, config.algorithm)
      }

      forAll(list.take(10)) { path =>
        attributesCache.get(path).accepted shouldEqual attributesEmpty(path)
      }
    }

    "verify failure is skipped" in new Ctx {
      val list = List.tabulate(5) { i => Paths.get(i.toString) }

      implicit val computation: AttributesComputation[String] = (path: Path, algorithm: String) => {
        if (path.endsWith("0"))
          IO.raiseError(new RuntimeException)
        else
          defaultComputation(path, algorithm) >> IO.pure(computedAttributes(path, algorithm))
      }
      val attributesCache = AttributesCache[String]

      forAll(list) { path => attributesCache.get(path).accepted shouldEqual attributesEmpty(path) }

      forAll(list.drop(1)) { path =>
        eventually(
          attributesCache.get(path).accepted shouldEqual computedAttributes(path, config.algorithm)
        )
      }
    }
  }
}
