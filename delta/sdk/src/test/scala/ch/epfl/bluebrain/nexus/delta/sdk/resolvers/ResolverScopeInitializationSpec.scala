package ch.epfl.bluebrain.nexus.delta.sdk.resolvers

import ch.epfl.bluebrain.nexus.delta.kernel.utils.UUIDF
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.{contexts, nxv, schema}
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.api.{JsonLdApi, JsonLdJavaApi}
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.RemoteContextResolution
import ch.epfl.bluebrain.nexus.delta.sdk.ConfigFixtures
import ch.epfl.bluebrain.nexus.delta.sdk.generators.ProjectGen
import ch.epfl.bluebrain.nexus.delta.sdk.identities.model.ServiceAccount
import ch.epfl.bluebrain.nexus.delta.sdk.model.IdSegment
import ch.epfl.bluebrain.nexus.delta.sdk.projects.FetchContextDummy
import ch.epfl.bluebrain.nexus.delta.sdk.projects.model.ApiMappings
import ch.epfl.bluebrain.nexus.delta.sdk.resolvers.model.ResolverRejection.{ProjectContextRejection, ResolverNotFound}
import ch.epfl.bluebrain.nexus.delta.sdk.resolvers.model.ResolverValue.InProjectValue
import ch.epfl.bluebrain.nexus.delta.sdk.resolvers.model.{Priority, ResourceResolutionReport}
import ch.epfl.bluebrain.nexus.delta.sdk.syntax._
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.{Subject, User}
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Label
import ch.epfl.bluebrain.nexus.testkit.{DoobieScalaTestFixture, IOFixedClock, IOValues, TestHelpers}
import monix.bio.IO
import org.scalatest.matchers.should.Matchers

import java.util.UUID

class ResolverScopeInitializationSpec
    extends DoobieScalaTestFixture
    with Matchers
    with IOValues
    with IOFixedClock
    with TestHelpers
    with ConfigFixtures {

  private val defaultInProjectResolverId: IdSegment = nxv.defaultResolver

  private val uuid                        = UUID.randomUUID()
  implicit private val uuidF: UUIDF       = UUIDF.fixed(uuid)
  private val saRealm: Label              = Label.unsafe("service-accounts")
  private val usersRealm: Label           = Label.unsafe("users")
  implicit private val sa: ServiceAccount = ServiceAccount(User("nexus-sa", saRealm))
  implicit private val bob: Subject       = User("bob", usersRealm)

  private val am       = ApiMappings("nxv" -> nxv.base, "Person" -> schema.Person)
  private val projBase = nxv.base
  private val project  =
    ProjectGen.project("org", "project", uuid = uuid, orgUuid = uuid, base = projBase, mappings = am)

  private lazy val resolvers: Resolvers = {
    implicit val api: JsonLdApi = JsonLdJavaApi.strict
    val resolution              = RemoteContextResolution.fixed(
      contexts.resolvers -> jsonContentOf("/contexts/resolvers.json").topContextValueOrEmpty
    )
    val rcr                     = new ResolverContextResolution(resolution, (_, _, _) => IO.raiseError(ResourceResolutionReport()))
    ResolversImpl(
      FetchContextDummy(List(project), ProjectContextRejection),
      rcr,
      ResolversConfig(eventLogConfig, pagination),
      xas
    )
  }
  "A ResolverScopeInitialization" should {
    lazy val init = new ResolverScopeInitialization(resolvers, sa)

    "create a default resolver on newly created project" in {
      resolvers.fetch(defaultInProjectResolverId, project.ref).rejectedWith[ResolverNotFound]
      init.onProjectCreation(project, bob).accepted
      val resource = resolvers.fetch(defaultInProjectResolverId, project.ref).accepted
      resource.value.value shouldEqual InProjectValue(Priority.unsafe(1))
      resource.rev shouldEqual 1L
      resource.createdBy shouldEqual sa.caller.subject
    }

    "not create a new resolver if one already exists" in {
      resolvers.fetch(defaultInProjectResolverId, project.ref).accepted.rev shouldEqual 1L
      init.onProjectCreation(project, bob).accepted
      val resource = resolvers.fetch(defaultInProjectResolverId, project.ref).accepted
      resource.rev shouldEqual 1L
    }
  }

}
