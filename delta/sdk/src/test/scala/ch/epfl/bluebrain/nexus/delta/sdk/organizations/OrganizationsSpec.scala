package ch.epfl.bluebrain.nexus.delta.sdk.organizations

import ch.epfl.bluebrain.nexus.delta.kernel.utils.UUIDF
import ch.epfl.bluebrain.nexus.delta.sdk.generators.OrganizationGen
import ch.epfl.bluebrain.nexus.delta.sdk.organizations.model.OrganizationCommand._
import ch.epfl.bluebrain.nexus.delta.sdk.organizations.model.OrganizationEvent._
import ch.epfl.bluebrain.nexus.delta.sdk.organizations.model.OrganizationRejection._
import ch.epfl.bluebrain.nexus.delta.sdk.organizations.Organizations.{evaluate, next}
import ch.epfl.bluebrain.nexus.delta.sdk.organizations.model.OrganizationState
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.User
import ch.epfl.bluebrain.nexus.testkit.{CirceLiteral, EitherValuable, IOFixedClock, IOValues}
import monix.execution.Scheduler
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike
import org.scalatest.{Inspectors, OptionValues}

import java.time.Instant

class OrganizationsSpec
    extends AnyWordSpecLike
    with Matchers
    with EitherValuable
    with OptionValues
    with Inspectors
    with IOFixedClock
    with IOValues
    with CirceLiteral {

  "The Organizations state machine" when {
    implicit val sc: Scheduler     = Scheduler.global
    val epoch: Instant             = Instant.EPOCH
    val time2: Instant             = Instant.ofEpochMilli(10L)
    val state: OrganizationState   = OrganizationGen.state("org", 1, description = Some("desc"))
    val (label, uuid, desc, desc2) = (state.label, state.uuid, state.description, Some("other"))
    val subject: User              = User("myuser", label)

    implicit val uuidF: UUIDF = UUIDF.fixed(uuid)

    "evaluating an incoming command" should {

      "create a new event" in {
        evaluate(None, CreateOrganization(label, desc, subject)).accepted shouldEqual
          OrganizationCreated(label, uuid, 1, desc, epoch, subject)

        evaluate(Some(state), UpdateOrganization(label, 1, desc2, subject)).accepted shouldEqual
          OrganizationUpdated(label, uuid, 2, desc2, epoch, subject)

        evaluate(Some(state), DeprecateOrganization(label, 1, subject)).accepted shouldEqual
          OrganizationDeprecated(label, uuid, 2, epoch, subject)
      }

      "reject with IncorrectRev" in {
        val list = List(
          state -> UpdateOrganization(label, 2, desc2, subject),
          state -> DeprecateOrganization(label, 2, subject)
        )
        forAll(list) { case (state, cmd) =>
          evaluate(Some(state), cmd).rejectedWith[IncorrectRev]
        }
      }

      "reject with OrganizationAlreadyExists" in {
        evaluate(Some(state), CreateOrganization(label, desc, subject)).rejectedWith[OrganizationAlreadyExists]
      }

      "reject with OrganizationIsDeprecated" in {
        val list = List(
          state.copy(deprecated = true) -> UpdateOrganization(label, 1, desc2, subject),
          state.copy(deprecated = true) -> DeprecateOrganization(label, 1, subject)
        )
        forAll(list) { case (state, cmd) =>
          evaluate(Some(state), cmd).rejectedWith[OrganizationIsDeprecated]
        }
      }

      "reject with OrganizationNotFound" in {
        val list = List(
          None -> UpdateOrganization(label, 1, desc2, subject),
          None -> DeprecateOrganization(label, 1, subject)
        )
        forAll(list) { case (state, cmd) =>
          evaluate(state, cmd).rejectedWith[OrganizationNotFound]
        }
      }
    }

    "producing next state" should {

      "create a new OrganizationCreated state" in {
        next(None, OrganizationCreated(label, uuid, 1, desc, time2, subject)).value shouldEqual
          state.copy(createdAt = time2, createdBy = subject, updatedAt = time2, updatedBy = subject)

        next(Some(state), OrganizationCreated(label, uuid, 1, desc, time2, subject)) shouldEqual None
      }

      "create a new OrganizationUpdated state" in {
        next(None, OrganizationUpdated(label, uuid, 2, desc2, time2, subject)) shouldEqual None

        next(Some(state), OrganizationUpdated(label, uuid, 2, desc2, time2, subject)).value shouldEqual
          state.copy(rev = 2, description = desc2, updatedAt = time2, updatedBy = subject)
      }

      "create new OrganizationDeprecated state" in {
        next(None, OrganizationDeprecated(label, uuid, 2, time2, subject)) shouldEqual None

        next(Some(state), OrganizationDeprecated(label, uuid, 2, time2, subject)).value shouldEqual
          state.copy(rev = 2, deprecated = true, updatedAt = time2, updatedBy = subject)
      }
    }
  }
}
