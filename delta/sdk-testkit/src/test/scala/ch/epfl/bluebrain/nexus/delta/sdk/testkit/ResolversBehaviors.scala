package ch.epfl.bluebrain.nexus.delta.sdk.testkit

import akka.persistence.query.{NoOffset, Sequence}
import cats.implicits._
import ch.epfl.bluebrain.nexus.delta.kernel.utils.UUIDF
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.{contexts, nxv, schema}
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.api.{JsonLdApi, JsonLdJavaApi}
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.RemoteContextResolution
import ch.epfl.bluebrain.nexus.delta.sdk.Resolvers
import ch.epfl.bluebrain.nexus.delta.sdk.generators.ProjectGen
import ch.epfl.bluebrain.nexus.delta.sdk.generators.ResolverGen._
import ch.epfl.bluebrain.nexus.delta.sdk.model._
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Caller
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.{Authenticated, Group, User}
import ch.epfl.bluebrain.nexus.delta.sdk.organizations.model.OrganizationRejection.{OrganizationIsDeprecated, OrganizationNotFound}
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.ProjectRejection.{ProjectIsDeprecated, ProjectNotFound}
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.ApiMappings
import ch.epfl.bluebrain.nexus.delta.sdk.model.resolvers.IdentityResolution.{ProvidedIdentities, UseCurrentCaller}
import ch.epfl.bluebrain.nexus.delta.sdk.model.resolvers.ResolverEvent.{ResolverCreated, ResolverDeprecated, ResolverTagAdded, ResolverUpdated}
import ch.epfl.bluebrain.nexus.delta.sdk.model.resolvers.ResolverRejection.{DecodingFailed, IncorrectRev, InvalidIdentities, InvalidResolverId, NoIdentities, PriorityAlreadyExists, ResolverIsDeprecated, ResolverNotFound, ResourceAlreadyExists, RevisionNotFound, TagNotFound, UnexpectedResolverId, WrappedOrganizationRejection, WrappedProjectRejection}
import ch.epfl.bluebrain.nexus.delta.sdk.model.resolvers.ResolverValue.{CrossProjectValue, InProjectValue}
import ch.epfl.bluebrain.nexus.delta.sdk.model.resolvers._
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.Pagination.FromPagination
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.SearchParams.ResolverSearchParams
import ch.epfl.bluebrain.nexus.delta.sdk.implicits._
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Label
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Tag.UserTag
import ch.epfl.bluebrain.nexus.delta.sourcing.model.ProjectRef
import ch.epfl.bluebrain.nexus.testkit.{IOFixedClock, IOValues, TestHelpers}
import monix.bio.{IO, Task}
import monix.execution.Scheduler
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike
import org.scalatest.{CancelAfterFailure, Inspectors, OptionValues}

import java.util.UUID

trait ResolversBehaviors {
  this: AnyWordSpecLike
    with Matchers
    with IOValues
    with IOFixedClock
    with TestHelpers
    with OptionValues
    with Inspectors
    with CancelAfterFailure =>

  private val realm                = Label.unsafe("myrealm")
  implicit private val bob: Caller =
    Caller(User("Bob", realm), Set(User("Bob", realm), Group("mygroup", realm), Authenticated(realm)))
  private val alice                = Caller(User("Alice", realm), Set(User("Alice", realm), Group("mygroup2", realm)))

  implicit val scheduler: Scheduler = Scheduler.global
  implicit val baseUri: BaseUri     = BaseUri("http://localhost", Label.unsafe("v1"))

  val uuid                  = UUID.randomUUID()
  implicit val uuidF: UUIDF = UUIDF.fixed(uuid)

  implicit val api: JsonLdApi = JsonLdJavaApi.strict

  def res: RemoteContextResolution =
    RemoteContextResolution.fixed(
      contexts.resolvers         -> jsonContentOf("/contexts/resolvers.json").topContextValueOrEmpty,
      contexts.resolversMetadata -> jsonContentOf("/contexts/resolvers-metadata.json").topContextValueOrEmpty
    )

  val resolverContextResolution: ResolverContextResolution = new ResolverContextResolution(
    res,
    (_, _, _) => IO.raiseError(ResourceResolutionReport())
  )

