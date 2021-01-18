package ch.epfl.bluebrain.nexus.delta.sdk.testkit

import akka.persistence.query.{NoOffset, Sequence}
import cats.data.NonEmptyList
import cats.implicits._
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.{contexts, nxv, schema}
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.RemoteContextResolution
import ch.epfl.bluebrain.nexus.delta.sdk.Resolvers
import ch.epfl.bluebrain.nexus.delta.sdk.generators.ProjectGen
import ch.epfl.bluebrain.nexus.delta.sdk.generators.ResolverGen._
import ch.epfl.bluebrain.nexus.delta.sdk.model.IdSegment.{IriSegment, StringSegment}
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Caller
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Identity.{Authenticated, Group, User}
import ch.epfl.bluebrain.nexus.delta.sdk.model.organizations.OrganizationRejection.OrganizationIsDeprecated
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.ProjectRejection.{ProjectIsDeprecated, ProjectNotFound}
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.{ApiMappings, ProjectRef}
import ch.epfl.bluebrain.nexus.delta.sdk.model.resolvers.IdentityResolution.{ProvidedIdentities, UseCurrentCaller}
import ch.epfl.bluebrain.nexus.delta.sdk.model.resolvers.ResolverEvent.{ResolverCreated, ResolverDeprecated, ResolverTagAdded, ResolverUpdated}
import ch.epfl.bluebrain.nexus.delta.sdk.model.resolvers.ResolverRejection.{DecodingFailed, IncorrectRev, InvalidIdentities, InvalidResolverId, NoIdentities, ResolverAlreadyExists, ResolverIsDeprecated, ResolverNotFound, RevisionNotFound, TagNotFound, UnexpectedResolverId, WrappedOrganizationRejection, WrappedProjectRejection}
import ch.epfl.bluebrain.nexus.delta.sdk.model.resolvers.ResolverValue.{CrossProjectValue, InProjectValue}
import ch.epfl.bluebrain.nexus.delta.sdk.model.resolvers.{Priority, Resolver, ResolverContextResolution, ResolverValue, ResourceResolutionReport}
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.Pagination.FromPagination
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.SearchParams.ResolverSearchParams
import ch.epfl.bluebrain.nexus.delta.sdk.model.{BaseUri, Label, ResourceF, TagLabel}
import ch.epfl.bluebrain.nexus.delta.sdk.syntax._
import ch.epfl.bluebrain.nexus.delta.sdk.utils.UUIDF
import ch.epfl.bluebrain.nexus.testkit.{IOFixedClock, IOValues, TestHelpers}
import monix.bio.{IO, UIO}
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

  def res: RemoteContextResolution                         =
    RemoteContextResolution.fixed(contexts.resolvers -> jsonContentOf("/contexts/resolvers.json"))

  val resolverContextResolution: ResolverContextResolution = new ResolverContextResolution(
    res,
    (_, _, _) => IO.raiseError(ResourceResolutionReport())
  )

  val org                      = Label.unsafe("org")
  val orgDeprecated            = Label.unsafe("org-deprecated")
  val apiMappings              = ApiMappings(Map("nxv" -> nxv.base, "Person" -> schema.Person))
  val base                     = nxv.base
  val project                  = ProjectGen.project("org", "proj", base = base, mappings = apiMappings)
  val deprecatedProject        = ProjectGen.project("org", "proj-deprecated")
  val projectWithDeprecatedOrg = ProjectGen.project("org-deprecated", "other-proj")

  val projectRef           = project.ref
  val deprecatedProjectRef = deprecatedProject.ref
  val unknownProjectRef    = ProjectRef(org, Label.unsafe("xxx"))

  private val priority = Priority.unsafe(42)

  lazy val projects: ProjectsDummy = ProjectSetup
    .init(
      orgsToCreate = org :: orgDeprecated :: Nil,
      projectsToCreate = project :: deprecatedProject :: projectWithDeprecatedOrg :: Nil,
      projectsToDeprecate = deprecatedProject.ref :: Nil,
      organizationsToDeprecate = orgDeprecated :: Nil
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
          val payload = sourceWithoutId(value)
          resolvers
            .create(IriSegment(id), projectRef, payload)
            .accepted shouldEqual resolverResourceFor(id, project, value, payload, subject = bob.subject)
        }
      }

      "succeed with the id only defined in the payload" in {
        forAll(
          List(
            nxv + "in-project-payload"    -> inProjectValue,
            nxv + "cross-project-payload" -> crossProjectValue
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
            nxv + "in-project-both"    -> inProjectValue,
            nxv + "cross-project-both" -> crossProjectValue.copy(identityResolution = UseCurrentCaller)
          )
        ) { case (id, value) =>
          val payload = sourceFrom(id, value)
          resolvers
            .create(IriSegment(id), projectRef, payload)(alice)
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
        val expectedValue = crossProjectValue.copy(resourceTypes = Set(nxv.Schema))
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
            nxv + "in-project-from-value"    -> inProjectValue,
            nxv + "cross-project-from-value" -> crossProjectValue
          )
        ) { case (id, value) =>
          resolvers.create(IriSegment(id), projectRef, value).accepted shouldEqual resolverResourceFor(
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
            .create(IriSegment(id), projectRef, payload)
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
          resolvers.create(StringSegment(id), projectRef, payload).rejected shouldEqual InvalidResolverId(id)
        }
      }

      "fail if it already exists" in {
        forAll(
          (List(nxv + "in-project"), List(inProjectValue, crossProjectValue)).tupled
        ) { case (id, value) =>
          val payload = sourceWithoutId(value)
          resolvers
            .create(StringSegment(id.toString), projectRef, payload)
            .rejected shouldEqual ResolverAlreadyExists(id, projectRef)

          val payloadWithId = sourceFrom(id, value)
          resolvers
            .create(projectRef, payloadWithId)
            .rejected shouldEqual ResolverAlreadyExists(
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
            .create(IriSegment(id), unknownProjectRef, payload)
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
            .create(IriSegment(id), deprecatedProjectRef, payload)
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
            .create(IriSegment(id), projectWithDeprecatedOrg.ref, payload)
            .rejected shouldEqual WrappedOrganizationRejection(OrganizationIsDeprecated(orgDeprecated))

          val payloadWithId = sourceFrom(id, value)
          resolvers
            .create(projectWithDeprecatedOrg.ref, payloadWithId)
            .rejected shouldEqual WrappedOrganizationRejection(OrganizationIsDeprecated(orgDeprecated))
        }
      }

      "fail if no identities are provided for a cross-project resolver" in {
        val invalidValue = crossProjectValue.copy(identityResolution = ProvidedIdentities(Set.empty))
        val payload      = sourceWithoutId(invalidValue)
        resolvers
          .create(IriSegment(nxv + "cross-project-no-id"), projectRef, payload)
          .rejected shouldEqual NoIdentities
      }

      "fail if some provided identities don't belong to the caller for a cross-project resolver" in {
        val invalidValue =
          crossProjectValue.copy(identityResolution = ProvidedIdentities(Set(bob.subject, alice.subject)))
        val payload      = sourceWithoutId(invalidValue)
        resolvers
          .create(IriSegment(nxv + "cross-project-miss-id"), projectRef, payload)
          .rejected shouldEqual InvalidIdentities(Set(alice.subject))
      }

      "fail if mandatory values in source are missing" in {
        val payload = sourceWithoutId(crossProjectValue).removeKeys("projects")
        resolvers
          .create(IriSegment(nxv + "cross-project-miss-id"), projectRef, payload)
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
            .update(IriSegment(id), projectRef, 1L, payload)
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
            nxv + "cross-project-from-value" -> crossProjectValue.copy(priority = Priority.unsafe(999))
          )
        ) { case (id, value) =>
          resolvers.update(IriSegment(id), projectRef, 1L, value).accepted shouldEqual resolverResourceFor(
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
            nxv + "in-project-xxx"    -> inProjectValue,
            nxv + "cross-project-xxx" -> crossProjectValue
          )
        ) { case (id, value) =>
          val payload = sourceWithoutId(value)
          resolvers
            .update(IriSegment(id), projectRef, 1L, payload)
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
            .update(IriSegment(id), projectRef, 5L, payload)
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
            .update(IriSegment(id), projectRef, 2L, payload)
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
            .update(IriSegment(id), unknownProjectRef, 2L, payload)
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
            .update(IriSegment(id), deprecatedProjectRef, 2L, payload)
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
            .update(IriSegment(id), projectWithDeprecatedOrg.ref, 2L, payload)
            .rejected shouldEqual WrappedOrganizationRejection(OrganizationIsDeprecated(orgDeprecated))
        }
      }

      "fail if no identities are provided for a cross-project resolver" in {
        val invalidValue = crossProjectValue.copy(identityResolution = ProvidedIdentities(Set.empty))
        val payload      = sourceWithoutId(invalidValue)
        resolvers
          .update(IriSegment(nxv + "cross-project"), projectRef, 2L, payload)
          .rejected shouldEqual NoIdentities
      }

      "fail if some provided identities don't belong to the caller for a cross-project resolver" in {
        val invalidValue =
          crossProjectValue.copy(identityResolution = ProvidedIdentities(Set(bob.subject, alice.subject)))
        val payload      = sourceWithoutId(invalidValue)
        resolvers
          .update(IriSegment(nxv + "cross-project"), projectRef, 2L, payload)
          .rejected shouldEqual InvalidIdentities(Set(alice.subject))
      }
    }

    val tag = TagLabel.unsafe("my-tag")

    "tagging a resolver" should {
      "succeed" in {
        forAll(
          List(
            nxv + "in-project"    -> updatedInProjectValue,
            nxv + "cross-project" -> updatedCrossProjectValue
          )
        ) { case (id, value) =>
          resolvers.tag(IriSegment(id), projectRef, tag, 1L, 2L).accepted shouldEqual resolverResourceFor(
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

      "fail if the project does not exist" in {
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

      "fail if the project is deprecated" in {
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

      "fail if the org is deprecated" in {
        forAll(List(nxv + "in-project", nxv + "cross-project")) { id =>
          resolvers
            .tag(IriSegment(id), projectWithDeprecatedOrg.ref, tag, 1L, 3L)
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
          resolvers.deprecate(IriSegment(id), projectRef, 3L).accepted shouldEqual resolverResourceFor(
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

      "fail if the project does not exist" in {
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

      "fail if the project is deprecated" in {
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

      "fail if the org is deprecated" in {
        forAll(List(nxv + "in-project", nxv + "cross-project")) { id =>
          resolvers
            .deprecate(IriSegment(id), projectWithDeprecatedOrg.ref, 3L)
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
          resolvers.deprecate(IriSegment(id), projectRef, 4L).rejected shouldEqual ResolverIsDeprecated(id)
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
            .update(IriSegment(id), projectRef, 4L, sourceWithoutId(value))
            .rejected shouldEqual ResolverIsDeprecated(id)
        }
      }

      "fail if we try to tag it" in {
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

    val inProjectExpected    = resolverResourceFor(
      nxv + "in-project",
      project,
      updatedInProjectValue,
      sourceWithoutId(updatedInProjectValue),
      tags = Map(tag -> 1L),
      rev = 4L,
      subject = bob.subject,
      deprecated = true
    )
    val crossProjectExpected = resolverResourceFor(
      nxv + "cross-project",
      project,
      updatedCrossProjectValue,
      sourceWithoutId(updatedCrossProjectValue),
      tags = Map(tag -> 1L),
      rev = 4L,
      subject = bob.subject,
      deprecated = true
    )

    "fetching a resolver" should {

      "succeed" in {
        forAll(List(inProjectExpected, crossProjectExpected)) { resource =>
          resolvers.fetch(StringSegment(resource.value.id.toString), projectRef).accepted shouldEqual resource
        }
      }

      "succeed by rev" in {
        forAll(List(inProjectExpected, crossProjectExpected)) { resource =>
          resolvers.fetchAt(IriSegment(resource.value.id), projectRef, 3L).accepted shouldEqual
            resource.copy(rev = 3L, deprecated = false)
        }
      }

      "succeed by tag" in {
        forAll(
          List(
            nxv + "in-project"    -> inProjectValue,
            nxv + "cross-project" -> crossProjectValue
          )
        ) { case (id, value) =>
          resolvers.fetchBy(IriSegment(id), projectRef, tag).accepted shouldEqual resolverResourceFor(
            id,
            project,
            value,
            sourceWithoutId(value),
            subject = bob.subject
          )
        }
      }

      "fail fetching if resolver does not exist" in {
        resolvers.fetch(StringSegment("xxx"), projectRef).rejectedWith[ResolverNotFound]
      }

      "fail fetching if resolver does not exist at specific rev" in {
        resolvers.fetchAt(StringSegment("xxx"), projectRef, 1L).rejectedWith[ResolverNotFound]
      }

      "fail if revision does not exist" in {
        resolvers.fetchAt(IriSegment(nxv + "in-project"), projectRef, 30L).rejected shouldEqual
          RevisionNotFound(30L, 4L)
      }

      "fail if tag does not exist" in {
        val unknownTag = TagLabel.unsafe("xxx")
        resolvers.fetchBy(IriSegment(nxv + "in-project"), projectRef, unknownTag).rejected shouldEqual
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
        results.results.map(_.source) should contain theSameElementsAs Vector(
          resolverResourceFor(
            nxv + "in-project-both",
            project,
            inProjectValue,
            sourceFrom(nxv + "in-project-both", inProjectValue),
            subject = alice.subject
          ),
          resolverResourceFor(
            nxv + "cross-project-both",
            project,
            crossProjectValue.copy(identityResolution = UseCurrentCaller),
            sourceFrom(nxv + "cross-project-both", crossProjectValue.copy(identityResolution = UseCurrentCaller)),
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
        val events = resolvers
          .events(NoOffset)
          .map { e => (e.event.id, e.eventType, e.offset) }
          .take(17L)
          .compile
          .toList
          .accepted
        events shouldEqual allEvents
      }

      "get events from offset 2" in {
        val events = resolvers
          .events(Sequence(2L))
          .map { e => (e.event.id, e.eventType, e.offset) }
          .take(15L)
          .compile
          .toList
          .accepted
        events shouldEqual allEvents.drop(2)
      }
    }

  }

}
