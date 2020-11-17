package ch.epfl.bluebrain.nexus.delta.sdk.testkit

import java.util.UUID

import akka.persistence.query.{NoOffset, Sequence}
import cats.data.NonEmptyList
import cats.implicits._
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.{nxv, schema}
import ch.epfl.bluebrain.nexus.delta.sdk.Resolvers
import ch.epfl.bluebrain.nexus.delta.sdk.generators.{ProjectGen, ResolverGen}
import ch.epfl.bluebrain.nexus.delta.sdk.model.IdSegment.{IriSegment, StringSegment}
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Caller
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Identity.{Authenticated, Group, User}
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.ProjectRejection.{ProjectIsDeprecated, ProjectNotFound}
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.{ApiMappings, ProjectRef}
import ch.epfl.bluebrain.nexus.delta.sdk.model.resolvers.IdentityResolution.{ProvidedIdentities, UseCurrentCaller}
import ch.epfl.bluebrain.nexus.delta.sdk.model.resolvers.ResolverEvent.{ResolverCreated, ResolverDeprecated, ResolverTagAdded, ResolverUpdated}
import ch.epfl.bluebrain.nexus.delta.sdk.model.resolvers.ResolverRejection.{IncorrectRev, InvalidIdentities, NoIdentities, ResolverAlreadyExists, ResolverIsDeprecated, ResolverNotFound, RevisionNotFound, TagNotFound, WrappedProjectRejection, WrappedResourceRejection}
import ch.epfl.bluebrain.nexus.delta.sdk.model.resolvers.ResolverValue.{CrossProjectValue, InProjectValue}
import ch.epfl.bluebrain.nexus.delta.sdk.model.resolvers.{Priority, ResolverFields}
import ch.epfl.bluebrain.nexus.delta.sdk.model.resources.ResourceRejection.{InvalidResourceId, UnexpectedResourceId}
import ch.epfl.bluebrain.nexus.delta.sdk.model.{BaseUri, Label}
import ch.epfl.bluebrain.nexus.delta.sdk.utils.UUIDF
import ch.epfl.bluebrain.nexus.testkit.{IOFixedClock, IOValues, TestHelpers}
import monix.bio.UIO
import monix.execution.Scheduler
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike
import org.scalatest.{Inspectors, OptionValues}

trait ResolversBehaviors {
  this: AnyWordSpecLike
    with Matchers
    with IOValues
    with IOFixedClock
    with TestHelpers
    with OptionValues
    with Inspectors =>

  private val realm                = Label.unsafe("myrealm")
  implicit private val bob: Caller =
    Caller(User("Bob", realm), Set(User("Bob", realm), Group("mygroup", realm), Authenticated(realm)))
  private val alice                = Caller(User("Alice", realm), Set(User("Alice", realm), Group("mygroup2", realm)))

  implicit val scheduler: Scheduler = Scheduler.global
  implicit val baseUri: BaseUri     = BaseUri("http://localhost", Label.unsafe("v1"))

  val uuid                  = UUID.randomUUID()
  implicit val uuidF: UUIDF = UUIDF.fixed(uuid)

  val org               = Label.unsafe("org")
  val apiMappings       = ApiMappings(Map("nxv" -> nxv.base, "Person" -> schema.Person))
  val base              = nxv.base
  val project           = ProjectGen.resourceFor(ProjectGen.project("org", "proj", base = base, mappings = apiMappings))
  val deprecatedProject = ProjectGen.resourceFor(ProjectGen.project("org", "proj-deprecated"))

  val projectRef           = project.value.ref
  val deprecatedProjectRef = deprecatedProject.value.ref
  val unknownProjectRef    = ProjectRef(org, Label.unsafe("xxx"))

  private val priority = Priority.unsafe(42)

  lazy val projects: ProjectsDummy = ProjectSetup
    .init(
      orgsToCreate = org :: Nil,
      projectsToCreate = project.value :: deprecatedProject.value :: Nil,
      projectsToDeprecate = deprecatedProject.value.ref :: Nil
    )
    .map(_._2)
    .accepted

  def create: UIO[Resolvers]

  lazy val resolvers: Resolvers = create.accepted

