package ch.epfl.bluebrain.nexus.delta.sdk

import cats.implicits._
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.nxv
import ch.epfl.bluebrain.nexus.delta.sdk.Resolvers._
import ch.epfl.bluebrain.nexus.delta.sdk.identities.model.Caller
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.{Anonymous, Authenticated, Group, User}
import ch.epfl.bluebrain.nexus.delta.sdk.model.resolvers.IdentityResolution.{ProvidedIdentities, UseCurrentCaller}
import ch.epfl.bluebrain.nexus.delta.sdk.model.resolvers.ResolverCommand.{CreateResolver, DeprecateResolver, TagResolver, UpdateResolver}
import ch.epfl.bluebrain.nexus.delta.sdk.model.resolvers.ResolverEvent.{ResolverCreated, ResolverDeprecated, ResolverTagAdded, ResolverUpdated}
import ch.epfl.bluebrain.nexus.delta.sdk.model.resolvers.ResolverRejection._
import ch.epfl.bluebrain.nexus.delta.sdk.model.resolvers.ResolverState.{Current, Initial}
import ch.epfl.bluebrain.nexus.delta.sdk.model.resolvers.ResolverType._
import ch.epfl.bluebrain.nexus.delta.sdk.model.resolvers.ResolverValue.{CrossProjectValue, InProjectValue}
import ch.epfl.bluebrain.nexus.delta.sdk.model.resolvers.{Priority, ResolverRejection, ResolverType}
import ch.epfl.bluebrain.nexus.delta.sdk.model.NonEmptyList
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Tag.UserTag
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Label
import ch.epfl.bluebrain.nexus.delta.sourcing.model.ProjectRef
import ch.epfl.bluebrain.nexus.testkit.{IOFixedClock, IOValues}
import io.circe.Json
import monix.bio.{IO, UIO}
import monix.execution.Scheduler
import org.scalatest.Inspectors
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import java.time.Instant

class ResolversSpec extends AnyWordSpec with Matchers with IOValues with IOFixedClock with Inspectors {

  private val epoch   = Instant.EPOCH
  private val instant = Instant.ofEpochMilli(1000L)
  private val realm   = Label.unsafe("myrealm")
  private val bob     = Caller(User("Bob", realm), Set(User("Bob", realm), Group("mygroup", realm), Authenticated(realm)))
  private val alice   = Caller(User("Alice", realm), Set(User("Alice", realm), Group("mygroup2", realm)))

  private val project  = ProjectRef.unsafe("org", "proj")
  private val priority = Priority.unsafe(42)

  private val ipId = nxv + "in-project"
  private val cpId = nxv + "cross-project"

  private val inProjectCurrent = Current(
    ipId,
    project,
    InProjectValue(priority),
    Json.obj(),
    Map.empty,
    2L,
    deprecated = false,
    epoch,
    bob.subject,
    instant,
    Anonymous
  )

  private val crossProjectCurrent = Current(
    cpId,
    project,
    CrossProjectValue(
      priority,
      Set.empty,
      NonEmptyList.of(
        ProjectRef.unsafe("org2", "proj")
      ),
      ProvidedIdentities(bob.identities)
    ),
    Json.obj(),
    Map(UserTag.unsafe("tag1") -> 5L),
    2L,
    deprecated = false,
    epoch,
    alice.subject,
    instant,
    bob.subject
  )

  val filteredResolvers: FindResolver = (_, _) => UIO.none

  private def eval = evaluate(filteredResolvers, (_, _) => IO.unit)(_, _)

