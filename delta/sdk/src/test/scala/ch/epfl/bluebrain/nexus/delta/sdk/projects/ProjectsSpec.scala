package ch.epfl.bluebrain.nexus.delta.sdk.projects

import ch.epfl.bluebrain.nexus.delta.kernel.utils.UUIDF
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.{schema, xsd}
import ch.epfl.bluebrain.nexus.delta.sdk.generators.{OrganizationGen, ProjectGen}
import ch.epfl.bluebrain.nexus.delta.sdk.organizations.model.OrganizationRejection.{OrganizationIsDeprecated, OrganizationNotFound}
import ch.epfl.bluebrain.nexus.delta.sdk.projects.Projects.{evaluate, FetchOrganization}
import ch.epfl.bluebrain.nexus.delta.sdk.projects.model.ProjectCommand._
import ch.epfl.bluebrain.nexus.delta.sdk.projects.model.ProjectEvent._
import ch.epfl.bluebrain.nexus.delta.sdk.projects.model.ProjectRejection._
import ch.epfl.bluebrain.nexus.delta.sdk.projects.model.{ApiMappings, PrefixIri}
import ch.epfl.bluebrain.nexus.delta.sdk.syntax._
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.User
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{Label, ProjectRef}
import ch.epfl.bluebrain.nexus.testkit._
import monix.bio.IO
import monix.execution.Scheduler
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike
import org.scalatest.{Inspectors, OptionValues}

