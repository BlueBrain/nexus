package ch.epfl.bluebrain.nexus.delta.sdk

import java.time.Instant

import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.{schema, xsd}
import ch.epfl.bluebrain.nexus.delta.sdk.Projects.{evaluate, next}
import ch.epfl.bluebrain.nexus.delta.sdk.generators.{OrganizationGen, ProjectGen}
import ch.epfl.bluebrain.nexus.delta.sdk.mocks.OrganizationsMock
import ch.epfl.bluebrain.nexus.delta.sdk.model.Label
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Identity.User
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.PrefixIRI
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.ProjectCommand._
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.ProjectEvent._
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.ProjectRejection._
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.ProjectState.Initial
import ch.epfl.bluebrain.nexus.delta.sdk.syntax._
import ch.epfl.bluebrain.nexus.testkit.{CirceLiteral, EitherValuable, IOFixedClock, IOValues}
import monix.execution.Scheduler
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike
import org.scalatest.{Inspectors, OptionValues}

class ProjectsSpec
    extends AnyWordSpecLike
    with Matchers
    with EitherValuable
    with Inspectors
    with IOFixedClock
    with IOValues
    with CirceLiteral
    with OptionValues {

  "The Projects state machine" when {
    implicit val sc: Scheduler = Scheduler.global
    val epoch                  = Instant.EPOCH
    val time2                  = Instant.ofEpochMilli(10L)
    val am                     = Map("xsd" -> xsd.base, "Person" -> schema.Person)
    val base                   = PrefixIRI.unsafe(iri"http://example.com/base/")
    val vocab                  = PrefixIRI.unsafe(iri"http://example.com/vocab/")
    val org1                   = OrganizationGen.currentState("org", 1L)
    val org2                   = OrganizationGen.currentState("org2", 1L, deprecated = true)
    val current                = ProjectGen.currentState(
      "org",
      "proj",
      1L,
      description = Some("desc"),
      mappings = am,
      base = base.value,
      vocab = vocab.value
    )
    val label                  = current.label
    val uuid                   = current.uuid
    val orgLabel               = current.organizationLabel
    val orgUuid                = current.organizationUuid
    val desc                   = current.description
    val desc2                  = Some("desc2")
    val org2Label              = org2.label
    val subject                = User("myuser", label)
    val orgs                   = new OrganizationsMock(Map(orgLabel -> org1.toResource.value, org2Label -> org2.toResource.value))

    "evaluating an incoming command" should {

      "create a new event" in {
        evaluate(orgs)(
          Initial,
          CreateProject(label, uuid, orgLabel, orgUuid, desc, am, base, vocab, subject)
        ).accepted shouldEqual
          ProjectCreated(label, uuid, orgLabel, orgUuid, 1L, desc, am, base, vocab, epoch, subject)

        evaluate(orgs)(
          current,
          UpdateProject(label, uuid, orgLabel, orgUuid, desc2, Map.empty, base, vocab, 1L, subject)
        ).accepted shouldEqual
          ProjectUpdated(label, uuid, orgLabel, orgUuid, 2L, desc2, Map.empty, base, vocab, epoch, subject)

        evaluate(orgs)(current, DeprecateProject(label, uuid, orgLabel, orgUuid, 1L, subject)).accepted shouldEqual
          ProjectDeprecated(label, uuid, orgLabel, orgUuid, 2L, epoch, subject)
      }

      "reject with IncorrectRev" in {
        val list = List(
          current -> UpdateProject(label, uuid, orgLabel, orgUuid, desc, am, base, vocab, 2L, subject),
          current -> DeprecateProject(label, uuid, orgLabel, orgUuid, 2L, subject)
        )
        forAll(list) { case (state, cmd) =>
          evaluate(orgs)(state, cmd).rejectedWith[IncorrectRev]
        }
      }

      "reject with OrganizationIsDeprecated" in {
        val list = List(
          Initial -> CreateProject(label, uuid, org2Label, orgUuid, desc, am, base, vocab, subject),
          current -> UpdateProject(label, uuid, org2Label, orgUuid, desc, am, base, vocab, 1L, subject),
          current -> DeprecateProject(label, uuid, org2Label, orgUuid, 1L, subject)
        )
        forAll(list) { case (state, cmd) =>
          evaluate(orgs)(state, cmd).rejectedWith[OrganizationIsDeprecated]
        }
      }

      "reject with OrganizationNotFound" in {
        val orgNotFound = Label.unsafe("other")
        val list        = List(
          Initial -> CreateProject(label, uuid, orgNotFound, orgUuid, desc, am, base, vocab, subject),
          current -> UpdateProject(label, uuid, orgNotFound, orgUuid, desc, am, base, vocab, 1L, subject),
          current -> DeprecateProject(label, uuid, orgNotFound, orgUuid, 1L, subject)
        )
        forAll(list) { case (state, cmd) =>
          evaluate(orgs)(state, cmd).rejectedWith[OrganizationNotFound]
        }
      }

      "reject with ProjectIsDeprecated" in {
        val cur  = current.copy(deprecated = true)
        val list = List(
          cur -> UpdateProject(label, uuid, orgLabel, orgUuid, desc, am, base, vocab, 1L, subject),
          cur -> DeprecateProject(label, uuid, orgLabel, orgUuid, 1L, subject)
        )
        forAll(list) { case (state, cmd) =>
          evaluate(orgs)(state, cmd).rejectedWith[ProjectIsDeprecated]
        }
      }

      "reject with ProjectNotFound" in {
        val list = List(
          Initial -> UpdateProject(label, uuid, orgLabel, orgUuid, desc, am, base, vocab, 1L, subject),
          Initial -> DeprecateProject(label, uuid, orgLabel, orgUuid, 1L, subject)
        )
        forAll(list) { case (state, cmd) =>
          evaluate(orgs)(state, cmd).rejectedWith[ProjectNotFound]
        }
      }

      "reject with ProjectAlreadyExists" in {
        evaluate(orgs)(current, CreateProject(label, uuid, orgLabel, orgUuid, desc, am, base, vocab, subject))
          .rejectedWith[ProjectAlreadyExists]
      }

    }

    "producing next state" should {

      "create a new ProjectCreated state" in {
        next(
          Initial,
          ProjectCreated(label, uuid, orgLabel, orgUuid, 1L, desc, am, base, vocab, time2, subject)
        ) shouldEqual
          current.copy(createdAt = time2, createdBy = subject, updatedAt = time2, updatedBy = subject)

        next(
          current,
          ProjectCreated(label, uuid, orgLabel, orgUuid, 1L, desc, am, base, vocab, time2, subject)
        ) shouldEqual current
      }

      "create a new ProjectUpdated state" in {
        // format: off
        next(Initial, ProjectUpdated(label, uuid, orgLabel, orgUuid, 2L, desc2, Map.empty, base, vocab, time2, subject)) shouldEqual
          Initial

        next(current, ProjectUpdated(label, uuid, orgLabel, orgUuid, 2L, desc2, Map.empty, base, vocab, time2, subject)) shouldEqual
          current.copy(rev = 2L, description = desc2, apiMappings = Map.empty, updatedAt = time2, updatedBy = subject)
        // format: on
      }

      "create new ProjectDeprecated state" in {
        next(Initial, ProjectDeprecated(label, uuid, orgLabel, orgUuid, 2L, time2, subject)) shouldEqual Initial

        next(current, ProjectDeprecated(label, uuid, orgLabel, orgUuid, 2L, time2, subject)) shouldEqual
          current.copy(rev = 2L, deprecated = true, updatedAt = time2, updatedBy = subject)
      }
    }
  }
}
