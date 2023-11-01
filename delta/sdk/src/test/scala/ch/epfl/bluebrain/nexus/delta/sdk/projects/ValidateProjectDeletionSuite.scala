package ch.epfl.bluebrain.nexus.delta.sdk.projects

import cats.effect.IO
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.nxv
import ch.epfl.bluebrain.nexus.delta.sdk.projects.model.ProjectRejection.{ProjectDeletionIsDisabled, ProjectIsReferenced}
import ch.epfl.bluebrain.nexus.delta.sourcing.model.EntityDependency.ReferencedBy
import ch.epfl.bluebrain.nexus.delta.sourcing.model.ProjectRef
import ch.epfl.bluebrain.nexus.testkit.mu.ce.CatsEffectSuite

class ValidateProjectDeletionSuite extends CatsEffectSuite {

  private val noReferences   = ProjectRef.unsafe("org", "no-refs")
  private val withReferences = ProjectRef.unsafe("org", "has-refs")

  private val references = Set(
    ReferencedBy(noReferences, nxv + "ref1"),
    ReferencedBy(noReferences, nxv + "ref2")
  )

  private val deletionEnabled = ValidateProjectDeletion(
    fetchReferences = {
      case `noReferences`   => IO.pure(Set.empty)
      case `withReferences` => IO.pure(references)
      case _                => IO.raiseError(new IllegalStateException(s"Only '$noReferences', '$withReferences' are accepted"))
    },
    enabled = true
  )

  private val deletionDisabled = ValidateProjectDeletion(
    _ => IO.raiseError(new IllegalStateException("Should never be called as deletion is disabled.")),
    enabled = false
  )

  test("Pass if no reference is returned") {
    deletionEnabled.apply(noReferences)
  }

  test("Fail if a reference is detected") {
    deletionEnabled
      .apply(withReferences)
      .intercept(ProjectIsReferenced(withReferences, Map(noReferences -> Set(nxv + "ref1", nxv + "ref2"))))
  }

  test("Fail as project deletion is disabled") {
    deletionDisabled
      .apply(noReferences)
      .intercept(ProjectDeletionIsDisabled)
  }

}