  "The Resolvers module" when {

    val inProjectValue = InProjectValue(priority)

    val updatedInProjectValue = InProjectValue(Priority.unsafe(99))

    val crossProjectValue = CrossProjectValue(
      priority,
      Set.empty,
      NonEmptyList.of(
        ProjectRef.unsafe("org", "proj")
      ),
      ProvidedIdentities(bob.identities)
    )

    val updatedCrossProjectValue = crossProjectValue.copy(identityResolution = UseCurrentCaller)

    "creating a resolver" should {

      "succeed with the id only defined as a segment" in {
        forAll(
          List(
            nxv + "in-project"    -> inProjectValue,
            nxv + "cross-project" -> crossProjectValue
          )
        ) { case (id, value) =>
          resolvers.create(IriSegment(id), projectRef, ResolverFields(None, value)).accepted shouldEqual ResolverGen
            .resourceFor(id, project.value, value, subject = bob.subject)
        }
      }

      "succeed with the id only defined in the payload" in {
        forAll(
          List(
            nxv + "in-project-payload"    -> inProjectValue,
            nxv + "cross-project-payload" -> crossProjectValue
          )
        ) { case (id, value) =>
          resolvers.create(projectRef, ResolverFields(Some(id), value)).accepted shouldEqual ResolverGen.resourceFor(
            id,
            project.value,
            value,
            subject = bob.subject
          )
        }
      }

      "succeed with the same id only defined in both segment and payload" in {
        forAll(
          List(
            nxv + "in-project-both"    -> inProjectValue,
            nxv + "cross-project-both" -> crossProjectValue
          )
        ) { case (id, value) =>
          resolvers.create(IriSegment(id), projectRef, ResolverFields(Some(id), value)).accepted shouldEqual ResolverGen
            .resourceFor(id, project.value, value, subject = bob.subject)
        }
      }

      "succeed with a generated id" in {
        val expectedId = nxv.base / uuid.toString
        resolvers.create(projectRef, ResolverFields(None, crossProjectValue)).accepted shouldEqual ResolverGen
          .resourceFor(expectedId, project.value, crossProjectValue, subject = bob.subject)
      }

      "fail with different ids defined in segment and payload" in {
        forAll(
          List(
            nxv + "in-project"    -> inProjectValue,
            nxv + "cross-project" -> crossProjectValue
          )
        ) { case (id, value) =>
          val payloadId = nxv + "resolver-fail"
          resolvers
            .create(IriSegment(id), projectRef, ResolverFields(Some(payloadId), value))
            .rejected shouldEqual WrappedResourceRejection(
            UnexpectedResourceId(id = id, payloadId = payloadId)
          )
        }
      }

      "fail with ids are not valid" in {
        forAll(
          List(
            "{a@*"  -> inProjectValue,
            "%&jl>" -> crossProjectValue
          )
        ) { case (id, value) =>
          resolvers
            .create(StringSegment(id), projectRef, ResolverFields(None, value))
            .rejected shouldEqual WrappedResourceRejection(
            InvalidResourceId(id = id)
          )
        }
      }

      "fail it already exists" in {
        forAll(
          (List(nxv + "in-project"), List(inProjectValue, crossProjectValue)).tupled
        ) { case (id, value) =>
          resolvers
            .create(StringSegment(id.toString), projectRef, ResolverFields(None, value))
            .rejected shouldEqual ResolverAlreadyExists(id, projectRef)

          resolvers.create(projectRef, ResolverFields(Some(id), value)).rejected shouldEqual ResolverAlreadyExists(
            id,
            projectRef
          )
        }
      }

      "fail the project does not exist" in {
        forAll(
          List(
            nxv + "in-project"    -> inProjectValue,
            nxv + "cross-project" -> crossProjectValue
          )
        ) { case (id, value) =>
          resolvers
            .create(IriSegment(id), unknownProjectRef, ResolverFields(None, value))
            .rejected shouldEqual WrappedProjectRejection(ProjectNotFound(unknownProjectRef))

          resolvers
            .create(unknownProjectRef, ResolverFields(Some(id), value))
            .rejected shouldEqual WrappedProjectRejection(ProjectNotFound(unknownProjectRef))
        }
      }

      "fail the project is deprecated" in {
        forAll(
          List(
            nxv + "in-project"    -> inProjectValue,
            nxv + "cross-project" -> crossProjectValue
          )
        ) { case (id, value) =>
          resolvers
            .create(IriSegment(id), deprecatedProjectRef, ResolverFields(None, value))
            .rejected shouldEqual WrappedProjectRejection(ProjectIsDeprecated(deprecatedProjectRef))

          resolvers
            .create(deprecatedProjectRef, ResolverFields(Some(id), value))
            .rejected shouldEqual WrappedProjectRejection(ProjectIsDeprecated(deprecatedProjectRef))
        }
      }

      "fail if no identities are provided for a cross-project resolver" in {
        val invalidValue = crossProjectValue.copy(identityResolution = ProvidedIdentities(Set.empty))
        resolvers
          .create(IriSegment(nxv + "cross-project-no-id"), projectRef, ResolverFields(None, invalidValue))
          .rejected shouldEqual NoIdentities
      }

      "fail if some provided identities don't belong to the caller for a cross-project resolver" in {
        val invalidValue =
          crossProjectValue.copy(identityResolution = ProvidedIdentities(Set(bob.subject, alice.subject)))
        resolvers
          .create(IriSegment(nxv + "cross-project-miss-id"), projectRef, ResolverFields(None, invalidValue))
          .rejected shouldEqual InvalidIdentities(Set(alice.subject))
      }
    }

    "updating a resolver" should {
      "succeed with the id only defined as a segment" in {
        forAll(
          List(
            nxv + "in-project"    -> updatedInProjectValue,
            nxv + "cross-project" -> updatedCrossProjectValue
          )
        ) { case (id, value) =>
          resolvers.update(IriSegment(id), projectRef, 1L, ResolverFields(None, value)).accepted shouldEqual ResolverGen
            .resourceFor(id, project.value, value, rev = 2L, subject = bob.subject)
        }
      }

      "fail if it doesn't exist" in {
        forAll(
          List(
            nxv + "in-project-xxx"    -> inProjectValue,
            nxv + "cross-project-xxx" -> crossProjectValue
          )
        ) { case (id, value) =>
          resolvers
            .update(IriSegment(id), projectRef, 1L, ResolverFields(None, value))
            .rejected shouldEqual ResolverNotFound(id, projectRef)
        }
      }

      "fail if the provided revision does not match" in {
        forAll(
          List(
            nxv + "in-project"    -> inProjectValue,
            nxv + "cross-project" -> crossProjectValue
          )
        ) { case (id, value) =>
          resolvers
            .update(IriSegment(id), projectRef, 5L, ResolverFields(None, value))
            .rejected shouldEqual IncorrectRev(5L, 2L)
        }
      }

      "fail with different ids defined in segment and payload" in {
        forAll(
          List(
            nxv + "in-project"    -> inProjectValue,
            nxv + "cross-project" -> crossProjectValue
          )
        ) { case (id, value) =>
          val payloadId = nxv + "resolver-fail"
          resolvers
            .update(IriSegment(id), projectRef, 2L, ResolverFields(Some(payloadId), value))
            .rejected shouldEqual WrappedResourceRejection(
            UnexpectedResourceId(id = id, payloadId = payloadId)
          )
        }
      }

      "fail the project does not exist" in {
        forAll(
          List(
            nxv + "in-project"    -> inProjectValue,
            nxv + "cross-project" -> crossProjectValue
          )
        ) { case (id, value) =>
          resolvers
            .update(IriSegment(id), unknownProjectRef, 2L, ResolverFields(None, value))
            .rejected shouldEqual WrappedProjectRejection(ProjectNotFound(unknownProjectRef))
        }
      }

      "fail the project is deprecated" in {
        forAll(
          List(
            nxv + "in-project"    -> inProjectValue,
            nxv + "cross-project" -> crossProjectValue
          )
        ) { case (id, value) =>
          resolvers
            .update(IriSegment(id), deprecatedProjectRef, 2L, ResolverFields(None, value))
            .rejected shouldEqual WrappedProjectRejection(ProjectIsDeprecated(deprecatedProjectRef))
        }
      }

      "fail if no identities are provided for a cross-project resolver" in {
        val invalidValue = crossProjectValue.copy(identityResolution = ProvidedIdentities(Set.empty))
        resolvers
          .update(IriSegment(nxv + "cross-project"), projectRef, 2L, ResolverFields(None, invalidValue))
          .rejected shouldEqual NoIdentities
      }

      "fail if some provided identities don't belong to the caller for a cross-project resolver" in {
        val invalidValue =
          crossProjectValue.copy(identityResolution = ProvidedIdentities(Set(bob.subject, alice.subject)))
        resolvers
          .update(IriSegment(nxv + "cross-project"), projectRef, 2L, ResolverFields(None, invalidValue))
          .rejected shouldEqual InvalidIdentities(Set(alice.subject))
      }
    }

    val tag = Label.unsafe("my-tag")

    "tagging a resolver" should {
      "succeed" in {
        forAll(
          List(
            nxv + "in-project"    -> updatedInProjectValue,
            nxv + "cross-project" -> updatedCrossProjectValue
          )
        ) { case (id, value) =>
          resolvers.tag(IriSegment(id), projectRef, tag, 1L, 2L).accepted shouldEqual ResolverGen.resourceFor(
            id,
            project.value,
            value,
            tags = Map(tag -> 1L),
            rev = 3L,
            subject = bob.subject
          )
        }
      }

      "fail if it doesn't exist" in {
        forAll(
          List(
            nxv + "in-project-xxx",
            nxv + "cross-project-xxx"
          )
        ) { id =>
          resolvers.tag(IriSegment(id), projectRef, tag, 1L, 2L).rejected shouldEqual ResolverNotFound(id, projectRef)
        }
      }

      "fail if the provided revision does not match" in {
        forAll(
          List(
            nxv + "in-project",
            nxv + "cross-project"
          )
        ) { id =>
          resolvers.tag(IriSegment(id), projectRef, tag, 1L, 21L).rejected shouldEqual IncorrectRev(21L, 3L)
        }
      }

      "fail if the tag revision is invalid" in {
        forAll(
          List(
            nxv + "in-project",
            nxv + "cross-project"
          )
        ) { id =>
          resolvers.tag(IriSegment(id), projectRef, tag, 20L, 3L).rejected shouldEqual RevisionNotFound(20L, 3L)
        }
      }

      "fail the project does not exist" in {
        forAll(
          List(
            nxv + "in-project",
            nxv + "cross-project"
          )
        ) { id =>
          resolvers.tag(IriSegment(id), unknownProjectRef, tag, 1L, 3L).rejected shouldEqual WrappedProjectRejection(
            ProjectNotFound(unknownProjectRef)
          )
        }
      }

      "fail the project is deprecated" in {
        forAll(
          List(
            nxv + "in-project",
            nxv + "cross-project"
          )
        ) { id =>
          resolvers.tag(IriSegment(id), deprecatedProjectRef, tag, 1L, 3L).rejected shouldEqual WrappedProjectRejection(
            ProjectIsDeprecated(deprecatedProjectRef)
          )
        }
      }
    }

    "deprecating a resolver" should {
      "succeed" in {
        forAll(
          List(
            nxv + "in-project"    -> updatedInProjectValue,
            nxv + "cross-project" -> updatedCrossProjectValue
          )
        ) { case (id, value) =>
          resolvers.deprecate(IriSegment(id), projectRef, 3L).accepted shouldEqual ResolverGen.resourceFor(
            id,
            project.value,
            value,
            tags = Map(tag -> 1L),
            rev = 4L,
            subject = bob.subject,
            deprecated = true
          )
        }
      }

      "fail if it doesn't exist" in {
        forAll(
          List(
            nxv + "in-project-xxx",
            nxv + "cross-project-xxx"
          )
        ) { id =>
          resolvers.deprecate(IriSegment(id), projectRef, 3L).rejected shouldEqual ResolverNotFound(id, projectRef)
        }
      }

      "fail if the provided revision does not match" in {
        forAll(
          List(
            nxv + "in-project",
            nxv + "cross-project"
          )
        ) { id =>
          resolvers.deprecate(IriSegment(id), projectRef, 3L).rejected shouldEqual IncorrectRev(3, 4L)
        }
      }

      "fail the project does not exist" in {
        forAll(
          List(
            nxv + "in-project",
            nxv + "cross-project"
          )
        ) { id =>
          resolvers.deprecate(IriSegment(id), unknownProjectRef, 3L).rejected shouldEqual WrappedProjectRejection(
            ProjectNotFound(unknownProjectRef)
          )
        }
      }

      "fail the project is deprecated" in {
        forAll(
          List(
            nxv + "in-project",
            nxv + "cross-project"
          )
        ) { id =>
          resolvers.deprecate(IriSegment(id), deprecatedProjectRef, 3L).rejected shouldEqual WrappedProjectRejection(
            ProjectIsDeprecated(deprecatedProjectRef)
          )
        }
      }

      "fail is we try to deprecate it again" in {
        forAll(
          List(
            nxv + "in-project",
            nxv + "cross-project"
          )
        ) { id =>
          resolvers.deprecate(IriSegment(id), projectRef, 4L).rejected shouldEqual ResolverIsDeprecated(id)
        }
      }

      "fail is we try to update it" in {
        forAll(
          List(
            nxv + "in-project"    -> inProjectValue,
            nxv + "cross-project" -> crossProjectValue
          )
        ) { case (id, value) =>
          resolvers
            .update(IriSegment(id), projectRef, 4L, ResolverFields(None, value))
            .rejected shouldEqual ResolverIsDeprecated(id)
        }
      }

      "fail is we try to tag it" in {
        forAll(
          List(
            nxv + "in-project",
            nxv + "cross-project"
          )
        ) { id =>
          resolvers.tag(IriSegment(id), projectRef, tag, 3L, 4L).rejected shouldEqual ResolverIsDeprecated(id)
        }
      }
    }

    "fetching a resolver" should {
      val inProjectExpected    = ResolverGen.resourceFor(
        nxv + "in-project",
        project.value,
        updatedInProjectValue,
        tags = Map(tag -> 1L),
        rev = 4L,
        subject = bob.subject,
        deprecated = true
      )
      val crossProjectExpected = ResolverGen.resourceFor(
        nxv + "cross-project",
        project.value,
        updatedCrossProjectValue,
        tags = Map(tag -> 1L),
        rev = 4L,
        subject = bob.subject,
        deprecated = true
      )

      "succeed" in {
        forAll(List(inProjectExpected, crossProjectExpected)) { resource =>
          resolvers.fetch(StringSegment(resource.value.id.toString), projectRef).accepted.value shouldEqual resource
        }
      }

      "succeed by rev" in {
        forAll(List(inProjectExpected, crossProjectExpected)) { resource =>
          resolvers.fetchAt(IriSegment(resource.value.id), projectRef, 3L).accepted.value shouldEqual resource.copy(
            rev = 3L,
            deprecated = false
          )
        }
      }

      "succeed by tag" in {
        forAll(
          List(
            nxv + "in-project"    -> inProjectValue,
            nxv + "cross-project" -> crossProjectValue
          )
        ) { case (id, value) =>
          resolvers.fetchBy(IriSegment(id), projectRef, tag).accepted.value shouldEqual ResolverGen.resourceFor(
            id,
            project.value,
            value,
            subject = bob.subject
          )
        }
      }

      "return none if resolver does not exist" in {
        resolvers.fetch(StringSegment("xxx"), projectRef).accepted shouldEqual None
      }

      "fail if revision does not exist" in {
        resolvers.fetchAt(IriSegment(nxv + "in-project"), projectRef, 30L).rejected shouldEqual RevisionNotFound(
          30L,
          4L
        )
      }

      "fail if tag does not exist" in {
        val unknownTag = Label.unsafe("xxx")
        resolvers.fetchBy(IriSegment(nxv + "in-project"), projectRef, unknownTag).rejected shouldEqual TagNotFound(
          unknownTag
        )
      }
    }

    "getting events" should {
      val allEvents = SSEUtils.list(
        nxv + "in-project"            -> ResolverCreated,
        nxv + "cross-project"         -> ResolverCreated,
        nxv + "in-project-payload"    -> ResolverCreated,
        nxv + "cross-project-payload" -> ResolverCreated,
        nxv + "in-project-both"       -> ResolverCreated,
        nxv + "cross-project-both"    -> ResolverCreated,
        nxv + uuid.toString           -> ResolverCreated,
        nxv + "in-project"            -> ResolverUpdated,
        nxv + "cross-project"         -> ResolverUpdated,
        nxv + "in-project"            -> ResolverTagAdded,
        nxv + "cross-project"         -> ResolverTagAdded,
        nxv + "in-project"            -> ResolverDeprecated,
        nxv + "cross-project"         -> ResolverDeprecated
      )

      "get all events" in {
        val events = resolvers
          .events(NoOffset)
          .map { e => (e.event.id, e.eventType, e.offset) }
          .take(13L)
          .compile
          .toList
          .accepted
        events shouldEqual allEvents
      }

      "get events from offset 2" in {
        val events = resolvers
          .events(Sequence(2L))
          .map { e => (e.event.id, e.eventType, e.offset) }
          .take(11L)
          .compile
          .toList
          .accepted
        events shouldEqual allEvents.drop(2)
      }
    }

  }

}