  val org                      = Label.unsafe("org")
  val orgDeprecated            = Label.unsafe("org-deprecated")
  val apiMappings              = ApiMappings("nxv" -> nxv.base, "Person" -> schema.Person)
  val base                     = nxv.base
  val project                  = ProjectGen.project("org", "proj", base = base, mappings = apiMappings)
  val deprecatedProject        = ProjectGen.project("org", "proj-deprecated")
  val projectWithDeprecatedOrg = ProjectGen.project("org-deprecated", "other-proj")

  val projectRef           = project.ref
  val deprecatedProjectRef = deprecatedProject.ref
  val unknownProjectRef    = ProjectRef(org, Label.unsafe("xxx"))

  private val inProjectPrio    = Priority.unsafe(42)
  private val crossProjectPrio = Priority.unsafe(43)

  lazy val (orgs, projects) = ProjectSetup
    .init(
      orgsToCreate = org :: orgDeprecated :: Nil,
      projectsToCreate = project :: deprecatedProject :: projectWithDeprecatedOrg :: Nil,
      projectsToDeprecate = deprecatedProject.ref :: Nil,
      organizationsToDeprecate = orgDeprecated :: Nil
    )
    .accepted

  def create: Task[Resolvers]

  lazy val resolvers: Resolvers = create.accepted

  lazy val finder = Resolvers.projectReferenceFinder(resolvers)

  val referencedProject = ProjectRef.unsafe("org", "proj2")

  "The Resolvers module" when {

    val inProjectValue = InProjectValue(inProjectPrio)

    val updatedInProjectValue = InProjectValue(Priority.unsafe(99))

    val crossProjectValue = CrossProjectValue(
      crossProjectPrio,
      Set.empty,
      NonEmptyList.of(referencedProject),
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
          val payload = sourceWithoutId(value)
          resolvers
            .create(id, projectRef, payload)
            .accepted shouldEqual resolverResourceFor(id, project, value, payload, subject = bob.subject)
        }
      }

      "succeed with the id only defined in the payload" in {
        forAll(
          List(
            nxv + "in-project-payload"    -> inProjectValue.copy(priority = Priority.unsafe(44)),
            nxv + "cross-project-payload" -> crossProjectValue.copy(priority = Priority.unsafe(45))
          )
        ) { case (id, value) =>
          val payload = sourceFrom(id, value)
          resolvers.create(projectRef, payload).accepted shouldEqual resolverResourceFor(
            id,
            project,
            value,
            payload,
            subject = bob.subject
          )
        }
      }

      "succeed with the same id when defined in both segment and payload" in {
        forAll(
          List(
            nxv + "in-project-both"    -> inProjectValue.copy(priority = Priority.unsafe(46)),
            nxv + "cross-project-both" -> crossProjectValue.copy(
              identityResolution = UseCurrentCaller,
              priority = Priority.unsafe(47)
            )
          )
        ) { case (id, value) =>
          val payload = sourceFrom(id, value)
          resolvers
            .create(id, projectRef, payload)(alice)
            .accepted shouldEqual resolverResourceFor(
            id,
            project,
            value,
            payload,
            subject = alice.subject
          )
        }
      }

      "succeed with a generated id and with resourceTypes extracted from source" in {
        val expectedId    = nxv.base / uuid.toString
        val expectedValue = crossProjectValue.copy(resourceTypes = Set(nxv.Schema), priority = Priority.unsafe(48))
        val payload       = sourceWithoutId(expectedValue)
        resolvers.create(projectRef, payload).accepted shouldEqual resolverResourceFor(
          expectedId,
          project,
          expectedValue,
          payload,
          subject = bob.subject
        )
      }

      "succeed with a parsed value" in {
        forAll(
          List(
            nxv + "in-project-from-value"    -> inProjectValue.copy(priority = Priority.unsafe(49)),
            nxv + "cross-project-from-value" -> crossProjectValue.copy(priority = Priority.unsafe(50))
          )
        ) { case (id, value) =>
          resolvers.create(id, projectRef, value).accepted shouldEqual resolverResourceFor(
            id,
            project,
            value,
            ResolverValue.generateSource(id, value),
            subject = bob.subject
          )
        }
      }

