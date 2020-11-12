package ch.epfl.bluebrain.nexus.delta.sdk

import java.time.Instant

import cats.data.NonEmptyList
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.nxv
import ch.epfl.bluebrain.nexus.delta.sdk.Resolvers._
import ch.epfl.bluebrain.nexus.delta.sdk.model.Label
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Caller
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Identity.{Anonymous, Authenticated, Group, User}
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.ProjectRef
import ch.epfl.bluebrain.nexus.delta.sdk.model.resolvers.ResolverCommand.{CreateCrossProjectResolver, CreateInProjectResolver, DeprecateResolver, UpdateCrossProjectResolver, UpdateInProjectResolver}
import ch.epfl.bluebrain.nexus.delta.sdk.model.resolvers.ResolverEvent.{CrossProjectResolverCreated, CrossProjectResolverUpdated, InProjectResolverCreated, InProjectResolverUpdated, ResolverDeprecated}
import ch.epfl.bluebrain.nexus.delta.sdk.model.resolvers.ResolverRejection.{DifferentResolverType, IncorrectRev, InvalidIdentities, NoIdentities, ReadOnlyProject, ResolverAlreadyExists, ResolverIsDeprecated, ResolverNotFound}
import ch.epfl.bluebrain.nexus.delta.sdk.model.resolvers.ResolverState.{CrossProjectCurrent, InProjectCurrent, Initial}
import ch.epfl.bluebrain.nexus.delta.sdk.model.resolvers.{Priority, ResolverRejection}
import ch.epfl.bluebrain.nexus.testkit.{IOFixedClock, IOValues, TestHelpers}
import monix.bio.IO
import monix.execution.Scheduler
import org.scalatest.Inspectors
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

class ResolversSpec extends AnyWordSpec with TestHelpers with Matchers with IOValues with IOFixedClock with Inspectors {

  private val epoch   = Instant.EPOCH
  private val instant = Instant.ofEpochMilli(1000L)
  private val realm   = Label.unsafe("myrealm")
  private val bob     = Caller(User("Bob", realm), Set(User("Bob", realm), Group("mygroup", realm), Authenticated(realm)))
  private val alice   = Caller(User("Alice", realm), Set(User("Alice", realm), Group("mygroup2", realm)))

  private val project  = ProjectRef.unsafe("org", "proj")
  private val priority = Priority.unsafe(42)

  private val ipId = nxv + "in-project"
  private val cpId = nxv + "cross-project"

  private val inProjectCurrent = InProjectCurrent(
    ipId,
    project,
    priority,
    2L,
    deprecated = false,
    epoch,
    bob.subject,
    instant,
    Anonymous
  )

  private val crossProjectCurrent = CrossProjectCurrent(
    cpId,
    project,
    Set.empty,
    NonEmptyList.of(
      ProjectRef.unsafe("org2", "proj")
    ),
    bob.identities,
    priority,
    2L,
    deprecated = false,
    epoch,
    alice.subject,
    instant,
    bob.subject
  )

