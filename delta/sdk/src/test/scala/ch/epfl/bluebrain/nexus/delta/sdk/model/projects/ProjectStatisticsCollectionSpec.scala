package ch.epfl.bluebrain.nexus.delta.sdk.model.projects

import akka.persistence.query.Sequence
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.ProjectStatisticsCollection.ProjectStatistics
import ch.epfl.bluebrain.nexus.testkit.EitherValuable
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike
import io.circe.syntax._

class ProjectStatisticsCollectionSpec extends AnyWordSpecLike with Matchers with EitherValuable {
  "ProjectsStatistics values" should {
    val ref1       = ProjectRef.unsafe("org1", "proj1")
    val ref2       = ProjectRef.unsafe("org2", "proj2")
    val initialMap = Map(ref1 -> ProjectStatistics(3L, Sequence(3L)))
    val initial    = ProjectStatisticsCollection(initialMap)

    "be incremented" in {
      initial.incrementCount(ref2, Sequence(3L)) shouldEqual
        ProjectStatisticsCollection(initialMap + (ref2 -> ProjectStatistics(1L, Sequence(3L))))
      initial.incrementCount(ref1, Sequence(4L)) shouldEqual
        ProjectStatisticsCollection(Map(ref1 -> ProjectStatistics(4L, Sequence(4L))))
      initial.incrementCount(ref1, Sequence(2L)) shouldEqual
        ProjectStatisticsCollection(Map(ref1 -> ProjectStatistics(4L, Sequence(3L))))
    }

    "be converted to json and back" in {
      initial.asJson.as[ProjectStatisticsCollection].rightValue shouldEqual initial
    }
  }

}
