package ch.epfl.bluebrain.nexus.delta.sdk.resolvers

import cats.data.NonEmptyList
import cats.effect.IO
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.nxv
import ch.epfl.bluebrain.nexus.delta.sdk.resolvers.Resolvers.evaluate
import ch.epfl.bluebrain.nexus.delta.sdk.resolvers.model.IdentityResolution.{ProvidedIdentities, UseCurrentCaller}
import ch.epfl.bluebrain.nexus.delta.sdk.resolvers.model.Priority
import ch.epfl.bluebrain.nexus.delta.sdk.resolvers.model.ResolverCommand.{CreateResolver, DeprecateResolver, UpdateResolver}
import ch.epfl.bluebrain.nexus.delta.sdk.resolvers.model.ResolverEvent.{ResolverCreated, ResolverDeprecated, ResolverUpdated}
import ch.epfl.bluebrain.nexus.delta.sdk.resolvers.model.ResolverRejection._
import ch.epfl.bluebrain.nexus.delta.sdk.resolvers.model.ResolverType.{CrossProject, InProject}
import ch.epfl.bluebrain.nexus.delta.sdk.resolvers.model.ResolverValue.{CrossProjectValue, InProjectValue}
import ch.epfl.bluebrain.nexus.delta.sourcing.model.ProjectRef
import ch.epfl.bluebrain.nexus.testkit.mu.NexusSuite
import io.circe.Json

class ResolversEvaluateSuite extends NexusSuite with ResolverStateMachineFixture {

  private val validatePriority: ValidatePriority = (_, _, _) => IO.unit

  private def eval = evaluate(validatePriority, clock)(_, _)

  private val createInProject = CreateResolver(
    ipId,
    project,
    InProjectValue(priority),
    Json.obj("inProject" -> Json.fromString("created")),
    bob
  )

  test("Creation fails if the in-project resolver already exists") {
    eval(Some(inProjectCurrent), createInProject).interceptEquals(
      ResourceAlreadyExists(createInProject.id, createInProject.project)
    )
  }

  test("Creation fails if the priority already exists") {
    val validatePriority: ValidatePriority =
      (ref, _, priority) => IO.raiseError(PriorityAlreadyExists(ref, nxv + "same-prio", priority))
    evaluate(validatePriority, clock)(None, createInProject).intercept[PriorityAlreadyExists]
  }

  test("Creation succeeds for a in-project resolver") {
    val expected = ResolverCreated(
      ipId,
      project,
      createInProject.value,
      createInProject.source,
      1,
      epoch,
      bob.subject
    )
    eval(None, createInProject).assertEquals(expected)
  }

  private val crossProjectValue = CrossProjectValue(
    priority,
    Set(nxv + "resource"),
    NonEmptyList.of(
      ProjectRef.unsafe("org2", "proj"),
      ProjectRef.unsafe("org2", "proj2")
    ),
    ProvidedIdentities(bob.identities)
  )

  private val createCrossProject = CreateResolver(
    cpId,
    project,
    crossProjectValue,
    Json.obj("crossProject" -> Json.fromString("created")),
    bob
  )

  test("Creation fails if the cross-project resolver already exists") {
    eval(Some(crossProjectCurrent), createCrossProject).interceptEquals(
      ResourceAlreadyExists(createCrossProject.id, createCrossProject.project)
    )
  }

  test("Creation fails if no identities are provided for a cross-project resolver") {
    val invalidValue   = crossProjectValue.copy(identityResolution = ProvidedIdentities(Set.empty))
    val invalidCommand = createCrossProject.copy(value = invalidValue)
    eval(None, invalidCommand).interceptEquals(NoIdentities)
  }

  test("Creation fails if no identities are provided for a cross-project resolver") {
    val invalidValue   =
      crossProjectValue.copy(identityResolution = ProvidedIdentities(Set(bob.subject, alice.subject)))
    val invalidCommand = createCrossProject.copy(value = invalidValue)
    eval(None, invalidCommand).interceptEquals(InvalidIdentities(Set(alice.subject)))
  }

  test("Creation succeeds for a cross-project resolver with provided identities") {
    val expected = ResolverCreated(
      cpId,
      project,
      createCrossProject.value,
      createCrossProject.source,
      1,
      epoch,
      bob.subject
    )
    eval(None, createCrossProject).assertEquals(expected)
  }

  test("Creation succeeds for a cross-project resolver with provided identities") {
    val useCaller = crossProjectValue.copy(identityResolution = UseCurrentCaller)
    val command   = createCrossProject.copy(value = useCaller)
    val expected  = ResolverCreated(
      cpId,
      project,
      command.value,
      command.source,
      1,
      epoch,
      bob.subject
    )
    eval(None, command).assertEquals(expected)
  }

  private val updateInProject = UpdateResolver(
    ipId,
    project,
    InProjectValue(Priority.unsafe(99)),
    Json.obj("inProject" -> Json.fromString("updated")),
    2,
    alice
  )

  test("Update fails if the in-project resolver does not exist") {
    eval(None, updateInProject).interceptEquals(ResolverNotFound(updateInProject.id, updateInProject.project))
  }

  test("Update fails if the provided revision for the in-project resolver is incorrect") {
    val invalidCommand = updateInProject.copy(rev = 4)
    eval(Some(inProjectCurrent), invalidCommand).interceptEquals(IncorrectRev(invalidCommand.rev, inProjectCurrent.rev))
  }

