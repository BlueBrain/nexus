package ch.epfl.bluebrain.nexus.delta.sdk.projects

import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.nxv
import ch.epfl.bluebrain.nexus.delta.sdk.projects.model.ProjectRejection.ProjectIsReferenced
import ch.epfl.bluebrain.nexus.delta.sourcing.model.EntityDependency.ReferencedBy
import ch.epfl.bluebrain.nexus.delta.sourcing.model.ProjectRef
import ch.epfl.bluebrain.nexus.testkit.bio.BioSuite
import monix.bio.{IO, UIO}

class ProjectReferenceFinderSuite extends BioSuite {

  private val noReferences   = ProjectRef.unsafe("org", "no-refs")
  private val withReferences = ProjectRef.unsafe("org", "has-refs")

  private val references = Set(
    ReferencedBy(noReferences, nxv + "ref1"),
    ReferencedBy(noReferences, nxv + "ref2")
  )

  private val referenceFinder = ProjectReferenceFinder(
    fetchReferences = {
      case `noReferences`   => UIO.pure(Set.empty)
      case `withReferences` => UIO.pure(references)
      case _                => IO.terminate(new IllegalStateException(s"Only '$noReferences', '$withReferences' are accepted"))
    }
  )

  test("Pass if no reference is returned") {
    referenceFinder.raiseIfAny(noReferences)
  }

  test("Fail if a reference is detected") {
    referenceFinder
      .raiseIfAny(withReferences)
      .error(ProjectIsReferenced(withReferences, Map(noReferences -> Set(nxv + "ref1", nxv + "ref2"))))
  }

}
