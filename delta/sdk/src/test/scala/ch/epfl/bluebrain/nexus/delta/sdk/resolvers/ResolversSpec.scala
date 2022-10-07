package ch.epfl.bluebrain.nexus.delta.sdk.resolvers

import cats.data.NonEmptyList
import cats.implicits._
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.nxv
import ch.epfl.bluebrain.nexus.delta.sdk.identities.model.Caller
import ch.epfl.bluebrain.nexus.delta.sdk.model.Tags
import ch.epfl.bluebrain.nexus.delta.sdk.resolvers.Resolvers.{ValidatePriority, evaluate, next}
import ch.epfl.bluebrain.nexus.delta.sdk.resolvers.model.IdentityResolution.{ProvidedIdentities, UseCurrentCaller}
import ch.epfl.bluebrain.nexus.delta.sdk.resolvers.model.ResolverCommand.{CreateResolver, DeprecateResolver, TagResolver, UpdateResolver}
import ch.epfl.bluebrain.nexus.delta.sdk.resolvers.model.ResolverEvent.{ResolverCreated, ResolverDeprecated, ResolverTagAdded, ResolverUpdated}
import ch.epfl.bluebrain.nexus.delta.sdk.resolvers.model.ResolverRejection.{DifferentResolverType, IncorrectRev, InvalidIdentities, NoIdentities, PriorityAlreadyExists, ResolverIsDeprecated, ResolverNotFound, ResourceAlreadyExists, RevisionNotFound}
import ch.epfl.bluebrain.nexus.delta.sdk.resolvers.model.ResolverType.{CrossProject, InProject}
import ch.epfl.bluebrain.nexus.delta.sdk.resolvers.model.ResolverValue.{CrossProjectValue, InProjectValue}
import ch.epfl.bluebrain.nexus.delta.sdk.resolvers.model.{Priority, ResolverRejection, ResolverState, ResolverType}
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.{Anonymous, Authenticated, Group, User}
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Tag.UserTag
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{Label, ProjectRef}
import ch.epfl.bluebrain.nexus.testkit.{IOFixedClock, IOValues}
import io.circe.Json
import monix.bio.{IO, UIO}
import monix.execution.Scheduler
import org.scalatest.{Inspectors, OptionValues}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import java.time.Instant

