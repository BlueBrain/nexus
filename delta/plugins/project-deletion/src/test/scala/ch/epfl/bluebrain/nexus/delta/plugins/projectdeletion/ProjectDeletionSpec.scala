package ch.epfl.bluebrain.nexus.delta.plugins.projectdeletion

import cats.effect.Clock
import cats.effect.concurrent.Deferred
import ch.epfl.bluebrain.nexus.delta.kernel.utils.UUIDF
import ch.epfl.bluebrain.nexus.delta.plugins.projectdeletion.ProjectDeletionSpec.Fixture
import ch.epfl.bluebrain.nexus.delta.plugins.projectdeletion.model.ProjectDeletionConfig
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.api.{JsonLdApi, JsonLdJavaApi}
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.RemoteContextResolution
import ch.epfl.bluebrain.nexus.delta.sdk.ProjectReferenceFinder.ProjectReferenceMap
import ch.epfl.bluebrain.nexus.delta.sdk.generators.ProjectGen
import ch.epfl.bluebrain.nexus.delta.sdk.model.Event.ProjectScopedEvent
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Identity.{Subject, User}
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.{Caller, ServiceAccount}
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.ApiMappings
import ch.epfl.bluebrain.nexus.delta.sdk.model.resolvers.{ResolverContextResolution, ResourceResolution, ResourceResolutionReport}
import ch.epfl.bluebrain.nexus.delta.sdk.model.{BaseUri, Envelope}
import ch.epfl.bluebrain.nexus.delta.sdk.testkit._
import ch.epfl.bluebrain.nexus.delta.sdk.{ProjectReferenceFinder, QuotasDummy, SchemaImports}
import ch.epfl.bluebrain.nexus.delta.sourcing.EventLog
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Label
import ch.epfl.bluebrain.nexus.delta.sourcing.model.ProjectRef
import ch.epfl.bluebrain.nexus.testkit.IOValues
import io.circe.literal._
import monix.bio.{IO, Task, UIO}
import monix.execution.Scheduler
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

import scala.concurrent.duration.DurationInt

//noinspection TypeAnnotation
class ProjectDeletionSpec extends AnyWordSpecLike with Matchers with IOValues {

  implicit private val uuidF: UUIDF                = UUIDF.random
  implicit private val baseUri: BaseUri            = BaseUri("http://localhost", Label.unsafe("v1"))
  implicit private val sa: ServiceAccount          = ServiceAccount(User("nexus-sa", Label.unsafe("realm")))
  implicit private val subject: Subject            = sa.subject
  implicit private val caller: Caller              = Caller.unsafe(subject)
  implicit private val jsonLdApi: JsonLdApi        = JsonLdJavaApi.strict
  implicit private val scheduler: Scheduler        = Scheduler.global
  implicit private val prf: ProjectReferenceFinder = (_: ProjectRef) => UIO.pure(ProjectReferenceMap.empty)

