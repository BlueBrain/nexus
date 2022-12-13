package ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.stream

import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.stream.CompositeBranch.Run
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.nxv
import ch.epfl.bluebrain.nexus.delta.sourcing.offset.Offset
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.ProjectionProgress
import munit.FunSuite

import java.time.Instant

class CompositeProgressSuite extends FunSuite {

  private val source1 = nxv + "source1"
  private val source2 = nxv + "source2"
  private val source3 = nxv + "source3"

  private val target1 = nxv + "target1"
  private val target2 = nxv + "target2"

  private val mainBranch11    = CompositeBranch(source1, target1, Run.Main)
  private val mainBranch12    = CompositeBranch(source1, target2, Run.Main)
  private val mainBranch21    = CompositeBranch(source2, target1, Run.Main)
  private val mainBranch32    = CompositeBranch(source3, target2, Run.Rebuild)
  private val rebuildBranch11 = CompositeBranch(source1, target1, Run.Rebuild)

  private val mainProgress11    = ProjectionProgress(Offset.At(42L), Instant.EPOCH, 5, 2, 1)
  private val mainProgress12    = ProjectionProgress(Offset.At(54L), Instant.EPOCH, 4, 1, 0)
  private val mainProgress21    = ProjectionProgress(Offset.At(78L), Instant.EPOCH, 7, 1, 0)
  private val mainProgress32    = ProjectionProgress(Offset.At(12L), Instant.EPOCH, 7, 1, 0)
  private val rebuildProgress11 = ProjectionProgress(Offset.At(99L), Instant.EPOCH, 4, 1, 0)

  test("Build an empty progress if no branches are provided") {
    assertEquals(CompositeProgress(Map.empty), CompositeProgress(Map.empty, Map.empty))
  }

  test("Build the expected progress from the provided branches") {
    val branches = Map(
      mainBranch11    -> mainProgress11,
      mainBranch12    -> mainProgress12,
      mainBranch21    -> mainProgress21,
      mainBranch32    -> mainProgress32,
      rebuildBranch11 -> rebuildProgress11
    )

    val expectedSources = Map(
      source1 -> mainProgress12.offset,
      source2 -> mainProgress21.offset
    )

    assertEquals(CompositeProgress(branches), CompositeProgress(expectedSources, branches))
  }
}
