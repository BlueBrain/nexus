package ch.epfl.bluebrain.nexus.delta.sdk

import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.nxv
import ch.epfl.bluebrain.nexus.delta.sdk.ProjectReferenceFinder.ProjectReferenceMap
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.ProjectRef
import ch.epfl.bluebrain.nexus.testkit.IOValues
import monix.bio.UIO
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

class ProjectReferenceFinderSpec extends AnyWordSpecLike with Matchers with IOValues {

  val project1 = ProjectRef.unsafe("org", "proj")
  val project2 = ProjectRef.unsafe("org", "proj2")
  val project3 = ProjectRef.unsafe("org", "proj3")

  val finder1: ProjectReferenceFinder = (_: ProjectRef) =>
    UIO.pure(
      ProjectReferenceMap.of(project1 -> List(nxv + "id1", nxv + "id2"))
    )

  val finder2: ProjectReferenceFinder = (_: ProjectRef) =>
    UIO.pure(
      ProjectReferenceMap.of(
        project2 -> List(nxv + "id3"),
        project3 -> List(nxv + "id3", nxv + "id4")
      )
    )

  val finder3: ProjectReferenceFinder = (_: ProjectRef) =>
    UIO.pure(
      ProjectReferenceMap.of(
        project1 -> List(nxv + "id5"),
        project3 -> List(nxv + "id2", nxv + "id6")
      )
    )

  "combining finders" should {
    "work" in {
      val combined = ProjectReferenceFinder.combine(List(finder1, finder2, finder3))

      val result = combined(ProjectRef.unsafe("org", "xxx")).accepted.value
      result.keySet shouldEqual Set(project1, project2, project3)

      result(project1) should contain theSameElementsAs List(nxv + "id1", nxv + "id2", nxv + "id5")
      result(project2) should contain theSameElementsAs List(nxv + "id3")
      result(project3) should contain theSameElementsAs List(nxv + "id2", nxv + "id3", nxv + "id4", nxv + "id6")
    }

  }

}
