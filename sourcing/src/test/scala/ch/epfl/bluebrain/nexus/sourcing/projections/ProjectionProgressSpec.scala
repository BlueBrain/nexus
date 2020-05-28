package ch.epfl.bluebrain.nexus.sourcing.projections

import java.util.UUID

import akka.persistence.query.{Offset, Sequence, TimeBasedUUID}
import ch.epfl.bluebrain.nexus.sourcing.projections.ProjectionProgress._
import ch.epfl.bluebrain.nexus.sourcing.projections.implicits._
import io.circe.Encoder
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike
import org.scalatest.{EitherValues, Inspectors}

class ProjectionProgressSpec extends AnyWordSpecLike with Matchers with Inspectors with TestHelpers with EitherValues {

  "A ProjectionProgress" should {
    val mapping = Map(
      OffsetProgress(Sequence(14L), 2, 0, 1) ->
        jsonContentOf("/indexing/sequence-offset-progress.json"),
      OffsetProgress(TimeBasedUUID(UUID.fromString("ee7e4360-39ca-11e9-9ed5-dbdaa32f8986")), 32, 5, 10) ->
        jsonContentOf("/indexing/timebaseduuid-offset-progress.json"),
      NoProgress ->
        jsonContentOf("/indexing/no-offset-progress.json"),
      OffsetsProgress(Map("noOffset" -> NoProgress, "other" -> OffsetProgress(Sequence(2L), 10L, 2L, 0L))) ->
        jsonContentOf("/indexing/offsets-progress.json")
    )

    "properly encode progress values" in {
      forAll(mapping.toList) {
        case (prog, repr) =>
          Encoder[ProjectionProgress].apply(prog) shouldEqual repr
      }
    }

    "properly decode progress values" in {
      forAll(mapping.toList) {
        case (prog, repr) =>
          repr.as[ProjectionProgress].rightValue shouldEqual prog
      }
    }

    "Add progress" in {
      val progress =
        OffsetsProgress(Map("noOffset" -> NoProgress, "other" -> OffsetProgress(Sequence(2L), 10L, 2L, 0L)))
      progress + ("noOffset", Sequence(1L), ProgressStatus.Failed("some error")) shouldEqual
        OffsetsProgress(
          Map(
            "noOffset" -> OffsetProgress(Sequence(1L), 1L, 0L, 1L),
            "other"    -> OffsetProgress(Sequence(2L), 10L, 2L, 0L)
          )
        )
      progress + ("other", Sequence(3L), ProgressStatus.Discarded) shouldEqual
        OffsetsProgress(Map("noOffset" -> NoProgress, "other" -> OffsetProgress(Sequence(3L), 11L, 3L, 0L)))
    }

    "fetch minimum progress" in {
      val progress = OffsetsProgress(
        Map(
          "one"   -> OffsetProgress(Sequence(1L), 2L, 1L, 0L),
          "other" -> OffsetProgress(Sequence(2L), 10L, 2L, 0L),
          "a"     -> OffsetProgress(Sequence(0L), 0L, 0L, 0L)
        )
      )
      progress.minProgressFilter(_.length > 1) shouldEqual OffsetProgress(Sequence(1L), 2L, 1L, 0L)
      progress.minProgress shouldEqual OffsetProgress(Sequence(0L), 0L, 0L, 0L)
    }

    "test TimeBasedUUIDd ordering" in {
      val time1           = TimeBasedUUID(UUID.fromString("49225740-2019-11ea-a752-ffae2393b6e4")) // 2019-12-16T15:32:36.148Z[UTC]
      val time2           = TimeBasedUUID(UUID.fromString("91be23d0-2019-11ea-a752-ffae2393b6e4")) // 2019-12-16T15:34:37.965Z[UTC]
      val time3           = TimeBasedUUID(UUID.fromString("91f95810-2019-11ea-a752-ffae2393b6e4")) // 2019-12-16T15:34:38.353Z[UTC]
      val offset1: Offset = time1
      val offset2: Offset = time2
      val offset3: Offset = time3
      time1.asInstant.isBefore(time2.asInstant) shouldEqual true
      time2.asInstant.isBefore(time3.asInstant) shouldEqual true
      offset1.gt(offset2) shouldEqual false
      offset3.gt(offset2) shouldEqual true
      List(time2, time1, time3).sorted(offsetOrdering) shouldEqual List(time1, time2, time3)
    }
  }
}
