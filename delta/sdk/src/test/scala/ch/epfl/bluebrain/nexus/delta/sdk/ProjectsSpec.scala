package ch.epfl.bluebrain.nexus.delta.sdk

import java.time.Instant

import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.{schema, xsd}
import ch.epfl.bluebrain.nexus.delta.sdk.Projects.{evaluate, next, FetchOrganization}
import ch.epfl.bluebrain.nexus.delta.sdk.generators.{OrganizationGen, ProjectGen}
import ch.epfl.bluebrain.nexus.delta.sdk.model.Label
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Identity.User
import ch.epfl.bluebrain.nexus.delta.sdk.model.organizations.OrganizationRejection.{OrganizationIsDeprecated, OrganizationNotFound}
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.ProjectCommand._
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.ProjectEvent._
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.ProjectRejection._
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.ProjectState.Initial
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.{ApiMappings, PrefixIri, ProjectRef}
import ch.epfl.bluebrain.nexus.delta.sdk.syntax._
import ch.epfl.bluebrain.nexus.delta.sdk.utils.UUIDF
import ch.epfl.bluebrain.nexus.testkit._
import monix.bio.IO
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
    with TestHelpers
    with CirceLiteral
    with OptionValues {

  "The Projects state machine" when {
    implicit val sc: Scheduler  = Scheduler.global
    val epoch                   = Instant.EPOCH
    val time2                   = Instant.ofEpochMilli(10L)
    val am                      = ApiMappings(Map("xsd" -> xsd.base, "Person" -> schema.Person))
    val base                    = PrefixIri.unsafe(iri"http://example.com/base/")
    val vocab                   = PrefixIri.unsafe(iri"http://example.com/vocab/")
    val org1                    = OrganizationGen.currentState("org", 1L)
    val org2                    = OrganizationGen.currentState("org2", 1L, deprecated = true)
    val current                 = ProjectGen.currentState(
      "org",
      "proj",
      1L,
      orgUuid = org1.uuid,
      description = Some("desc"),
      mappings = am,
      base = base.value,
      vocab = vocab.value
    )
    val label                   = current.label
    val uuid                    = current.uuid
    val orgLabel                = current.organizationLabel
    val orgUuid                 = current.organizationUuid
    val desc                    = current.description
    val desc2                   = Some("desc2")
    val org2Label               = org2.label
    val subject                 = User("myuser", label)
    val orgs: FetchOrganization = {
      case `orgLabel`  => IO.pure(org1.toResource.value.value)
      case `org2Label` => IO.raiseError(WrappedOrganizationRejection(OrganizationIsDeprecated(org2Label)))
      case label       => IO.raiseError(WrappedOrganizationRejection(OrganizationNotFound(label)))
    }
    val ref                     = ProjectRef(orgLabel, label)
    val ref2                    = ProjectRef(org2Label, label)

    implicit val uuidF: UUIDF = UUIDF.fixed(uuid)

    "evaluating an incoming command" should {

      "create a new event" in {
        evaluate(orgs)(
          Initial,
          CreateProject(ref, desc, am, base, vocab, subject)
        ).accepted shouldEqual
          ProjectCreated(label, uuid, orgLabel, orgUuid, 1L, desc, am, base, vocab, epoch, subject)

        evaluate(orgs)(
          current,
          UpdateProject(ref, desc2, ApiMappings.empty, base, vocab, 1L, subject)
        ).accepted shouldEqual
          ProjectUpdated(label, uuid, orgLabel, orgUuid, 2L, desc2, ApiMappings.empty, base, vocab, epoch, subject)

        evaluate(orgs)(current, DeprecateProject(ref, 1L, subject)).accepted shouldEqual
          ProjectDeprecated(label, uuid, orgLabel, orgUuid, 2L, epoch, subject)
      }

      "reject with IncorrectRev" in {
        val list = List(
          current -> UpdateProject(ref, desc, am, base, vocab, 2L, subject),
          current -> DeprecateProject(ref, 2L, subject)
        )
        forAll(list) { case (state, cmd) =>
          evaluate(orgs)(state, cmd).rejectedWith[IncorrectRev]
        }
      }

      "reject with OrganizationIsDeprecated" in {
        val list = List(
          Initial -> CreateProject(ref2, desc, am, base, vocab, subject),
          current -> UpdateProject(ref2, desc, am, base, vocab, 1L, subject),
          current -> DeprecateProject(ref2, 1L, subject)
        )
        forAll(list) { case (state, cmd) =>
          evaluate(orgs)(state, cmd).rejected shouldEqual
            WrappedOrganizationRejection(OrganizationIsDeprecated(ref2.organization))
        }
      }

      "reject with OrganizationNotFound" in {
        val orgNotFound = ProjectRef(label, Label.unsafe("other"))
        val list        = List(
          Initial -> CreateProject(orgNotFound, desc, am, base, vocab, subject),
          current -> UpdateProject(orgNotFound, desc, am, base, vocab, 1L, subject),
          current -> DeprecateProject(orgNotFound, 1L, subject)
        )
        forAll(list) { case (state, cmd) =>
          evaluate(orgs)(state, cmd).rejected shouldEqual
            WrappedOrganizationRejection(OrganizationNotFound(label))
        }
      }

      "reject with ProjectIsDeprecated" in {
        val cur  = current.copy(deprecated = true)
        val list = List(
          cur -> UpdateProject(ref, desc, am, base, vocab, 1L, subject),
          cur -> DeprecateProject(ref, 1L, subject)
        )
        forAll(list) { case (state, cmd) =>
          evaluate(orgs)(state, cmd).rejectedWith[ProjectIsDeprecated]
        }
      }

      "reject with ProjectNotFound" in {
        val list = List(
          Initial -> UpdateProject(ref, desc, am, base, vocab, 1L, subject),
          Initial -> DeprecateProject(ref, 1L, subject)
        )
        forAll(list) { case (state, cmd) =>
          evaluate(orgs)(state, cmd).rejectedWith[ProjectNotFound]
        }
      }

      "reject with ProjectAlreadyExists" in {
        evaluate(orgs)(current, CreateProject(ref, desc, am, base, vocab, subject))
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
        next(Initial, ProjectUpdated(label, uuid, orgLabel, orgUuid, 2L, desc2, ApiMappings.empty, base, vocab, time2, subject)) shouldEqual
          Initial

        next(current, ProjectUpdated(label, uuid, orgLabel, orgUuid, 2L, desc2, ApiMappings.empty, base, vocab, time2, subject)) shouldEqual
          current.copy(rev = 2L, description = desc2, apiMappings = ApiMappings.empty, updatedAt = time2, updatedBy = subject)
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
