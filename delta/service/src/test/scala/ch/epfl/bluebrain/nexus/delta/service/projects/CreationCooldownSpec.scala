package ch.epfl.bluebrain.nexus.delta.service.projects

import akka.persistence.query.NoOffset
import cats.effect.Clock
import ch.epfl.bluebrain.nexus.delta.sdk.cache.KeyValueStore
import ch.epfl.bluebrain.nexus.delta.sdk.model.ResourcesDeletionProgress.ResourcesDeleted
import ch.epfl.bluebrain.nexus.delta.sdk.model.ResourcesDeletionStatus
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.Anonymous
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.ProjectRejection.ProjectCreationCooldown
import ch.epfl.bluebrain.nexus.delta.sourcing.EventLogConfig
import ch.epfl.bluebrain.nexus.delta.sourcing.config.DatabaseFlavour
import ch.epfl.bluebrain.nexus.delta.sourcing.model.ProjectRef
import ch.epfl.bluebrain.nexus.testkit.IOValues
import monix.bio.{IO, UIO}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

import java.time.Instant
import java.util.UUID
import scala.concurrent.duration._

class CreationCooldownSpec extends AnyWordSpecLike with IOValues with Matchers {

  private val now = Instant.now()

  implicit val clock: Clock[UIO] =
    new Clock[UIO] {
      override def realTime(unit: TimeUnit): UIO[Long]  = IO.pure(now.toEpochMilli)
      override def monotonic(unit: TimeUnit): UIO[Long] = IO.pure(now.toEpochMilli)
    }

  private val eventLogConfigWithoutTtl = EventLogConfig.postgresql
  private val eventLogConfigWithTtl    = EventLogConfig(DatabaseFlavour.Cassandra, NoOffset, Some(5.minutes))

  private def resourceDeletionStatus(project: ProjectRef, updatedAt: Instant) =
    ResourcesDeletionStatus(ResourcesDeleted, project, Anonymous, now, Anonymous, now, updatedAt, UUID.randomUUID())

  private val ref1 = ProjectRef.unsafe("org", "proj1")
  private val ref2 = ProjectRef.unsafe("org", "proj2")

  private val cache = {
    for {
      kvs <- KeyValueStore.localLRU[UUID, ResourcesDeletionStatus](3)
      _   <- kvs.put(UUID.randomUUID(), resourceDeletionStatus(ref1, now.minusSeconds(15 * 60L)))
      _   <- kvs.put(UUID.randomUUID(), resourceDeletionStatus(ref1, now.minusSeconds(10 * 60L)))
      _   <- kvs.put(UUID.randomUUID(), resourceDeletionStatus(ref2, now.minusSeconds(15 * 60L)))
    } yield kvs
  }.accepted

  "Checking cooldown" should {

    "not detect any problem if no ttl is defined" in {
      CreationCooldown.validate(cache, eventLogConfigWithoutTtl)(ref1).accepted
      CreationCooldown.validate(cache, eventLogConfigWithoutTtl)(ref2).accepted
    }

    "detect any problem if the ttl has not been observed" in {
      CreationCooldown.validate(cache, eventLogConfigWithTtl)(ref1).rejectedWith[ProjectCreationCooldown]
    }

    "not detect any problem if the ttl has been observed" in {
      CreationCooldown.validate(cache, eventLogConfigWithTtl)(ref2).accepted
    }

  }

}
