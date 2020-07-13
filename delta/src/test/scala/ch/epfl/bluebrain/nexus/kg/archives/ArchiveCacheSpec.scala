package ch.epfl.bluebrain.nexus.kg.archives

import java.time.{Clock, Instant, ZoneId}

import cats.effect.{IO, Timer}
import ch.epfl.bluebrain.nexus.admin.projects.Project
import ch.epfl.bluebrain.nexus.admin.types.ResourceF
import ch.epfl.bluebrain.nexus.iam.types.Identity.{Anonymous, Subject}
import ch.epfl.bluebrain.nexus.kg.TestHelper
import ch.epfl.bluebrain.nexus.kg.archives.Archive.{File, Resource, ResourceDescription}
import ch.epfl.bluebrain.nexus.kg.resources.Id
import ch.epfl.bluebrain.nexus.kg.resources.ProjectIdentifier.ProjectRef
import ch.epfl.bluebrain.nexus.delta.config.Settings
import ch.epfl.bluebrain.nexus.util.{ActorSystemFixture, IOOptionValues}
import org.scalatest.concurrent.Eventually
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

import scala.concurrent.duration._

class ArchiveCacheSpec
    extends ActorSystemFixture("ArchiveCacheSpec", true)
    with TestHelper
    with AnyWordSpecLike
    with Matchers
    with IOOptionValues
    with Eventually {

  implicit override def patienceConfig: PatienceConfig = PatienceConfig(10.second, 50.milliseconds)

  private val appConfig                 = Settings(system).appConfig
  implicit private val config           =
    appConfig.copy(archives = appConfig.archives.copy(cacheInvalidateAfter = 500.millis, maxResources = 100))
  implicit private val timer: Timer[IO] = IO.timer(system.dispatcher)
  implicit private val archivesCfg      = config.archives

  private val cache: ArchiveCache[IO] = ArchiveCache[IO].unsafeToFuture().futureValue
  implicit private val clock          = Clock.fixed(Instant.EPOCH, ZoneId.systemDefault())
  private val instant                 = clock.instant()
  private val subject: Subject        = Anonymous
  private val epoch                   = Instant.EPOCH

  def randomProject() = {
    val project = Project(genString(), genUUID, genString(), None, Map.empty, genIri, genIri)
    ResourceF(genIri, genUUID, 1L, false, Set.empty, epoch, subject, epoch, subject, project)
  }

  "An archive cache" should {

    "write and read an Archive" in {
      val resId     = Id(ProjectRef(randomProject().uuid), genIri)
      val resource1 = Resource(genIri, randomProject(), None, None, originalSource = true, None)
      val file1     = File(genIri, randomProject(), None, None, None)
      val archive   = Archive(resId, instant, Anonymous, Set(resource1, file1))
      val _         = cache.put(archive).value.some
      cache.get(archive.resId).value.some shouldEqual archive
    }

    "read a non existing resource" in {
      val resId = Id(ProjectRef(randomProject().uuid), genIri)
      cache.get(resId).value.ioValue shouldEqual None
    }

    "read after timeout" in {
      val resId   = Id(ProjectRef(randomProject().uuid), genIri)
      val set     = Set[ResourceDescription](Resource(genIri, randomProject(), None, None, originalSource = true, None))
      val archive = Archive(resId, instant, Anonymous, set)
      val _       = cache.put(archive).value.some
      val time    = System.currentTimeMillis()
      cache.get(resId).value.some shouldEqual archive
      eventually {
        cache.get(resId).value.ioValue shouldEqual None
      }
      val diff    = System.currentTimeMillis() - time
      diff should be > config.archives.cacheInvalidateAfter.toMillis
      diff should be < config.archives.cacheInvalidateAfter.toMillis + 300
    }
  }
}