import java.time.Instant

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
    val am                      = ApiMappings("xsd" -> xsd.base, "Person" -> schema.Person)
    val base                    = PrefixIri.unsafe(iri"http://example.com/base/")
    val vocab                   = PrefixIri.unsafe(iri"http://example.com/vocab/")
    val org1                    = OrganizationGen.state("org", 1)
    val org2                    = OrganizationGen.state("org2", 1, deprecated = true)
    val state                   = ProjectGen.state(
      "org",
      "proj",
      1,
      orgUuid = org1.uuid,
      description = Some("desc"),
      mappings = am,
      base = base.value,
      vocab = vocab.value
    )
    val label                   = state.label
    val uuid                    = state.uuid
    val orgLabel                = state.organizationLabel
    val orgUuid                 = state.organizationUuid
    val desc                    = state.description
    val desc2                   = Some("desc2")
    val org2abel                = org2.label
    val subject                 = User("myuser", label)
    val orgs: FetchOrganization = {
      case `orgLabel` => IO.pure(org1.toResource.value)
      case `org2abel` => IO.raiseError(WrappedOrganizationRejection(OrganizationIsDeprecated(org2abel)))
      case label      => IO.raiseError(WrappedOrganizationRejection(OrganizationNotFound(label)))
    }
    val ref                     = ProjectRef(orgLabel, label)
    val ref2                    = ProjectRef(org2abel, label)

    implicit val uuidF: UUIDF = UUIDF.fixed(uuid)

    "evaluating an incoming command" should {

      val eval = evaluate(orgs)(_, _)

      "create a new event" in {
        eval(None, CreateProject(ref, desc, am, base, vocab, subject)).accepted shouldEqual
          ProjectCreated(label, uuid, orgLabel, orgUuid, 1, desc, am, base, vocab, epoch, subject)

        eval(Some(state), UpdateProject(ref, desc2, ApiMappings.empty, base, vocab, 1, subject)).accepted shouldEqual
          ProjectUpdated(label, uuid, orgLabel, orgUuid, 2, desc2, ApiMappings.empty, base, vocab, epoch, subject)

        eval(Some(state), DeprecateProject(ref, 1, subject)).accepted shouldEqual
          ProjectDeprecated(label, uuid, orgLabel, orgUuid, 2, epoch, subject)

        eval(Some(state), DeleteProject(ref, 1, subject)).accepted shouldEqual
          ProjectMarkedForDeletion(label, uuid, orgLabel, orgUuid, 2, epoch, subject)
      }

      "reject with IncorrectRev" in {
        val list = List(
          state -> UpdateProject(ref, desc, am, base, vocab, 2, subject),
          state -> DeprecateProject(ref, 2, subject)
        )
        forAll(list) { case (state, cmd) =>
          eval(Some(state), cmd).rejectedWith[IncorrectRev]
        }
      }

      "reject with OrganizationIsDeprecated" in {
        val list = List(
          None        -> CreateProject(ref2, desc, am, base, vocab, subject),
          Some(state) -> UpdateProject(ref2, desc, am, base, vocab, 1, subject),
          Some(state) -> DeprecateProject(ref2, 1, subject)
        )
        forAll(list) { case (state, cmd) =>
          eval(state, cmd).rejected shouldEqual
            WrappedOrganizationRejection(OrganizationIsDeprecated(ref2.organization))
        }
      }

      "reject with OrganizationNotFound" in {
        val orgNotFound = ProjectRef(label, Label.unsafe("other"))
        val list        = List(
          None        -> CreateProject(orgNotFound, desc, am, base, vocab, subject),
          Some(state) -> UpdateProject(orgNotFound, desc, am, base, vocab, 1, subject),
          Some(state) -> DeprecateProject(orgNotFound, 1, subject)
        )
        forAll(list) { case (state, cmd) =>
          eval(state, cmd).rejected shouldEqual
            WrappedOrganizationRejection(OrganizationNotFound(label))
        }
      }

      "reject with ProjectIsDeprecated" in {
        val cur  = state.copy(deprecated = true)
        val list = List(
          cur -> UpdateProject(ref, desc, am, base, vocab, 1, subject),
          cur -> DeprecateProject(ref, 1, subject)
        )
        forAll(list) { case (state, cmd) =>
          eval(Some(state), cmd).rejectedWith[ProjectIsDeprecated]
        }
      }

      "reject with ProjectNotFound" in {
        val list = List(
          None -> UpdateProject(ref, desc, am, base, vocab, 1, subject),
          None -> DeprecateProject(ref, 1, subject)
        )
        forAll(list) { case (state, cmd) =>
          eval(state, cmd).rejectedWith[ProjectNotFound]
        }
      }

      "reject with ProjectAlreadyExists" in {
        eval(Some(state), CreateProject(ref, desc, am, base, vocab, subject))
          .rejectedWith[ProjectAlreadyExists]
      }

      "do not reject with ProjectIsDeprecated" in {
        val cur = state.copy(deprecated = true)
        val cmd = DeleteProject(ref, 1, subject)
        eval(Some(cur), cmd).accepted shouldEqual
          ProjectMarkedForDeletion(label, uuid, orgLabel, orgUuid, 2, epoch, subject)
      }
    }

    "producing next state" should {
      val next = Projects.next _
      val c    = state.copy(apiMappings = state.apiMappings)

      "create a new ProjectCreated state" in {
        next(
          None,
          ProjectCreated(label, uuid, orgLabel, orgUuid, 1, desc, am, base, vocab, time2, subject)
        ).value shouldEqual
          c.copy(createdAt = time2, createdBy = subject, updatedAt = time2, updatedBy = subject)

        next(
          Some(state),
          ProjectCreated(label, uuid, orgLabel, orgUuid, 1, desc, am, base, vocab, time2, subject)
        ) shouldEqual None
      }

      "create a new ProjectUpdated state" in {
        next(
          None,
          ProjectUpdated(label, uuid, orgLabel, orgUuid, 2, desc2, ApiMappings.empty, base, vocab, time2, subject)
        ) shouldEqual None

        next(
          Some(state),
          ProjectUpdated(label, uuid, orgLabel, orgUuid, 2, desc2, ApiMappings.empty, base, vocab, time2, subject)
        ).value shouldEqual
          c.copy(rev = 2, description = desc2, apiMappings = ApiMappings.empty, updatedAt = time2, updatedBy = subject)
      }

      "create new ProjectDeprecated state" in {
        next(
          None,
          ProjectDeprecated(label, uuid, orgLabel, orgUuid, 2, time2, subject)
        ) shouldEqual None

        next(
          Some(state),
          ProjectDeprecated(label, uuid, orgLabel, orgUuid, 2, time2, subject)
        ).value shouldEqual
          c.copy(rev = 2, deprecated = true, updatedAt = time2, updatedBy = subject)
      }

      "create new ProjectMarkedForDeletion state" in {
        next(None, ProjectMarkedForDeletion(label, uuid, orgLabel, orgUuid, 2, time2, subject)) shouldEqual None

        next(Some(state), ProjectMarkedForDeletion(label, uuid, orgLabel, orgUuid, 2, time2, subject)).value shouldEqual
          c.copy(rev = 2, markedForDeletion = true, updatedAt = time2, updatedBy = subject)
      }
    }
  }
}