  "A ProjectDeletion" should {

    "delete deprecated projects immediately" in new Fixture {
      val config = ProjectDeletionConfig(
        idleInterval = 900.hours,
        idleCheckPeriod = 50.millis,
        deleteDeprecatedProjects = true,
        includedProjects = List(".+".r),
        excludedProjects = List()
      )

      val (orgs, projects, _, eventLog) = testContext.accepted
      val retained                      = ProjectGen.project("org", "proj")
      val deprecated                    = ProjectGen.project("org", "deprecated")

      orgs.create(Label.unsafe("org"), None).accepted
      projects.create(retained.ref, ProjectGen.projectFields(retained)).accepted
      projects.create(deprecated.ref, ProjectGen.projectFields(deprecated)).accepted
      projects.deprecate(deprecated.ref, 1L).accepted

      val deletion = ProjectDeletion(projects, config, eventLog, DeleteProject(projects)).accepted
      (Task.sleep(200.millis) >> deletion.stop()).accepted
      projects.fetch(deprecated.ref).accepted.value.markedForDeletion shouldEqual true
      projects.fetch(retained.ref).accepted.value.markedForDeletion shouldEqual false
    }

    "not delete deprecated projects immediately" in new Fixture {
      val config = ProjectDeletionConfig(
        idleInterval = 900.hours,
        idleCheckPeriod = 50.millis,
        deleteDeprecatedProjects = false,
        includedProjects = List(".+".r),
        excludedProjects = List()
      )

      val (orgs, projects, _, eventLog) = testContext.accepted
      val deprecated                    = ProjectGen.project("org", "deprecated")

      orgs.create(Label.unsafe("org"), None).accepted
      projects.create(deprecated.ref, ProjectGen.projectFields(deprecated)).accepted
      projects.deprecate(deprecated.ref, 1L).accepted

      val deletion = ProjectDeletion(projects, config, eventLog, DeleteProject(projects)).accepted
      (Task.sleep(200.millis) >> deletion.stop()).accepted

      projects.fetch(deprecated.ref).accepted.value.markedForDeletion shouldEqual false
    }

    "delete projects that match both inclusion and exclusion regexes" in new Fixture {
      val config = ProjectDeletionConfig(
        idleInterval = 100.millis,
        idleCheckPeriod = 50.millis,
        deleteDeprecatedProjects = false,
        includedProjects = List("some.+".r, "other.+".r),
        excludedProjects = List(".*retained.*".r)
      )

      val (orgs, projects, _, eventLog) = testContext.accepted
      val deprecated                    = ProjectGen.project("org", "deprecated")
      val otherRetained                 = ProjectGen.project("other", "retained")
      val otherDeleted                  = ProjectGen.project("other", "deleted")
      val someRetained                  = ProjectGen.project("some", "retained")
      val someDeleted                   = ProjectGen.project("some", "deleted")

      orgs.create(Label.unsafe("org"), None).accepted
      orgs.create(Label.unsafe("other"), None).accepted
      orgs.create(Label.unsafe("some"), None).accepted

      projects.create(deprecated.ref, ProjectGen.projectFields(deprecated)).accepted
      projects.deprecate(deprecated.ref, 1L).accepted
      projects.create(otherRetained.ref, ProjectGen.projectFields(otherRetained)).accepted
      projects.create(otherDeleted.ref, ProjectGen.projectFields(otherDeleted)).accepted
      projects.deprecate(otherDeleted.ref, 1L).accepted
      projects.create(someRetained.ref, ProjectGen.projectFields(someRetained)).accepted
      projects.create(someDeleted.ref, ProjectGen.projectFields(someDeleted)).accepted

      val deletion = ProjectDeletion(projects, config, eventLog, DeleteProject(projects)).accepted
      (Task.sleep(2.seconds) >> deletion.stop()).accepted

      projects.fetch(deprecated.ref).accepted.value.markedForDeletion shouldEqual false
      projects.fetch(otherRetained.ref).accepted.value.markedForDeletion shouldEqual false
      projects.fetch(otherDeleted.ref).accepted.value.markedForDeletion shouldEqual true
      projects.fetch(someRetained.ref).accepted.value.markedForDeletion shouldEqual false
      projects.fetch(someDeleted.ref).accepted.value.markedForDeletion shouldEqual true
    }

    "delete projects that become idle" in new Fixture {
      val config = ProjectDeletionConfig(
        idleInterval = 2.seconds,
        idleCheckPeriod = 50.millis,
        deleteDeprecatedProjects = false,
        includedProjects = List(".+".r),
        excludedProjects = List()
      )

      val (orgs, projects, resources, eventLog) = testContext.accepted
      val otherRetained                         = ProjectGen.project("other", "retained")
      val otherDeleted                          = ProjectGen.project("other", "deleted")

      orgs.create(Label.unsafe("other"), None).accepted
      projects.create(otherRetained.ref, ProjectGen.projectFields(otherRetained)).accepted
      projects.create(otherDeleted.ref, ProjectGen.projectFields(otherDeleted)).accepted

      val deletion = ProjectDeletion(projects, config, eventLog, DeleteProject(projects)).accepted
      fs2.Stream
        .fixedRate[Task](100.millis)
        .evalMap { _ =>
          resources
            .create(otherRetained.ref, Vocabulary.schemas.resources, json"""{"@type": "Person"}""")
            .mapError(rej => new RuntimeException(rej.reason))
        }
        .interruptAfter(4.seconds)
        .compile
        .drain
        .acceptedWithTimeout(10.seconds)

      deletion.stop().accepted
      projects.fetch(otherRetained.ref).accepted.value.markedForDeletion shouldEqual false
      projects.fetch(otherDeleted.ref).accepted.value.markedForDeletion shouldEqual true
    }

    "not delete projects that do not match the inclusion filter" in new Fixture {
      val config = ProjectDeletionConfig(
        idleInterval = 100.millis,
        idleCheckPeriod = 50.millis,
        deleteDeprecatedProjects = true,
        includedProjects = List(),
        excludedProjects = List()
      )

      val (orgs, projects, _, eventLog) = testContext.accepted
      val otherRetained                 = ProjectGen.project("other", "retained")

      orgs.create(Label.unsafe("other"), None).accepted
      projects.create(otherRetained.ref, ProjectGen.projectFields(otherRetained)).accepted
      projects.deprecate(otherRetained.ref, 1L).accepted

      val deletion = ProjectDeletion(projects, config, eventLog, DeleteProject(projects)).accepted
      (Task.sleep(2.seconds) >> deletion.stop()).accepted

      projects.fetch(otherRetained.ref).accepted.value.markedForDeletion shouldEqual false
    }
  }

}

object ProjectDeletionSpec {

  private class Fixture(implicit uuidf: UUIDF, clock: Clock[UIO], baseUri: BaseUri, api: JsonLdApi) {
    val testContext: UIO[(OrganizationsDummy, ProjectsDummy, ResourcesDummy, EventLog[Envelope[ProjectScopedEvent]])] =
      (for {
        orgs                     <- OrganizationsDummy()
        projects                 <- ProjectsDummy(orgs, QuotasDummy.neverReached, ApiMappings.empty)
        acls                     <- AclSetup.init()
        resolverContextResolution = new ResolverContextResolution(
                                      RemoteContextResolution.fixed(),
                                      (_, _, _) => IO.raiseError(ResourceResolutionReport())
                                    )
        resolvers                <- ResolversDummy(orgs, projects, resolverContextResolution, (_, _) => IO.unit)
        schemaImports            <- Deferred[Task, SchemaImports]
        schemas                  <-
          SchemasDummy.fromDeferredImports(orgs, projects, schemaImports, resolverContextResolution, (_, _) => IO.unit)
        _                        <- schemaImports.complete(SchemaImports(acls, resolvers, schemas, null))
        resolverResolution        = ResourceResolution.schemaResource(acls, resolvers, schemas)
        resources                <- ResourcesDummy(orgs, projects, resolverResolution, (_, _) => IO.unit, resolverContextResolution)
        eventLog                  = resources.journal.asInstanceOf[EventLog[Envelope[ProjectScopedEvent]]]
      } yield (orgs, projects, resources, eventLog)).hideErrors
  }

}
