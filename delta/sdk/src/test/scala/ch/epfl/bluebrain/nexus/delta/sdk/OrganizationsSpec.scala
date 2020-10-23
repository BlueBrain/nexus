package ch.epfl.bluebrain.nexus.delta.sdk

import java.time.Instant

import ch.epfl.bluebrain.nexus.delta.sdk.Organizations.{evaluate, next}
import ch.epfl.bluebrain.nexus.delta.sdk.generators.OrganizationGen
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Identity.User
import ch.epfl.bluebrain.nexus.delta.sdk.model.organizations.OrganizationCommand._
import ch.epfl.bluebrain.nexus.delta.sdk.model.organizations.OrganizationEvent._
import ch.epfl.bluebrain.nexus.delta.sdk.model.organizations.OrganizationRejection._
import ch.epfl.bluebrain.nexus.delta.sdk.model.organizations.OrganizationState.{Current, Initial}
import ch.epfl.bluebrain.nexus.delta.sdk.utils.UUIDF
import ch.epfl.bluebrain.nexus.testkit.{CirceLiteral, EitherValuable, IOFixedClock, IOValues}
import monix.execution.Scheduler
import org.scalatest.Inspectors
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

class OrganizationsSpec
    extends AnyWordSpecLike
    with Matchers
    with EitherValuable
    with Inspectors
    with IOFixedClock
    with IOValues
    with CirceLiteral {

  "The Organizations state machine" when {
    implicit val sc: Scheduler     = Scheduler.global
    val epoch: Instant             = Instant.EPOCH
    val time2: Instant             = Instant.ofEpochMilli(10L)
    val current: Current           = OrganizationGen.currentState("org", 1L, description = Some("desc"))
    val (label, uuid, desc, desc2) = (current.label, current.uuid, current.description, Some("other"))
    val subject: User              = User("myuser", label)

    implicit val uuidF: UUIDF = UUIDF.fixed(uuid)

    "evaluating an incoming command" should {

      "create a new event" in {
        evaluate(Initial, CreateOrganization(label, desc, subject)).accepted shouldEqual
          OrganizationCreated(label, uuid, 1L, desc, epoch, subject)

        evaluate(current, UpdateOrganization(label, 1L, desc2, subject)).accepted shouldEqual
          OrganizationUpdated(label, uuid, 2L, desc2, epoch, subject)

        evaluate(current, DeprecateOrganization(label, 1L, subject)).accepted shouldEqual
          OrganizationDeprecated(label, uuid, 2L, epoch, subject)
      }

      "reject with IncorrectRev" in {
        val list = List(
          current -> UpdateOrganization(label, 2L, desc2, subject),
          current -> DeprecateOrganization(label, 2L, subject)
        )
        forAll(list) { case (state, cmd) =>
          evaluate(state, cmd).rejectedWith[IncorrectRev]
        }
      }

      "reject with OrganizationAlreadyExists" in {
        evaluate(current, CreateOrganization(label, desc, subject)).rejectedWith[OrganizationAlreadyExists]
      }

      "reject with OrganizationIsDeprecated" in {
        val list = List(
          current.copy(deprecated = true) -> UpdateOrganization(label, 1L, desc2, subject),
          current.copy(deprecated = true) -> DeprecateOrganization(label, 1L, subject)
        )
        forAll(list) { case (state, cmd) =>
          evaluate(state, cmd).rejectedWith[OrganizationIsDeprecated]
        }
      }

      "reject with OrganizationNotFound" in {
        val list = List(
          Initial -> UpdateOrganization(label, 1L, desc2, subject),
          Initial -> DeprecateOrganization(label, 1L, subject)
        )
        forAll(list) { case (state, cmd) =>
          evaluate(state, cmd).rejectedWith[OrganizationNotFound]
        }
      }
    }

    "producing next state" should {

      "create a new OrganizationCreated state" in {
        next(Initial, OrganizationCreated(label, uuid, 1L, desc, time2, subject)) shouldEqual
          current.copy(createdAt = time2, createdBy = subject, updatedAt = time2, updatedBy = subject)

        next(current, OrganizationCreated(label, uuid, 1L, desc, time2, subject)) shouldEqual current
      }

      "create a new OrganizationUpdated state" in {
        next(Initial, OrganizationUpdated(label, uuid, 2L, desc2, time2, subject)) shouldEqual Initial

        next(current, OrganizationUpdated(label, uuid, 2L, desc2, time2, subject)) shouldEqual
          current.copy(rev = 2L, description = desc2, updatedAt = time2, updatedBy = subject)
      }

      "create new RealmDeprecated state" in {
        next(Initial, OrganizationDeprecated(label, uuid, 2L, time2, subject)) shouldEqual Initial

        next(current, OrganizationDeprecated(label, uuid, 2L, time2, subject)) shouldEqual
          current.copy(rev = 2L, deprecated = true, updatedAt = time2, updatedBy = subject)
      }
    }
  }
}
