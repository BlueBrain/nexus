package ch.epfl.bluebrain.nexus.delta.sdk.resolvers

import cats.data.NonEmptyList
import cats.effect.IO
import cats.syntax.all._
import ch.epfl.bluebrain.nexus.delta.kernel.search.Pagination.FromPagination
import ch.epfl.bluebrain.nexus.delta.kernel.utils.UUIDF
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.{contexts, nxv, schema}
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.api.{JsonLdApi, JsonLdJavaApi}
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.RemoteContextResolution
import ch.epfl.bluebrain.nexus.delta.sdk.ConfigFixtures
import ch.epfl.bluebrain.nexus.delta.sdk.generators.ProjectGen
import ch.epfl.bluebrain.nexus.delta.sdk.generators.ResolverGen.{resolverResourceFor, sourceFrom, sourceWithoutId}
import ch.epfl.bluebrain.nexus.delta.sdk.identities.model.Caller
import ch.epfl.bluebrain.nexus.delta.sdk.implicits._
import ch.epfl.bluebrain.nexus.delta.sdk.model._
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.SearchParams.ResolverSearchParams
import ch.epfl.bluebrain.nexus.delta.sdk.projects.model.ApiMappings
import ch.epfl.bluebrain.nexus.delta.sdk.projects.model.ProjectRejection.{ProjectIsDeprecated, ProjectNotFound}
import ch.epfl.bluebrain.nexus.delta.sdk.projects.{FetchContextDummy, Projects}
import ch.epfl.bluebrain.nexus.delta.sdk.resolvers.model.IdentityResolution.{ProvidedIdentities, UseCurrentCaller}
import ch.epfl.bluebrain.nexus.delta.sdk.resolvers.model.ResolverRejection.{DecodingFailed, IncorrectRev, InvalidIdentities, InvalidResolverId, NoIdentities, PriorityAlreadyExists, ResolverIsDeprecated, ResolverNotFound, ResourceAlreadyExists, RevisionNotFound, UnexpectedResolverId}
import ch.epfl.bluebrain.nexus.delta.sdk.resolvers.model.ResolverValue.{CrossProjectValue, InProjectValue}
import ch.epfl.bluebrain.nexus.delta.sdk.resolvers.model._
import ch.epfl.bluebrain.nexus.delta.sdk.resources.Resources
import ch.epfl.bluebrain.nexus.delta.sourcing.EntityDependencyStore
import ch.epfl.bluebrain.nexus.delta.sourcing.model.EntityDependency.DependsOn
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.{Authenticated, Group, User}
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{Label, ProjectRef}
import ch.epfl.bluebrain.nexus.delta.sourcing.postgres.DoobieScalaTestFixture
import ch.epfl.bluebrain.nexus.testkit.scalatest.ce.CatsEffectSpec
import org.scalatest.CancelAfterFailure

import java.util.UUID

class ResolversImplSpec extends CatsEffectSpec with DoobieScalaTestFixture with CancelAfterFailure with ConfigFixtures {

  private val realm                = Label.unsafe("myrealm")
  implicit private val bob: Caller =
    Caller(User("Bob", realm), Set(User("Bob", realm), Group("mygroup", realm), Authenticated(realm)))
  private val alice                = Caller(User("Alice", realm), Set(User("Alice", realm), Group("mygroup2", realm)))

  private val uuid                  = UUID.randomUUID()
  implicit private val uuidF: UUIDF = UUIDF.fixed(uuid)

  implicit private val api: JsonLdApi = JsonLdJavaApi.strict

  private def res: RemoteContextResolution =
    RemoteContextResolution.fixed(
      contexts.resolvers         -> jsonContentOf("contexts/resolvers.json").topContextValueOrEmpty,
      contexts.resolversMetadata -> jsonContentOf("contexts/resolvers-metadata.json").topContextValueOrEmpty
    )

  private val resolverContextResolution: ResolverContextResolution = ResolverContextResolution(res)

  private val org               = Label.unsafe("org")
  private val apiMappings       = ApiMappings("nxv" -> nxv.base, "Person" -> schema.Person)
  private val base              = nxv.base
  private val project           = ProjectGen.project("org", "proj", base = base, mappings = apiMappings + Resources.mappings)
  private val deprecatedProject = ProjectGen.project("org", "proj-deprecated")

  private val projectRef           = project.ref
  private val deprecatedProjectRef = deprecatedProject.ref
  private val unknownProjectRef    = ProjectRef(org, Label.unsafe("xxx"))
  private val referencedProject    = ProjectRef.unsafe("org", "proj2")