  "The Resolvers evaluation" when {
    implicit val sc: Scheduler = Scheduler.global

    val createInProject = CreateResolver(
      ipId,
      project,
      InProjectValue(priority),
      Json.obj("inProject" -> Json.fromString("created")),
      bob
    )

    val crossProjectValue = CrossProjectValue(
      priority,
      Set(nxv + "resource"),
      NonEmptyList.of(
        ProjectRef.unsafe("org2", "proj"),
        ProjectRef.unsafe("org2", "proj2")
      ),
      ProvidedIdentities(bob.identities)
    )

    val createCrossProject = CreateResolver(
      cpId,
      project,
      crossProjectValue,
      Json.obj("crossProject" -> Json.fromString("created")),
      bob
    )

    val updateInProject = UpdateResolver(
      ipId,
      project,
      InProjectValue(Priority.unsafe(99)),
      Json.obj("inProject" -> Json.fromString("updated")),
      2L,
      alice
    )

    val updateCrossProject = UpdateResolver(
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
      2L,
      alice
    )

    "evaluating a create command" should {

      "fail if the resolver already exists" in {
        forAll(
          (List(inProjectCurrent, crossProjectCurrent), List(createInProject, createCrossProject)).tupled
        ) { case (state, command) =>
          eval(state, command)
            .rejectedWith[ResolverRejection] shouldEqual ResourceAlreadyExists(command.id, command.project)
        }
      }

      "fail if a resource already exists with the same id" in {
        forAll(List(createInProject, createCrossProject)) { command =>
          evaluate(filteredResolvers, (project, id) => IO.raiseError(ResourceAlreadyExists(id, project)))(
            Initial,
            command
          )
            .rejectedWith[ResolverRejection] shouldEqual ResourceAlreadyExists(command.id, command.project)
        }
      }

      "create a in-project creation event" in {
        eval(Initial, createInProject).accepted shouldEqual ResolverCreated(
          ipId,
          project,
          createInProject.value,
          createInProject.source,
          1L,
          epoch,
          bob.subject
        )
      }

      "fail if no identities are provided for a cross-project resolver" in {
        val invalidValue = crossProjectValue.copy(identityResolution = ProvidedIdentities(Set.empty))
        eval(Initial, createCrossProject.copy(value = invalidValue))
          .rejectedWith[ResolverRejection] shouldEqual NoIdentities
      }

      "fail if the priority already exists" in {
        val findResolver: FindResolver = (_, _) => UIO(Some(nxv + "same-prio"))
        evaluate(findResolver, (_, _) => IO.unit)(Initial, createInProject).rejectedWith[PriorityAlreadyExists]
      }

      "fail if some provided identities don't belong to the caller for a cross-project resolver" in {
        val invalidValue =
          crossProjectValue.copy(identityResolution = ProvidedIdentities(Set(bob.subject, alice.subject)))
        eval(Initial, createCrossProject.copy(value = invalidValue))
          .rejectedWith[ResolverRejection] shouldEqual InvalidIdentities(Set(alice.subject))
      }

      "create a cross-project creation event" in {
        val userCallerResolution = crossProjectValue.copy(identityResolution = UseCurrentCaller)

        forAll(List(createCrossProject, createCrossProject.copy(value = userCallerResolution))) { command =>
          eval(Initial, command).accepted shouldEqual ResolverCreated(
            cpId,
            project,
            command.value,
            command.source,
            1L,
            epoch,
            bob.subject
          )
        }
      }
    }

    "eval an update command" should {

      "fail if the resolver doesn't exist" in {
        forAll(List(updateInProject, updateCrossProject)) { command =>
          eval(Initial, command)
            .rejectedWith[ResolverRejection] shouldEqual ResolverNotFound(command.id, command.project)
        }
      }

      "fail if the provided revision is incorrect" in {
        forAll(
          (
            List(inProjectCurrent, crossProjectCurrent),
            List(updateInProject.copy(rev = 4L), updateCrossProject.copy(rev = 1L))
          ).tupled
        ) { case (state, command) =>
          eval(state, command)
            .rejectedWith[ResolverRejection] shouldEqual IncorrectRev(command.rev, state.rev)
        }
      }

      "fail if the current state is deprecated" in {
        forAll(
          (
            List(inProjectCurrent.copy(deprecated = true), crossProjectCurrent.copy(deprecated = true)),
            List(updateInProject, updateCrossProject)
          ).tupled
        ) { case (state, command) =>
          eval(state, command)
            .rejectedWith[ResolverRejection] shouldEqual ResolverIsDeprecated(state.id)
        }
      }

      "fail if we try to change from in-project to cross-project type" in {
        eval(inProjectCurrent, updateCrossProject)
          .rejectedWith[ResolverRejection] shouldEqual DifferentResolverType(
          updateCrossProject.id,
          CrossProject,
          InProject
        )
      }

      "create an in-project resolver update event" in {
        eval(inProjectCurrent, updateInProject).accepted shouldEqual ResolverUpdated(
          ipId,
          project,
          updateInProject.value,
          updateInProject.source,
          3L,
          epoch,
          alice.subject
        )
      }

      "fail if the priority already exists" in {
        val findResolver: FindResolver = (_, _) => UIO(Some(nxv + "same-prio"))
        evaluate(findResolver, (_, _) => IO.unit)(inProjectCurrent, updateInProject).rejectedWith[PriorityAlreadyExists]
      }

      "fail if no identities are provided for a cross-project resolver" in {
        val invalidValue = crossProjectValue.copy(identityResolution = ProvidedIdentities(Set.empty))
        eval(crossProjectCurrent, updateCrossProject.copy(value = invalidValue))
          .rejectedWith[ResolverRejection] shouldEqual NoIdentities
      }

      "fail if some provided identities don't belong to the caller for a cross-project resolver" in {
        val invalidValue =
          crossProjectValue.copy(identityResolution = ProvidedIdentities(Set(bob.subject, alice.subject)))
        eval(
          crossProjectCurrent,
          updateCrossProject.copy(value = invalidValue)
        )
          .rejectedWith[ResolverRejection] shouldEqual InvalidIdentities(Set(bob.subject))
      }

      "fail if we try to change from cross-project to in-project type" in {
        eval(crossProjectCurrent, updateInProject)
          .rejectedWith[ResolverRejection] shouldEqual DifferentResolverType(
          updateInProject.id,
          InProject,
          CrossProject
        )
      }

      "create an cross-project update event" in {
        val userCallerResolution = crossProjectValue.copy(identityResolution = UseCurrentCaller)

        forAll(List(updateCrossProject, updateCrossProject.copy(value = userCallerResolution))) { command =>
          eval(
            crossProjectCurrent,
            command
          ).accepted shouldEqual ResolverUpdated(
            cpId,
            project,
            command.value,
            command.source,
            3L,
            epoch,
            alice.subject
          )
        }
      }

    }

    "eval a tag command" should {

      val tagResolver = TagResolver(ipId, project, 1L, UserTag.unsafe("tag1"), 2L, bob.subject)

      "fail if the resolver doesn't exist" in {
        eval(Initial, tagResolver)
          .rejectedWith[ResolverRejection] shouldEqual ResolverNotFound(tagResolver.id, tagResolver.project)
      }

      "fail if the provided revision is incorrect" in {
        val incorrectRev = tagResolver.copy(rev = 5L)
        forAll(List(inProjectCurrent, crossProjectCurrent)) { state =>
          eval(state, incorrectRev)
            .rejectedWith[ResolverRejection] shouldEqual IncorrectRev(incorrectRev.rev, state.rev)
        }
      }

      "succeed if  the resolver is deprecated" in {
        forAll(List(inProjectCurrent.copy(deprecated = true), crossProjectCurrent.copy(deprecated = true))) { state =>
          eval(state, tagResolver).accepted shouldEqual ResolverTagAdded(
            tagResolver.id,
            project,
            state.value.tpe,
            targetRev = tagResolver.targetRev,
            tag = tagResolver.tag,
            3L,
            epoch,
            bob.subject
          )
        }
      }

      "fail if the version to tag is invalid" in {
        val incorrectTagRev = tagResolver.copy(targetRev = 5L)
        forAll(List(inProjectCurrent, crossProjectCurrent)) { state =>
          eval(state, incorrectTagRev)
            .rejectedWith[ResolverRejection] shouldEqual RevisionNotFound(incorrectTagRev.targetRev, state.rev)
        }
      }

      "create a tag event" in {
        forAll(List(inProjectCurrent, crossProjectCurrent)) { state =>
          eval(state, tagResolver).accepted shouldEqual ResolverTagAdded(
            tagResolver.id,
            project,
            state.value.tpe,
            targetRev = tagResolver.targetRev,
            tag = tagResolver.tag,
            3L,
            epoch,
            bob.subject
          )
        }
      }
    }

    "eval a deprecate command" should {

      val deprecateResolver = DeprecateResolver(ipId, project, 2L, bob.subject)

      "fail if the resolver doesn't exist" in {
        eval(Initial, deprecateResolver)
          .rejectedWith[ResolverRejection] shouldEqual ResolverNotFound(deprecateResolver.id, deprecateResolver.project)
      }

      "fail if the provided revision is incorrect" in {
        val incorrectRev = deprecateResolver.copy(rev = 5L)
        forAll(List(inProjectCurrent, crossProjectCurrent)) { state =>
          eval(state, incorrectRev)
            .rejectedWith[ResolverRejection] shouldEqual IncorrectRev(incorrectRev.rev, state.rev)
        }
      }

      "fail if the resolver is already deprecated" in {
        forAll(List(inProjectCurrent.copy(deprecated = true), crossProjectCurrent.copy(deprecated = true))) { state =>
          eval(state, deprecateResolver)
            .rejectedWith[ResolverRejection] shouldEqual ResolverIsDeprecated(state.id)
        }
      }

      "deprecate the resolver" in {
        forAll(List(inProjectCurrent, crossProjectCurrent)) { state =>
          eval(state, deprecateResolver).accepted shouldEqual ResolverDeprecated(
            deprecateResolver.id,
            project,
            state.value.tpe,
            3L,
            epoch,
            bob.subject
          )
        }
      }
    }
  }