      "fail if ids defined in segment and payload are different" in {
        forAll(
          List(
            nxv + "in-project"    -> inProjectValue,
            nxv + "cross-project" -> crossProjectValue
          )
        ) { case (id, value) =>
          val payloadId = nxv + "resolver-fail"
          val payload   = sourceFrom(payloadId, value)
          resolvers
            .create(id, projectRef, payload)
            .rejected shouldEqual UnexpectedResolverId(id, payloadId)
        }
      }

      "fail if ids are not valid" in {
        forAll(
          List(
            "{a@*"  -> inProjectValue,
            "%&jl>" -> crossProjectValue
          )
        ) { case (id, value) =>
          val payload = sourceWithoutId(value)
          resolvers.create(id, projectRef, payload).rejected shouldEqual InvalidResolverId(id)
        }
      }

      "fail if priority already exists" in {
        resolvers
          .create(nxv + "in-project-other", projectRef, inProjectValue)
          .rejected shouldEqual PriorityAlreadyExists(projectRef, nxv + "in-project", inProjectValue.priority)
      }

      "fail if it already exists" in {
        val newPrio = Priority.unsafe(51)
        forAll(
          (
            List(nxv + "in-project"),
            List(inProjectValue.copy(priority = newPrio), crossProjectValue.copy(priority = newPrio))
          ).tupled
        ) { case (id, value) =>
          val payload = sourceWithoutId(value)
          resolvers
            .create(id.toString, projectRef, payload)
            .rejected shouldEqual ResourceAlreadyExists(id, projectRef)

          val payloadWithId = sourceFrom(id, value)
          resolvers
            .create(projectRef, payloadWithId)
            .rejected shouldEqual ResourceAlreadyExists(
            id,
            projectRef
          )
        }
      }

      "fail if the project does not exist" in {
        forAll(
          List(
            nxv + "in-project"    -> inProjectValue,
            nxv + "cross-project" -> crossProjectValue
          )
        ) { case (id, value) =>
          val payload = sourceWithoutId(value)
          resolvers
            .create(id, unknownProjectRef, payload)
            .rejected shouldEqual WrappedProjectRejection(ProjectNotFound(unknownProjectRef))

          val payloadWithId = sourceFrom(id, value)
          resolvers
            .create(unknownProjectRef, payloadWithId)
            .rejected shouldEqual WrappedProjectRejection(ProjectNotFound(unknownProjectRef))
        }
      }

      "fail if the project is deprecated" in {
        forAll(
          List(
            nxv + "in-project"    -> inProjectValue,
            nxv + "cross-project" -> crossProjectValue
          )
        ) { case (id, value) =>
          val payload = sourceWithoutId(value)
          resolvers
            .create(id, deprecatedProjectRef, payload)
            .rejected shouldEqual WrappedProjectRejection(ProjectIsDeprecated(deprecatedProjectRef))

          val payloadWithId = sourceFrom(id, value)
          resolvers
            .create(deprecatedProjectRef, payloadWithId)
            .rejected shouldEqual WrappedProjectRejection(ProjectIsDeprecated(deprecatedProjectRef))
        }
      }

      "fail if the org is deprecated" in {
        forAll(
          List(
            nxv + "in-project"    -> inProjectValue,
            nxv + "cross-project" -> crossProjectValue
          )
        ) { case (id, value) =>
          val payload = sourceWithoutId(value)
          resolvers
            .create(id, projectWithDeprecatedOrg.ref, payload)
            .rejected shouldEqual WrappedOrganizationRejection(OrganizationIsDeprecated(orgDeprecated))

          val payloadWithId = sourceFrom(id, value)
          resolvers
            .create(projectWithDeprecatedOrg.ref, payloadWithId)
            .rejected shouldEqual WrappedOrganizationRejection(OrganizationIsDeprecated(orgDeprecated))
        }
      }

      "fail if no identities are provided for a cross-project resolver" in {
        val newPrio      = Priority.unsafe(51)
        val invalidValue =
          crossProjectValue.copy(identityResolution = ProvidedIdentities(Set.empty), priority = newPrio)
        val payload      = sourceWithoutId(invalidValue)
        resolvers
          .create(nxv + "cross-project-no-id", projectRef, payload)
          .rejected shouldEqual NoIdentities
      }