class ResolversSpec
    extends AnyWordSpec
    with Matchers
    with OptionValues
    with IOValues
    with IOFixedClock
    with Inspectors {

  private val epoch   = Instant.EPOCH
  private val instant = Instant.ofEpochMilli(1000L)
  private val realm   = Label.unsafe("myrealm")
  private val bob     = Caller(User("Bob", realm), Set(User("Bob", realm), Group("mygroup", realm), Authenticated(realm)))
  private val alice   = Caller(User("Alice", realm), Set(User("Alice", realm), Group("mygroup2", realm)))

  private val project  = ProjectRef.unsafe("org", "proj")
  private val priority = Priority.unsafe(42)

  private val ipId = nxv + "in-project"
  private val cpId = nxv + "cross-project"

  private val inProjectCurrent = ResolverState(
    ipId,
    project,
    InProjectValue(priority),
    Json.obj(),
    Tags.empty,
    2,
    deprecated = false,
    epoch,
    bob.subject,
    instant,
    Anonymous
  )

  private val crossProjectCurrent = ResolverState(
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
    Tags(UserTag.unsafe("tag1") -> 5),
    2,
    deprecated = false,
    epoch,
    alice.subject,
    instant,
    bob.subject
  )

  val validatePriority: ValidatePriority = (_, _, _) => UIO.unit

  private def eval = evaluate(validatePriority)(_, _)

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
      2,
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
      2,
      alice
    )

    "evaluating a create command" should {

      "fail if the resolver already exists" in {
        forAll(
          (List(inProjectCurrent, crossProjectCurrent), List(createInProject, createCrossProject)).tupled
        ) { case (state, command) =>
          eval(Some(state), command).rejected shouldEqual ResourceAlreadyExists(command.id, command.project)
        }
      }

      "create a in-project creation event" in {
        eval(None, createInProject).accepted shouldEqual ResolverCreated(
          ipId,
          project,
          createInProject.value,
          createInProject.source,
          1,
          epoch,
          bob.subject
        )
      }

      "fail if no identities are provided for a cross-project resolver" in {
        val invalidValue = crossProjectValue.copy(identityResolution = ProvidedIdentities(Set.empty))
        eval(None, createCrossProject.copy(value = invalidValue)).rejected shouldEqual NoIdentities
      }

      "fail if the priority already exists" in {
        val validatePriority: ValidatePriority =
          (ref, _, priority) => IO.raiseError(PriorityAlreadyExists(ref, nxv + "same-prio", priority))
        evaluate(validatePriority)(None, createInProject).rejectedWith[PriorityAlreadyExists]
      }

      "fail if some provided identities don't belong to the caller for a cross-project resolver" in {
        val invalidValue =
          crossProjectValue.copy(identityResolution = ProvidedIdentities(Set(bob.subject, alice.subject)))
        eval(None, createCrossProject.copy(value = invalidValue)).rejected shouldEqual InvalidIdentities(
          Set(alice.subject)
        )
      }

      "create a cross-project creation event" in {
        val userCallerResolution = crossProjectValue.copy(identityResolution = UseCurrentCaller)

        forAll(List(createCrossProject, createCrossProject.copy(value = userCallerResolution))) { command =>
          eval(None, command).accepted shouldEqual ResolverCreated(
            cpId,
            project,
            command.value,
            command.source,
            1,
            epoch,
            bob.subject
          )
        }
      }
    }

    "eval an update command" should {

      "fail if the resolver doesn't exist" in {
        forAll(List(updateInProject, updateCrossProject)) { command =>
          eval(None, command).rejected shouldEqual ResolverNotFound(command.id, command.project)
        }
      }

      "fail if the provided revision is incorrect" in {
        forAll(
          (
            List(inProjectCurrent, crossProjectCurrent),
            List(updateInProject.copy(rev = 4), updateCrossProject.copy(rev = 1))
          ).tupled
        ) { case (state, command) =>
          eval(Some(state), command).rejected shouldEqual IncorrectRev(command.rev, state.rev)
        }
      }

      "fail if the current state is deprecated" in {
        forAll(
          (
            List(inProjectCurrent.copy(deprecated = true), crossProjectCurrent.copy(deprecated = true)),
            List(updateInProject, updateCrossProject)
          ).tupled
        ) { case (state, command) =>
          eval(Some(state), command).rejected shouldEqual ResolverIsDeprecated(state.id)
        }
      }

      "fail if we try to change from in-project to cross-project type" in {
        eval(Some(inProjectCurrent), updateCrossProject).rejected shouldEqual DifferentResolverType(
          updateCrossProject.id,
          CrossProject,
          InProject
        )
      }

      "create an in-project resolver update event" in {
        eval(Some(inProjectCurrent), updateInProject).accepted shouldEqual ResolverUpdated(
          ipId,
          project,
          updateInProject.value,
          updateInProject.source,
          3,
          epoch,
          alice.subject
        )
      }

      "fail if the priority already exists" in {
        val validatePriority: ValidatePriority =
          (ref, _, priority) => IO.raiseError(PriorityAlreadyExists(ref, nxv + "same-prio", priority))
        evaluate(validatePriority)(Some(inProjectCurrent), updateInProject).rejectedWith[PriorityAlreadyExists]
      }

      "fail if no identities are provided for a cross-project resolver" in {
        val invalidValue = crossProjectValue.copy(identityResolution = ProvidedIdentities(Set.empty))
        eval(Some(crossProjectCurrent), updateCrossProject.copy(value = invalidValue)).rejected shouldEqual NoIdentities
      }

      "fail if some provided identities don't belong to the caller for a cross-project resolver" in {
        val invalidValue =
          crossProjectValue.copy(identityResolution = ProvidedIdentities(Set(bob.subject, alice.subject)))
        eval(
          Some(crossProjectCurrent),
          updateCrossProject.copy(value = invalidValue)
        ).rejected shouldEqual InvalidIdentities(Set(bob.subject))
      }

      "fail if we try to change from cross-project to in-project type" in {
        eval(Some(crossProjectCurrent), updateInProject).rejected shouldEqual DifferentResolverType(
          updateInProject.id,
          InProject,
          CrossProject
        )
      }

      "create an cross-project update event" in {
        val userCallerResolution = crossProjectValue.copy(identityResolution = UseCurrentCaller)

        forAll(List(updateCrossProject, updateCrossProject.copy(value = userCallerResolution))) { command =>
          eval(Some(crossProjectCurrent), command).accepted shouldEqual ResolverUpdated(
            cpId,
            project,
            command.value,
            command.source,
            3,
            epoch,
            alice.subject
          )
        }
      }

    }

    "eval a tag command" should {

      val tagResolver = TagResolver(ipId, project, 1, UserTag.unsafe("tag1"), 2, bob.subject)

      "fail if the resolver doesn't exist" in {
        eval(None, tagResolver).rejected shouldEqual ResolverNotFound(tagResolver.id, tagResolver.project)
      }

      "fail if the provided revision is incorrect" in {
        val incorrectRev = tagResolver.copy(rev = 5)
        forAll(List(inProjectCurrent, crossProjectCurrent)) { state =>
          eval(Some(state), incorrectRev)
            .rejectedWith[ResolverRejection] shouldEqual IncorrectRev(incorrectRev.rev, state.rev)
        }
      }

      "succeed if  the resolver is deprecated" in {
        forAll(List(inProjectCurrent.copy(deprecated = true), crossProjectCurrent.copy(deprecated = true))) { state =>
          eval(Some(state), tagResolver).accepted shouldEqual ResolverTagAdded(
            tagResolver.id,
            project,
            state.value.tpe,
            targetRev = tagResolver.targetRev,
            tag = tagResolver.tag,
            3,
            epoch,
            bob.subject
          )
        }
      }

      "fail if the version to tag is invalid" in {
        val incorrectTagRev = tagResolver.copy(targetRev = 5)
        forAll(List(inProjectCurrent, crossProjectCurrent)) { state =>
          eval(Some(state), incorrectTagRev).rejected shouldEqual RevisionNotFound(incorrectTagRev.targetRev, state.rev)
        }
      }

      "create a tag event" in {
        forAll(List(inProjectCurrent, crossProjectCurrent)) { state =>
          eval(Some(state), tagResolver).accepted shouldEqual ResolverTagAdded(
            tagResolver.id,
            project,
            state.value.tpe,
            targetRev = tagResolver.targetRev,
            tag = tagResolver.tag,
            3,
            epoch,
            bob.subject
          )
        }
      }
    }

    "eval a deprecate command" should {

      val deprecateResolver = DeprecateResolver(ipId, project, 2, bob.subject)

      "fail if the resolver doesn't exist" in {
        eval(None, deprecateResolver).rejected shouldEqual ResolverNotFound(
          deprecateResolver.id,
          deprecateResolver.project
        )
      }

      "fail if the provided revision is incorrect" in {
        val incorrectRev = deprecateResolver.copy(rev = 5)
        forAll(List(inProjectCurrent, crossProjectCurrent)) { state =>
          eval(Some(state), incorrectRev).rejected shouldEqual IncorrectRev(incorrectRev.rev, state.rev)
        }
      }

      "fail if the resolver is already deprecated" in {
        forAll(List(inProjectCurrent.copy(deprecated = true), crossProjectCurrent.copy(deprecated = true))) { state =>
          eval(Some(state), deprecateResolver).rejected shouldEqual ResolverIsDeprecated(state.id)
        }
      }

      "deprecate the resolver" in {
        forAll(List(inProjectCurrent, crossProjectCurrent)) { state =>
          eval(Some(state), deprecateResolver).accepted shouldEqual ResolverDeprecated(
            deprecateResolver.id,
            project,
            state.value.tpe,
            3,
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
        1,
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
        1,
        epoch,
        bob.subject
      )

      "give a new in-project resolver state from None" in {
        next(None, inProjectCreated).value shouldEqual ResolverState(
          ipId,
          project,
          inProjectCreated.value,
          inProjectCreated.source,
          Tags.empty,
          1,
          deprecated = false,
          epoch,
          bob.subject,
          epoch,
          bob.subject
        )
      }

      "give a new cross-project resolver state from None" in {
        next(None, crossProjectCreated).value shouldEqual ResolverState(
          cpId,
          project,
          crossProjectCreated.value,
          crossProjectCreated.source,
          Tags.empty,
          1,
          deprecated = false,
          epoch,
          bob.subject,
          epoch,
          bob.subject
        )
      }

      "return None for an existing entity" in {
        forAll(
          (
            List(inProjectCurrent, crossProjectCurrent),
            List(inProjectCreated, crossProjectCreated)
          ).tupled
        ) { case (state, event) =>
          next(Some(state), event) shouldEqual None
        }
      }
    }

    "applying an update event" should {
      val inProjectUpdated = ResolverUpdated(
        ipId,
        project,
        InProjectValue(Priority.unsafe(40)),
        Json.obj("inProject" -> Json.fromString("updated")),
        3,
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
        3,
        epoch,
        bob.subject
      )

      "give a new revision of the in-project resolver state from an existing in-project state" in {
        next(Some(inProjectCurrent), inProjectUpdated).value shouldEqual inProjectCurrent.copy(
          value = inProjectUpdated.value,
          source = inProjectUpdated.source,
          rev = inProjectUpdated.rev,
          updatedAt = inProjectUpdated.instant,
          updatedBy = inProjectUpdated.subject
        )
      }

      "give a new revision of the cross-project resolver state from an existing cross-project state" in {
        next(Some(crossProjectCurrent), crossCrojectUpdated).value shouldEqual crossProjectCurrent.copy(
          value = crossCrojectUpdated.value,
          source = crossCrojectUpdated.source,
          rev = crossCrojectUpdated.rev,
          updatedAt = crossCrojectUpdated.instant,
          updatedBy = crossCrojectUpdated.subject
        )
      }

      "return None for other combinations" in {
        forAll(
          List(
            None                      -> inProjectUpdated,
            None                      -> crossCrojectUpdated,
            Some(inProjectCurrent)    -> crossCrojectUpdated,
            Some(crossProjectCurrent) -> inProjectUpdated
          )
        ) { case (state, event) =>
          next(state, event) shouldEqual None
        }
      }
    }

    "applying a tag event" should {
      val resolverTagAdded =
        ResolverTagAdded(ipId, project, ResolverType.InProject, 1, UserTag.unsafe("tag2"), 3, instant, alice.subject)

      "update the tag list" in {
        forAll(List(inProjectCurrent, crossProjectCurrent)) { state =>
          next(Some(state), resolverTagAdded).value shouldEqual state.copy(
            tags = state.tags + (resolverTagAdded.tag -> resolverTagAdded.targetRev),
            rev = resolverTagAdded.rev,
            updatedAt = resolverTagAdded.instant,
            updatedBy = resolverTagAdded.subject
          )
        }
      }

      "doesn't result in any change on an initial state" in {
        next(None, resolverTagAdded) shouldEqual None
      }

    }

    "applying a deprecate event" should {

      val deprecated = ResolverDeprecated(ipId, project, ResolverType.InProject, 3, instant, alice.subject)

      "mark the current state as deprecated for a resolver" in {
        forAll(List(inProjectCurrent, crossProjectCurrent)) { state =>
          next(Some(state), deprecated).value shouldEqual state.copy(
            deprecated = true,
            rev = deprecated.rev,
            updatedAt = deprecated.instant,
            updatedBy = deprecated.subject
          )
        }
      }

      "doesn't result in any change on an initial state" in {
        next(None, deprecated) shouldEqual None
      }

    }
  }

}