  "The Resolvers next state" when {

    "applying a create event" should {

      val inProjectCreated = ResolverCreated(
        ipId,
        project,
        InProjectValue(Priority.unsafe(22)),
        Json.obj("inProject" -> Json.fromString("created")),
        1L,
        epoch,
        bob.subject
      )

      val crossProjectCreated = ResolverCreated(
        cpId,
        project,
        CrossProjectValue(
          Priority.unsafe(55),
          Set(nxv + "resource"),
          NonEmptyList.of(
            ProjectRef.unsafe("org2", "proj"),
            ProjectRef.unsafe("org2", "proj2")
          ),
          ProvidedIdentities(bob.identities)
        ),
        Json.obj("crossProject" -> Json.fromString("created")),
        1L,
        epoch,
        bob.subject
      )

      "give a new in-project resolver state from Initial" in {
        next(Initial, inProjectCreated) shouldEqual Current(
          ipId,
          project,
          inProjectCreated.value,
          inProjectCreated.source,
          Map.empty,
          1L,
          deprecated = false,
          epoch,
          bob.subject,
          epoch,
          bob.subject
        )
      }

      "give a new cross-project resolver state from Initial" in {
        next(Initial, crossProjectCreated) shouldEqual Current(
          cpId,
          project,
          crossProjectCreated.value,
          crossProjectCreated.source,
          Map.empty,
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
          (
            List(inProjectCurrent, crossProjectCurrent),
            List(inProjectCreated, crossProjectCreated)
          ).tupled
        ) { case (state, event) =>
          next(state, event) shouldEqual state
        }
      }
    }

    "applying an update event" should {
      val inProjectUpdated = ResolverUpdated(
        ipId,
        project,
        InProjectValue(Priority.unsafe(40)),
        Json.obj("inProject" -> Json.fromString("updated")),
        3L,
        instant,
        bob.subject
      )

      val crossCrojectUpdated = ResolverUpdated(
        cpId,
        project,
        CrossProjectValue(
          Priority.unsafe(999),
          Set(nxv + "r", nxv + "r2"),
          NonEmptyList.of(
            ProjectRef.unsafe("org2", "proj"),
            ProjectRef.unsafe("org3", "proj2")
          ),
          ProvidedIdentities(alice.identities)
        ),
        Json.obj("crossProject" -> Json.fromString("updated")),
        3L,
        epoch,
        bob.subject
      )

      "give a new revision of the in-project resolver state from an existing in-project state" in {
        next(inProjectCurrent, inProjectUpdated) shouldEqual inProjectCurrent.copy(
          value = inProjectUpdated.value,
          source = inProjectUpdated.source,
          rev = inProjectUpdated.rev,
          updatedAt = inProjectUpdated.instant,
          updatedBy = inProjectUpdated.subject
        )
      }

      "give a new revision of the cross-project resolver state from an existing cross-project state" in {
        next(crossProjectCurrent, crossCrojectUpdated) shouldEqual crossProjectCurrent.copy(
          value = crossCrojectUpdated.value,
          source = crossCrojectUpdated.source,
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

    "applying a tag event" should {
      val resolverTagAdded =
        ResolverTagAdded(ipId, project, ResolverType.InProject, 1L, UserTag.unsafe("tag2"), 3L, instant, alice.subject)

      "update the tag list" in {
        forAll(List(inProjectCurrent, crossProjectCurrent)) { state =>
          next(state, resolverTagAdded) shouldEqual state.copy(
            tags = state.tags + (resolverTagAdded.tag -> resolverTagAdded.targetRev),
            rev = resolverTagAdded.rev,
            updatedAt = resolverTagAdded.instant,
            updatedBy = resolverTagAdded.subject
          )
        }
      }

      "doesn't result in any change on an initial state" in {
        next(Initial, resolverTagAdded) shouldEqual Initial
      }

    }

    "applying a deprecate event" should {

      val deprecated = ResolverDeprecated(ipId, project, ResolverType.InProject, 3L, instant, alice.subject)

      "mark the current state as deprecated for a resolver" in {
        forAll(List(inProjectCurrent, crossProjectCurrent)) { state =>
          next(state, deprecated) shouldEqual state.copy(
            deprecated = true,
            rev = deprecated.rev,
            updatedAt = deprecated.instant,
            updatedBy = deprecated.subject
          )
        }
      }

      "doesn't result in any change on an initial state" in {
        next(Initial, deprecated) shouldEqual Initial
      }

    }
  }

}