  test("Update fails if the in-project resolver is deprecated") {
    val deprecated = inProjectCurrent.copy(deprecated = true)
    eval(Some(deprecated), updateInProject).interceptEquals(ResolverIsDeprecated(deprecated.id))
  }

  test("Update fails  if we try to change from in-project to cross-project type") {
    val expectedError = DifferentResolverType(updateCrossProject.id, CrossProject, InProject)
    eval(Some(inProjectCurrent), updateCrossProject).interceptEquals(expectedError)
  }

  test("Update fails  if the priority already exists") {
    val validatePriority: ValidatePriority =
      (ref, _, priority) => IO.raiseError(PriorityAlreadyExists(ref, nxv + "same-priority", priority))
    evaluate(validatePriority, clock)(Some(inProjectCurrent), updateInProject).intercept[PriorityAlreadyExists]
  }

  test("Update succeeds for a in-project resolver") {
    val expected = ResolverUpdated(
      ipId,
      project,
      updateInProject.value,
      updateInProject.source,
      3,
      epoch,
      alice.subject
    )
    eval(Some(inProjectCurrent), updateInProject).assertEquals(expected)
  }

  private val updateCrossProject = UpdateResolver(
    cpId,
    project,
    CrossProjectValue(
      Priority.unsafe(99),
      Set(nxv + "resource"),
      NonEmptyList.of(
        ProjectRef.unsafe("org2", "proj"),
        ProjectRef.unsafe("org2", "proj2")
      ),
      ProvidedIdentities(alice.identities)
    ),
    Json.obj("crossProject" -> Json.fromString("updated")),
    2,
    alice
  )

  test("Update fails if the cross-project resolver does not exist") {
    eval(None, updateCrossProject).interceptEquals(ResolverNotFound(updateCrossProject.id, updateCrossProject.project))
  }

  test("Update fails if the provided revision for the cross-project resolver is incorrect") {
    val invalidCommand = updateCrossProject.copy(rev = 1)
    eval(Some(crossProjectCurrent), invalidCommand).interceptEquals(
      IncorrectRev(invalidCommand.rev, inProjectCurrent.rev)
    )
  }

  test("Update fails if the cross-project resolver is deprecated") {
    val deprecated = crossProjectCurrent.copy(deprecated = true)
    eval(Some(deprecated), updateCrossProject).interceptEquals(ResolverIsDeprecated(deprecated.id))
  }

  test("Update fails if no identities are provided for a cross-project resolver") {
    val invalidValue   = crossProjectValue.copy(identityResolution = ProvidedIdentities(Set.empty))
    val invalidCommand = updateCrossProject.copy(value = invalidValue)
    eval(Some(crossProjectCurrent), invalidCommand).interceptEquals(NoIdentities)
  }

  test("Update fails if some provided identities don't belong to the caller for a cross-project resolver") {
    val invalidValue   = crossProjectValue.copy(identityResolution = ProvidedIdentities(Set(bob.subject, alice.subject)))
    val invalidCommand = updateCrossProject.copy(value = invalidValue)
    eval(Some(crossProjectCurrent), invalidCommand).interceptEquals(InvalidIdentities(Set(bob.subject)))
  }

  test("Update fails if we try to change from cross-project to in-project type") {
    val expectedError = DifferentResolverType(updateInProject.id, InProject, CrossProject)
    eval(Some(crossProjectCurrent), updateInProject).interceptEquals(expectedError)
  }

  test("Update succeeds for a cross-project resolver with provided entities") {
    val expected = ResolverUpdated(
      cpId,
      project,
      updateCrossProject.value,
      updateCrossProject.source,
      3,
      epoch,
      alice.subject
    )
    eval(Some(crossProjectCurrent), updateCrossProject).assertEquals(expected)
  }

  test("Update succeeds for a cross-project resolver with current caller") {
    val userCallerResolution = crossProjectValue.copy(identityResolution = UseCurrentCaller)
    val command              = updateCrossProject.copy(value = userCallerResolution)
    val expected             = ResolverUpdated(
      cpId,
      project,
      command.value,
      command.source,
      3,
      epoch,
      alice.subject
    )
    eval(Some(crossProjectCurrent), command).assertEquals(expected)
  }

  private val deprecateCommand = DeprecateResolver(ipId, project, 2, bob.subject)

  test("Deprecate fails if resolver does not exist") {
    val expectedError = ResolverNotFound(deprecateCommand.id, deprecateCommand.project)
    eval(None, deprecateCommand).interceptEquals(expectedError)
  }

  bothStates.foreach { state =>
    test(s"Deprecate fails for an ${state.value.tpe} resolver if the provided revision is incorrect") {
      val incorrectRev  = deprecateCommand.copy(rev = 5)
      val expectedError = IncorrectRev(incorrectRev.rev, state.rev)
      eval(Some(state), incorrectRev).interceptEquals(expectedError)
    }

    test(s"Deprecate fails for an ${state.value.tpe} resolver if it is already deprecated") {
      val deprecated    = state.copy(deprecated = true)
      val expectedError = ResolverIsDeprecated(deprecated.id)
      eval(Some(deprecated), deprecateCommand).interceptEquals(expectedError)
    }

    test(s"Deprecate succeeds for an ${state.value.tpe} resolver") {
      val expected = ResolverDeprecated(deprecateCommand.id, project, state.value.tpe, 3, epoch, bob.subject)
      eval(Some(state), deprecateCommand).assertEquals(expected)
    }
  }
}