  "The Resolvers evaluation" when {
    implicit val sc: Scheduler = Scheduler.global

    val createInProject    = CreateInProjectResolver(ipId, project, priority)
    val createCrossProject = CreateCrossProjectResolver(
      cpId,
      project,
      Set(nxv + "resource"),
      NonEmptyList.of(
        ProjectRef.unsafe("org2", "proj"),
        ProjectRef.unsafe("org2", "proj2")
      ),
      bob.identities,
      priority
    )

    val updateInProject    = UpdateInProjectResolver(ipId, project, Priority.unsafe(99), 2L)
    val updateCrossProject = UpdateCrossProjectResolver(
      cpId,
      project,
      Set(nxv + "resource"),
      NonEmptyList.of(
        ProjectRef.unsafe("org2", "proj"),
        ProjectRef.unsafe("org2", "proj2")
      ),
      alice.identities,
      Priority.unsafe(99),
      2L
    )

    "evaluating a create command" should {
      implicit val caller: Caller = bob

      "fail if the resolver already exists" in {
        forAll(
          crossProduct(
            List(inProjectCurrent, crossProjectCurrent),
            List(createInProject, createCrossProject)
          )
        ) { case (state, command) =>
          evaluate(_ => IO.unit)(state, command)
            .rejectedWith[ResolverRejection] shouldEqual ResolverAlreadyExists(command.id, command.project)
        }
      }

      "create a in-project creation event" in {
        evaluate(_ => IO.unit)(Initial, createInProject).accepted shouldEqual InProjectResolverCreated(
          ipId,
          project,
          priority,
          1L,
          epoch,
          bob.subject
        )
      }

      "fail if no identities are provided for a cross-project resolver" in {
        evaluate(_ => IO.unit)(Initial, createCrossProject.copy(identities = Set.empty))
          .rejectedWith[ResolverRejection] shouldEqual NoIdentities
      }

      "fail if some provided identities don't belong to the caller for a cross-project resolver" in {
        evaluate(_ => IO.unit)(Initial, createCrossProject.copy(identities = Set(bob.subject, alice.subject)))
          .rejectedWith[ResolverRejection] shouldEqual InvalidIdentities(Set(alice.subject))
      }

      "create a cross-project creation event" in {
        evaluate(_ => IO.unit)(Initial, createCrossProject).accepted shouldEqual CrossProjectResolverCreated(
          cpId,
          project,
          createCrossProject.resourceTypes,
          createCrossProject.projects,
          createCrossProject.identities,
          createCrossProject.priority,
          1L,
          epoch,
          bob.subject
        )
      }
    }

    "evaluate an update command" should {

      implicit val caller: Caller = alice

      "fail if the resolver doesn't exist" in {
        forAll(List(updateInProject, updateCrossProject)) { command =>
          evaluate(_ => IO.unit)(Initial, command)
            .rejectedWith[ResolverRejection] shouldEqual ResolverNotFound(command.id, command.project)
        }
      }

      "fail if the provided revision is incorrect" in {
        forAll(
          crossProduct(
            List(inProjectCurrent, crossProjectCurrent),
            List(updateInProject.copy(rev = 4L), updateCrossProject.copy(rev = 1L))
          )
        ) { case (state, command) =>
          evaluate(_ => IO.unit)(state, command)
            .rejectedWith[ResolverRejection] shouldEqual IncorrectRev(command.rev, state.rev)
        }
      }

      "fail if we try to change from in-project to cross-project type" in {
        evaluate(_ => IO.unit)(inProjectCurrent, updateCrossProject)
          .rejectedWith[ResolverRejection] shouldEqual DifferentResolverType(
          updateCrossProject.id,
          crossProjectType,
          inProjectType
        )
      }

      "create an in-project resolver update event" in {
        evaluate(_ => IO.unit)(inProjectCurrent, updateInProject).accepted shouldEqual InProjectResolverUpdated(
          ipId,
          project,
          updateInProject.priority,
          3L,
          epoch,
          alice.subject
        )
      }

      "fail if no identities are provided for a cross-project resolver" in {
        evaluate(_ => IO.unit)(crossProjectCurrent, updateCrossProject.copy(identities = Set.empty))
          .rejectedWith[ResolverRejection] shouldEqual NoIdentities
      }

      "fail if some provided identities don't belong to the caller for a cross-project resolver" in {
        evaluate(_ => IO.unit)(
          crossProjectCurrent,
          updateCrossProject.copy(identities = Set(bob.subject, alice.subject))
        )
          .rejectedWith[ResolverRejection] shouldEqual InvalidIdentities(Set(bob.subject))
      }

      "fail if we try to change from cross-project to in-project type" in {
        evaluate(_ => IO.unit)(crossProjectCurrent, updateInProject)
          .rejectedWith[ResolverRejection] shouldEqual DifferentResolverType(
          updateInProject.id,
          inProjectType,
          crossProjectType
        )
      }

      "create an cross-project update event" in {
        evaluate(_ => IO.unit)(
          crossProjectCurrent,
          updateCrossProject
        ).accepted shouldEqual CrossProjectResolverUpdated(
          cpId,
          project,
          updateCrossProject.resourceTypes,
          updateCrossProject.projects,
          updateCrossProject.identities,
          updateCrossProject.priority,
          3L,
          epoch,
          alice.subject
        )
      }

    }

    "evaluate a deprecate command" should {
      implicit val caller: Caller = bob

      val deprecateResolver = DeprecateResolver(ipId, project, 2L)

      "fail if the resolver doesn't exist" in {
        evaluate(_ => IO.unit)(Initial, deprecateResolver)
          .rejectedWith[ResolverRejection] shouldEqual ResolverNotFound(deprecateResolver.id, deprecateResolver.project)
      }

      "fail if the provided revision is incorrect" in {
        val incorrectRev = deprecateResolver.copy(rev = 5L)
        forAll(List(inProjectCurrent, crossProjectCurrent)) { state =>
          evaluate(_ => IO.unit)(state, incorrectRev)
            .rejectedWith[ResolverRejection] shouldEqual IncorrectRev(incorrectRev.rev, state.rev)
        }
      }

      "fail if the resolver is already deprecated" in {
        forAll(List(inProjectCurrent.copy(deprecated = true), crossProjectCurrent.copy(deprecated = true))) { state =>
          evaluate(_ => IO.unit)(state, deprecateResolver)
            .rejectedWith[ResolverRejection] shouldEqual ResolverIsDeprecated(state.id)
        }
      }

      "deprecate the resolver" in {
        forAll(List(inProjectCurrent, crossProjectCurrent)) { state =>
          evaluate(_ => IO.unit)(state, deprecateResolver).accepted shouldEqual ResolverDeprecated(
            deprecateResolver.id,
            project,
            3L,
            epoch,
            bob.subject
          )
        }
      }
    }

    "evaluating any command on any state" should {
      "fail if the project is read-only" in {
        implicit val caller: Caller = bob
        forAll(
          crossProduct(
            List(Initial, inProjectCurrent, crossProjectCurrent),
            List(createInProject, createCrossProject, updateInProject, updateCrossProject)
          )
        ) { case (state, command) =>
          evaluate(project => IO.raiseError(ReadOnlyProject(project)))(state, command)
            .rejectedWith[ResolverRejection] shouldEqual ReadOnlyProject(command.project)
        }
      }
    }
  }