  private val inProjectPrio    = Priority.unsafe(42)
  private val crossProjectPrio = Priority.unsafe(43)

  private val fetchContext = FetchContextDummy(
    Map(project.ref -> project.context, deprecatedProject.ref -> deprecatedProject.context),
    Set(deprecatedProject.ref)
  )

  private lazy val resolvers: Resolvers = ResolversImpl(
    fetchContext,
    resolverContextResolution,
    eventLogConfig,
    xas,
    clock
  )

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
            .accepted shouldEqual resolverResourceFor(id, projectRef, value, payload, subject = bob.subject)
        }

        // Dependency to the referenced project should have been saved
        EntityDependencyStore.directDependencies(projectRef, nxv + "cross-project", xas).accepted shouldEqual Set(
          DependsOn(referencedProject, Projects.encodeId(referencedProject))
        )
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
            projectRef,
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
            projectRef,
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
          projectRef,
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
            projectRef,
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
          resolvers.create(id, projectRef, payload).assertRejectedEquals(UnexpectedResolverId(id, payloadId))
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
          resolvers.create(id, projectRef, payload).assertRejectedEquals(InvalidResolverId(id))
        }
      }

      "fail if priority already exists" in {
        resolvers
          .create(nxv + "in-project-other", projectRef, inProjectValue)
          .assertRejectedEquals(PriorityAlreadyExists(projectRef, nxv + "in-project", inProjectValue.priority))
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
            .assertRejectedEquals(ResourceAlreadyExists(id, projectRef))

          val payloadWithId = sourceFrom(id, value)
          resolvers.create(projectRef, payloadWithId).assertRejectedEquals(ResourceAlreadyExists(id, projectRef))
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
            .rejectedWith[ProjectNotFound]

          val payloadWithId = sourceFrom(id, value)
          resolvers
            .create(unknownProjectRef, payloadWithId)
            .rejectedWith[ProjectNotFound]
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
            .rejectedWith[ProjectIsDeprecated]

          val payloadWithId = sourceFrom(id, value)
          resolvers
            .create(deprecatedProjectRef, payloadWithId)
            .rejectedWith[ProjectIsDeprecated]
        }
      }

      "fail if no identities are provided for a cross-project resolver" in {
        val newPrio      = Priority.unsafe(51)
        val invalidValue =
          crossProjectValue.copy(identityResolution = ProvidedIdentities(Set.empty), priority = newPrio)
        val payload      = sourceWithoutId(invalidValue)
        resolvers
          .create(nxv + "cross-project-no-id", projectRef, payload)
          .assertRejectedEquals(NoIdentities)
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
          .assertRejectedEquals(InvalidIdentities(Set(alice.subject)))
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
            .update(id, projectRef, 1, payload)
            .accepted shouldEqual resolverResourceFor(
            id,
            projectRef,
            value,
            payload,
            rev = 2,
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
          resolvers.update(id, projectRef, 1, value).accepted shouldEqual resolverResourceFor(
            id,
            projectRef,
            value,
            ResolverValue.generateSource(id, value),
            rev = 2,
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
            .update(id, projectRef, 1, payload)
            .assertRejectedEquals(ResolverNotFound(id, projectRef))
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
            .update(id, projectRef, 5, payload)
            .assertRejectedEquals(IncorrectRev(5, 2))
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
            .update(id, projectRef, 2, payload)
            .assertRejectedEquals(UnexpectedResolverId(id = id, payloadId = payloadId))
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
            .update(id, unknownProjectRef, 2, payload)
            .rejectedWith[ProjectNotFound]
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
            .update(id, deprecatedProjectRef, 2, payload)
            .rejectedWith[ProjectIsDeprecated]
        }
      }

      "fail if no identities are provided for a cross-project resolver" in {
        val invalidValue = crossProjectValue.copy(identityResolution = ProvidedIdentities(Set.empty))
        val payload      = sourceWithoutId(invalidValue)
        resolvers
          .update(nxv + "cross-project", projectRef, 2, payload)
          .assertRejectedEquals(NoIdentities)
      }

      "fail if some provided identities don't belong to the caller for a cross-project resolver" in {
        val invalidValue =
          crossProjectValue.copy(
            identityResolution = ProvidedIdentities(Set(bob.subject, alice.subject)),
            priority = Priority.unsafe(51)
          )
        val payload      = sourceWithoutId(invalidValue)
        resolvers
          .update(nxv + "cross-project", projectRef, 2, payload)
          .assertRejectedEquals(InvalidIdentities(Set(alice.subject)))
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
          resolvers.deprecate(id, projectRef, 2).accepted shouldEqual resolverResourceFor(
            id,
            projectRef,
            value,
            sourceWithoutId(value),
            rev = 3,
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
          resolvers.deprecate(id, projectRef, 3).assertRejectedEquals(ResolverNotFound(id, projectRef))
        }
      }

      "fail if the provided revision does not match" in {
        forAll(
          List(
            nxv + "in-project",
            nxv + "cross-project"
          )
        ) { id =>
          resolvers.deprecate(id, projectRef, 10).assertRejectedEquals(IncorrectRev(10, 3))
        }
      }

      "fail if the project does not exist" in {
        forAll(
          List(
            nxv + "in-project",
            nxv + "cross-project"
          )
        ) { id =>
          resolvers.deprecate(id, unknownProjectRef, 3).rejectedWith[ProjectNotFound]
        }
      }

      "fail if the project is deprecated" in {
        forAll(
          List(
            nxv + "in-project",
            nxv + "cross-project"
          )
        ) { id =>
          resolvers.deprecate(id, deprecatedProjectRef, 3).rejectedWith[ProjectIsDeprecated]
        }
      }

      "fail if we try to deprecate it again" in {
        forAll(
          List(
            nxv + "in-project",
            nxv + "cross-project"
          )
        ) { id =>
          resolvers.deprecate(id, projectRef, 3).assertRejectedEquals(ResolverIsDeprecated(id))
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
            .update(id, projectRef, 3, sourceWithoutId(value))
            .assertRejectedEquals(ResolverIsDeprecated(id))
        }
      }
    }

    val inProjectExpected    = resolverResourceFor(
      nxv + "in-project",
      projectRef,
      updatedInProjectValue,
      sourceWithoutId(updatedInProjectValue),
      rev = 3,
      subject = bob.subject,
      deprecated = true
    )
    val crossProjectExpected = resolverResourceFor(
      nxv + "cross-project",
      projectRef,
      updatedCrossProjectValue,
      sourceWithoutId(updatedCrossProjectValue),
      rev = 3,
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
        forAll(List(inProjectExpected, crossProjectExpected)) { resource =>
          resolvers.fetch(IdSegmentRef(resource.value.id, 3), projectRef).accepted shouldEqual
            resource
        }
      }

      "fail fetching if resolver does not exist" in {
        resolvers.fetch("xxx", projectRef).rejectedWith[ResolverNotFound]
      }

      "fail fetching if resolver does not exist at specific rev" in {
        resolvers.fetch(IdSegmentRef("xxx", 1), projectRef).rejectedWith[ResolverNotFound]
      }

      "fail if revision does not exist" in {
        resolvers.fetch(IdSegmentRef(nxv + "in-project", 30), projectRef).assertRejectedEquals(RevisionNotFound(30, 3))
      }
    }

    "list resolvers" should {
      val order = ResourceF.defaultSort[Resolver]

      "return deprecated resolvers" in {
        val results = resolvers
          .list(
            FromPagination(0, 10),
            ResolverSearchParams(deprecated = Some(true), filter = _ => IO.pure(true)),
            order
          )
          .accepted

        results.total shouldEqual 2
        results.results.map(_.source) should contain theSameElementsAs Vector(inProjectExpected, crossProjectExpected)
      }

      "return resolvers created by alice" in {
        val results = resolvers
          .list(
            FromPagination(0, 10),
            ResolverSearchParams(createdBy = Some(alice.subject), filter = _ => IO.pure(true)),
            order
          )
          .accepted

        results.total shouldEqual 2
        val inProj    = inProjectValue.copy(priority = Priority.unsafe(46))
        val crossProj = crossProjectValue.copy(identityResolution = UseCurrentCaller, priority = Priority.unsafe(47))
        results.results.map(_.source) should contain theSameElementsAs Vector(
          resolverResourceFor(
            nxv + "in-project-both",
            projectRef,
            inProj,
            sourceFrom(nxv + "in-project-both", inProj),
            subject = alice.subject
          ),
          resolverResourceFor(
            nxv + "cross-project-both",
            projectRef,
            crossProj,
            sourceFrom(nxv + "cross-project-both", crossProj),
            subject = alice.subject
          )
        )
      }

    }
  }

}
