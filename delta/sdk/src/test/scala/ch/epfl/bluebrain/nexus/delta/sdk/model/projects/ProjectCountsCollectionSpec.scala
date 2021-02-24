package ch.epfl.bluebrain.nexus.delta.sdk.model.projects

import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.ProjectCountsCollection.ProjectCount
import ch.epfl.bluebrain.nexus.testkit.EitherValuable
import io.circe.syntax._
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

import java.time.Instant

class ProjectCountsCollectionSpec extends AnyWordSpecLike with Matchers with EitherValuable {
  private val now       = Instant.now()
  private val nowPlus3  = now.plusSeconds(3)
  private val nowMinus3 = now.minusSeconds(3)
  "ProjectCounts values" should {
    val ref1       = ProjectRef.unsafe("org1", "proj1")
    val ref2       = ProjectRef.unsafe("org2", "proj2")
    val initialMap = Map(ref1 -> ProjectCount(3L, now))
    val initial    = ProjectCountsCollection(initialMap)

    "be incremented" in {
      initial.increment(ref2, nowMinus3) shouldEqual
        ProjectCountsCollection(initialMap + (ref2 -> ProjectCount(1L, nowMinus3)))
      initial.increment(ref1, nowPlus3) shouldEqual
        ProjectCountsCollection(Map(ref1 -> ProjectCount(4L, nowPlus3)))
      initial.increment(ref1, nowMinus3) shouldEqual
        ProjectCountsCollection(Map(ref1 -> ProjectCount(4L, now)))
    }

    "be converted to json and back" in {
      initial.asJson.as[ProjectCountsCollection].rightValue shouldEqual initial
    }
  }

}