      "fail if some provided identities don't belong to the caller for a cross-project resolver" in {

        val invalidValue =
          crossProjectValue.copy(
            identityResolution = ProvidedIdentities(Set(bob.subject, alice.subject)),
            priority = Priority.unsafe(51)
          )
        val payload      = sourceWithoutId(invalidValue)
        resolvers
          .create(nxv + "cross-project-miss-id", projectRef, payload)
          .rejected shouldEqual InvalidIdentities(Set(alice.subject))
      }

      "fail if mandatory values in source are missing" in {
        val payload = sourceWithoutId(crossProjectValue).removeKeys("projects")
        resolvers
          .create(nxv + "cross-project-miss-id", projectRef, payload)
          .rejectedWith[DecodingFailed]
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
          val payload = sourceWithoutId(value)
          resolvers
            .update(id, projectRef, 1L, payload)
            .accepted shouldEqual resolverResourceFor(
            id,
            project,
            value,
            payload,
            rev = 2L,
            subject = bob.subject
          )
        }
      }

      "succeed with a parsed value" in {
        forAll(
          List(
            nxv + "in-project-from-value"    -> inProjectValue.copy(priority = Priority.unsafe(999)),
            nxv + "cross-project-from-value" -> crossProjectValue.copy(priority = Priority.unsafe(998))
          )
        ) { case (id, value) =>
          resolvers.update(id, projectRef, 1L, value).accepted shouldEqual resolverResourceFor(
            id,
            project,
            value,
            ResolverValue.generateSource(id, value),
            rev = 2L,
            subject = bob.subject
          )
        }
      }

      "fail if it doesn't exist" in {
        forAll(
          List(
            nxv + "in-project-xxx"    -> inProjectValue.copy(priority = Priority.unsafe(51)),
            nxv + "cross-project-xxx" -> crossProjectValue.copy(priority = Priority.unsafe(51))
          )
        ) { case (id, value) =>
          val payload = sourceWithoutId(value)
          resolvers
            .update(id, projectRef, 1L, payload)
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
          val payload = sourceWithoutId(value)
          resolvers
            .update(id, projectRef, 5L, payload)
            .rejected shouldEqual IncorrectRev(5L, 2L)
        }
      }

      "fail if ids defined in segment and payload are different" in {
        forAll(
          List(
            nxv + "in-project"    -> inProjectValue,
            nxv + "cross-project" -> crossProjectValue
          )
        ) { case (id, value) =>
          val payloadId = nxv + "resolver-fail"
          val payload   = sourceFrom(payloadId, value)
          resolvers
            .update(id, projectRef, 2L, payload)
            .rejected shouldEqual UnexpectedResolverId(id = id, payloadId = payloadId)

        }
      }

      "fail if the project does not exist" in {
        forAll(
          List(
            nxv + "in-project"    -> inProjectValue,
            nxv + "cross-project" -> crossProjectValue
          )
        ) { case (id, value) =>
          val payload = sourceWithoutId(value)
          resolvers
            .update(id, unknownProjectRef, 2L, payload)
            .rejected shouldEqual WrappedProjectRejection(ProjectNotFound(unknownProjectRef))
        }
      }

      "fail if the project is deprecated" in {
        forAll(
          List(
            nxv + "in-project"    -> inProjectValue,
            nxv + "cross-project" -> crossProjectValue
          )
        ) { case (id, value) =>
          val payload = sourceWithoutId(value)
          resolvers
            .update(id, deprecatedProjectRef, 2L, payload)
            .rejected shouldEqual WrappedProjectRejection(ProjectIsDeprecated(deprecatedProjectRef))
        }
      }

      "fail if the org is deprecated" in {
        forAll(
          List(
            nxv + "in-project"    -> inProjectValue,
            nxv + "cross-project" -> crossProjectValue
          )
        ) { case (id, value) =>
          val payload = sourceWithoutId(value)
          resolvers
            .update(id, projectWithDeprecatedOrg.ref, 2L, payload)
            .rejected shouldEqual WrappedOrganizationRejection(OrganizationIsDeprecated(orgDeprecated))
        }
      }

      "fail if no identities are provided for a cross-project resolver" in {
        val invalidValue = crossProjectValue.copy(identityResolution = ProvidedIdentities(Set.empty))
        val payload      = sourceWithoutId(invalidValue)
        resolvers
          .update(nxv + "cross-project", projectRef, 2L, payload)
          .rejected shouldEqual NoIdentities
      }

      "fail if some provided identities don't belong to the caller for a cross-project resolver" in {
        val invalidValue =
          crossProjectValue.copy(
            identityResolution = ProvidedIdentities(Set(bob.subject, alice.subject)),
            priority = Priority.unsafe(51)
          )
        val payload      = sourceWithoutId(invalidValue)
        resolvers
          .update(nxv + "cross-project", projectRef, 2L, payload)
          .rejected shouldEqual InvalidIdentities(Set(alice.subject))
      }
    }

    val tag  = UserTag.unsafe("my-tag")
    val tag2 = UserTag.unsafe("my-tag2")

    "tagging a resolver" should {
      "succeed" in {
        forAll(
          List(
            nxv + "in-project"    -> updatedInProjectValue,
            nxv + "cross-project" -> updatedCrossProjectValue
          )
        ) { case (id, value) =>
          resolvers.tag(id, projectRef, tag, 1L, 2L).accepted shouldEqual resolverResourceFor(
            id,
            project,
            value,
            sourceWithoutId(value),
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
          resolvers.tag(id, projectRef, tag, 1L, 2L).rejected shouldEqual ResolverNotFound(id, projectRef)
        }
      }

      "fail if the provided revision does not match" in {
        forAll(
          List(
            nxv + "in-project",
            nxv + "cross-project"
          )
        ) { id =>
          resolvers.tag(id, projectRef, tag, 1L, 21L).rejected shouldEqual IncorrectRev(21L, 3L)
        }
      }

      "fail if the tag revision is invalid" in {
        forAll(
          List(
            nxv + "in-project",
            nxv + "cross-project"
          )
        ) { id =>
          resolvers.tag(id, projectRef, tag, 20L, 3L).rejected shouldEqual RevisionNotFound(20L, 3L)
        }
      }

      "fail if the project does not exist" in {
        forAll(
          List(
            nxv + "in-project",
            nxv + "cross-project"
          )
        ) { id =>
          resolvers.tag(id, unknownProjectRef, tag, 1L, 3L).rejected shouldEqual WrappedProjectRejection(
            ProjectNotFound(unknownProjectRef)
          )
        }
      }

      "fail if the project is deprecated" in {
        forAll(
          List(
            nxv + "in-project",
            nxv + "cross-project"
          )
        ) { id =>
          resolvers.tag(id, deprecatedProjectRef, tag, 1L, 3L).rejected shouldEqual WrappedProjectRejection(
            ProjectIsDeprecated(deprecatedProjectRef)
          )
        }
      }

      "fail if the org is deprecated" in {
        forAll(List(nxv + "in-project", nxv + "cross-project")) { id =>
          resolvers
            .tag(id, projectWithDeprecatedOrg.ref, tag, 1L, 3L)
            .rejected shouldEqual WrappedOrganizationRejection(OrganizationIsDeprecated(orgDeprecated))
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
          resolvers.deprecate(id, projectRef, 3L).accepted shouldEqual resolverResourceFor(
            id,
            project,
            value,
            sourceWithoutId(value),
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
          resolvers.deprecate(id, projectRef, 3L).rejected shouldEqual ResolverNotFound(id, projectRef)
        }
      }

      "fail if the provided revision does not match" in {
        forAll(
          List(
            nxv + "in-project",
            nxv + "cross-project"
          )
        ) { id =>
          resolvers.deprecate(id, projectRef, 3L).rejected shouldEqual IncorrectRev(3, 4L)
        }
      }

      "fail if the project does not exist" in {
        forAll(
          List(
            nxv + "in-project",
            nxv + "cross-project"
          )
        ) { id =>
          resolvers.deprecate(id, unknownProjectRef, 3L).rejected shouldEqual WrappedProjectRejection(
            ProjectNotFound(unknownProjectRef)
          )
        }
      }

      "fail if the project is deprecated" in {
        forAll(
          List(
            nxv + "in-project",
            nxv + "cross-project"
          )
        ) { id =>
          resolvers.deprecate(id, deprecatedProjectRef, 3L).rejected shouldEqual WrappedProjectRejection(
            ProjectIsDeprecated(deprecatedProjectRef)
          )
        }
      }

      "fail if the org is deprecated" in {
        forAll(List(nxv + "in-project", nxv + "cross-project")) { id =>
          resolvers
            .deprecate(id, projectWithDeprecatedOrg.ref, 3L)
            .rejected shouldEqual WrappedOrganizationRejection(OrganizationIsDeprecated(orgDeprecated))
        }
      }

      "fail if we try to deprecate it again" in {
        forAll(
          List(
            nxv + "in-project",
            nxv + "cross-project"
          )
        ) { id =>
          resolvers.deprecate(id, projectRef, 4L).rejected shouldEqual ResolverIsDeprecated(id)
        }
      }

      "fail if we try to update it" in {
        forAll(
          List(
            nxv + "in-project"    -> inProjectValue,
            nxv + "cross-project" -> crossProjectValue
          )
        ) { case (id, value) =>
          resolvers
            .update(id, projectRef, 4L, sourceWithoutId(value))
            .rejected shouldEqual ResolverIsDeprecated(id)
        }
      }

      "tag it after it has been deprecated" in {
        forAll(
          List(
            nxv + "in-project"    -> updatedInProjectValue,
            nxv + "cross-project" -> updatedCrossProjectValue
          )
        ) { case (id, value) =>
          resolvers.tag(id, projectRef, tag2, 4L, 4L).accepted shouldEqual resolverResourceFor(
            id,
            project,
            value,
            sourceWithoutId(value),
            tags = Map(tag -> 1L, tag2 -> 4),
            rev = 5L,
            subject = bob.subject,
            deprecated = true
          )
        }
      }
    }

    val inProjectExpected    = resolverResourceFor(
      nxv + "in-project",
      project,
      updatedInProjectValue,
      sourceWithoutId(updatedInProjectValue),
      tags = Map(tag -> 1L, tag2 -> 4),
      rev = 5L,
      subject = bob.subject,
      deprecated = true
    )
    val crossProjectExpected = resolverResourceFor(
      nxv + "cross-project",
      project,
      updatedCrossProjectValue,
      sourceWithoutId(updatedCrossProjectValue),
      tags = Map(tag -> 1L, tag2 -> 4),
      rev = 5L,
      subject = bob.subject,
      deprecated = true
    )

    "fetching a resolver" should {

      "succeed" in {
        forAll(List(inProjectExpected, crossProjectExpected)) { resource =>
          resolvers.fetch(resource.value.id.toString, projectRef).accepted shouldEqual resource
        }
      }

      "succeed by rev" in {
        val inProjectExpectedByRev    = resolverResourceFor(
          nxv + "in-project",
          project,
          updatedInProjectValue,
          sourceWithoutId(updatedInProjectValue),
          tags = Map(tag -> 1L),
          rev = 3L,
          subject = bob.subject
        )
        val crossProjectExpectedByRev = resolverResourceFor(
          nxv + "cross-project",
          project,
          updatedCrossProjectValue,
          sourceWithoutId(updatedCrossProjectValue),
          tags = Map(tag -> 1L),
          rev = 3L,
          subject = bob.subject
        )

        forAll(List(inProjectExpectedByRev, crossProjectExpectedByRev)) { resource =>
          resolvers.fetch(IdSegmentRef(resource.value.id, 3), projectRef).accepted shouldEqual
            resource
        }
      }

      "succeed by tag" in {
        forAll(
          List(
            nxv + "in-project"    -> inProjectValue,
            nxv + "cross-project" -> crossProjectValue
          )
        ) { case (id, value) =>
          resolvers.fetch(IdSegmentRef(id, tag), projectRef).accepted shouldEqual resolverResourceFor(
            id,
            project,
            value,
            sourceWithoutId(value),
            subject = bob.subject
          )
        }
      }

      "fail fetching if resolver does not exist" in {
        resolvers.fetch("xxx", projectRef).rejectedWith[ResolverNotFound]
      }

      "fail fetching if resolver does not exist at specific rev" in {
        resolvers.fetch(IdSegmentRef("xxx", 1), projectRef).rejectedWith[ResolverNotFound]
      }

      "fail if revision does not exist" in {
        resolvers.fetch(IdSegmentRef(nxv + "in-project", 30), projectRef).rejected shouldEqual
          RevisionNotFound(30L, 5L)
      }

      "fail if tag does not exist" in {
        val unknownTag = UserTag.unsafe("xxx")
        resolvers.fetch(IdSegmentRef(nxv + "in-project", unknownTag), projectRef).rejected shouldEqual
          TagNotFound(unknownTag)
      }
    }

    "list resolvers" should {
      val order = ResourceF.defaultSort[Resolver]

      "return deprecated resolvers" in {
        val results = resolvers
          .list(FromPagination(0, 10), ResolverSearchParams(deprecated = Some(true), filter = _ => true), order)
          .accepted

        results.total shouldEqual 2L
        results.results.map(_.source) should contain theSameElementsAs Vector(inProjectExpected, crossProjectExpected)
      }

      "return resolvers created by alice" in {
        val results = resolvers
          .list(FromPagination(0, 10), ResolverSearchParams(createdBy = Some(alice.subject), filter = _ => true), order)
          .accepted

        results.total shouldEqual 2L
        val inProj    = inProjectValue.copy(priority = Priority.unsafe(46))
        val crossProj = crossProjectValue.copy(identityResolution = UseCurrentCaller, priority = Priority.unsafe(47))
        results.results.map(_.source) should contain theSameElementsAs Vector(
          resolverResourceFor(
            nxv + "in-project-both",
            project,
            inProj,
            sourceFrom(nxv + "in-project-both", inProj),
            subject = alice.subject
          ),
          resolverResourceFor(
            nxv + "cross-project-both",
            project,
            crossProj,
            sourceFrom(nxv + "cross-project-both", crossProj),
            subject = alice.subject
          )
        )
      }

    }

    "getting events" should {
      val allEvents = SSEUtils.list(
        nxv + "in-project"               -> ResolverCreated,
        nxv + "cross-project"            -> ResolverCreated,
        nxv + "in-project-payload"       -> ResolverCreated,
        nxv + "cross-project-payload"    -> ResolverCreated,
        nxv + "in-project-both"          -> ResolverCreated,
        nxv + "cross-project-both"       -> ResolverCreated,
        nxv + uuid.toString              -> ResolverCreated,
        nxv + "in-project-from-value"    -> ResolverCreated,
        nxv + "cross-project-from-value" -> ResolverCreated,
        nxv + "in-project"               -> ResolverUpdated,
        nxv + "cross-project"            -> ResolverUpdated,
        nxv + "in-project-from-value"    -> ResolverUpdated,
        nxv + "cross-project-from-value" -> ResolverUpdated,
        nxv + "in-project"               -> ResolverTagAdded,
        nxv + "cross-project"            -> ResolverTagAdded,
        nxv + "in-project"               -> ResolverDeprecated,
        nxv + "cross-project"            -> ResolverDeprecated
      )

      "get all events" in {
        val streams = List(
          resolvers.events(NoOffset),
          resolvers.events(org, NoOffset).accepted,
          resolvers.events(projectRef, NoOffset).accepted
        )
        forAll(streams) { stream =>
          val events = stream
            .map { e => (e.event.id, e.eventType, e.offset) }
            .take(17L)
            .compile
            .toList
            .accepted
          events shouldEqual allEvents
        }
      }

      "get events from offset 2" in {
        val streams = List(
          resolvers.events(Sequence(2L)),
          resolvers.events(org, Sequence(2L)).accepted,
          resolvers.events(projectRef, Sequence(2L)).accepted
        )
        forAll(streams) { stream =>
          val events = stream
            .map { e => (e.event.id, e.eventType, e.offset) }
            .take(15L)
            .compile
            .toList
            .accepted
          events shouldEqual allEvents.drop(2)
        }
      }

      "reject if project does not exist" in {
        val projectRef = ProjectRef(org, Label.unsafe("other"))
        resolvers.events(projectRef, NoOffset).rejected shouldEqual WrappedProjectRejection(ProjectNotFound(projectRef))
      }

      "reject if organization does not exist" in {
        val org = Label.unsafe("other")
        resolvers.events(org, NoOffset).rejected shouldEqual WrappedOrganizationRejection(OrganizationNotFound(org))
      }
    }

    "finding references" should {
      "get all non-deprecated results for the given project" in {
        val result = finder(referencedProject).accepted.value

        result.size shouldEqual 1
        result(projectRef) should contain theSameElementsAs List(
          nxv.base / uuid.toString,
          nxv + "cross-project-both",
          nxv + "cross-project-payload",
          nxv + "cross-project-from-value"
        )
      }
    }

  }

}