  "The Resolvers next state" when {

    val inProjectCreated    = InProjectResolverCreated(ipId, project, Priority.unsafe(22), 1L, epoch, bob.subject)
    val crossProjectCreated = CrossProjectResolverCreated(
      cpId,
      project,
      Set(nxv + "resource"),
      NonEmptyList.of(
        ProjectRef.unsafe("org2", "proj"),
        ProjectRef.unsafe("org2", "proj2")
      ),
      bob.identities,
      Priority.unsafe(55),
      1L,
      epoch,
      bob.subject
    )

    val inProjectUpdated    = InProjectResolverUpdated(ipId, project, Priority.unsafe(40), 3L, instant, bob.subject)
    val crossCrojectUpdated = CrossProjectResolverUpdated(
      cpId,
      project,
      Set(nxv + "r", nxv + "r2"),
      NonEmptyList.of(
        ProjectRef.unsafe("org2", "proj"),
        ProjectRef.unsafe("org3", "proj2")
      ),
      alice.identities,
      Priority.unsafe(55),
      3L,
      epoch,
      bob.subject
    )

    val deprecated = ResolverDeprecated(ipId, project, 3L, instant, alice.subject)

    "applying a create event" should {

      "give a new in-project resolver state from Initial" in {
        next(Initial, inProjectCreated) shouldEqual InProjectCurrent(
          ipId,
          project,
          inProjectCreated.priority,
          1L,
          deprecated = false,
          epoch,
          bob.subject,
          epoch,
          bob.subject
        )
      }

      "give a new cross-project resolver state from Initial" in {
        next(Initial, crossProjectCreated) shouldEqual CrossProjectCurrent(
          cpId,
          project,
          crossProjectCreated.resourceTypes,
          crossProjectCreated.projects,
          crossProjectCreated.identities,
          crossProjectCreated.priority,
          1L,
          deprecated = false,
          epoch,
          bob.subject,
          epoch,
          bob.subject
        )
      }

      "doesn't result in any change on a current state" in {
        forAll(
          crossProduct(
            List(inProjectCurrent, crossProjectCurrent),
            List(inProjectCreated, crossProjectCreated)
          )
        ) { case (state, event) =>
          next(state, event) shouldEqual state
        }
      }
    }

    "applying an update event" should {
      "give a new revision of the in-project resolver state from an existing in-project state" in {
        next(inProjectCurrent, inProjectUpdated) shouldEqual inProjectCurrent.copy(
          priority = inProjectUpdated.priority,
          rev = inProjectUpdated.rev,
          updatedAt = inProjectUpdated.instant,
          updatedBy = inProjectUpdated.subject
        )
      }

      "give a new revision of the cross-project resolver state from an existing cross-project state" in {
        next(crossProjectCurrent, crossCrojectUpdated) shouldEqual crossProjectCurrent.copy(
          resourceTypes = crossCrojectUpdated.resourceTypes,
          projects = crossCrojectUpdated.projects,
          identities = crossCrojectUpdated.identities,
          priority = crossCrojectUpdated.priority,
          rev = crossCrojectUpdated.rev,
          updatedAt = crossCrojectUpdated.instant,
          updatedBy = crossCrojectUpdated.subject
        )
      }

      "doesn't result in any change for other combinations" in {
        forAll(
          List(
            Initial             -> inProjectUpdated,
            Initial             -> crossCrojectUpdated,
            inProjectCurrent    -> crossCrojectUpdated,
            crossProjectCurrent -> inProjectUpdated
          )
        ) { case (state, event) =>
          next(state, event) shouldEqual state
        }
      }
    }

    "applying a deprecate event" should {

      "mark the current state as deprecated for a in-project resolver" in {
        next(inProjectCurrent, deprecated) shouldEqual inProjectCurrent.copy(
          deprecated = true,
          rev = deprecated.rev,
          updatedAt = deprecated.instant,
          updatedBy = deprecated.subject
        )
      }

      "mark the current state as deprecated for a cross-project resolver" in {
        next(crossProjectCurrent, deprecated) shouldEqual crossProjectCurrent.copy(
          deprecated = true,
          rev = deprecated.rev,
          updatedAt = deprecated.instant,
          updatedBy = deprecated.subject
        )
      }

      "doesn't result in any change for other combinations" in {
        forAll(
          List(
            Initial,
            inProjectCurrent.copy(deprecated = true),
            crossProjectCurrent.copy(deprecated = true)
          )
        ) { state =>
          next(state, deprecated) shouldEqual state
        }
      }

    }
  }

}
