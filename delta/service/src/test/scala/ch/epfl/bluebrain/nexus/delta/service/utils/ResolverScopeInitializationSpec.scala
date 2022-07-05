package ch.epfl.bluebrain.nexus.delta.service.utils

import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.{contexts, nxv, schema}
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.api.{JsonLdApi, JsonLdJavaApi}
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.RemoteContextResolution
import ch.epfl.bluebrain.nexus.delta.sdk.Resolvers
import ch.epfl.bluebrain.nexus.delta.sdk.generators.ProjectGen
import ch.epfl.bluebrain.nexus.delta.sdk.identities.model.ServiceAccount
import ch.epfl.bluebrain.nexus.delta.sdk.model.IdSegment
import ch.epfl.bluebrain.nexus.delta.sdk.model.resolvers.ResolverRejection.ResolverNotFound
import ch.epfl.bluebrain.nexus.delta.sdk.model.resolvers.ResolverValue.InProjectValue
import ch.epfl.bluebrain.nexus.delta.sdk.model.resolvers.{Priority, ResolverContextResolution, ResourceResolutionReport}
import ch.epfl.bluebrain.nexus.delta.sdk.projects.model.ApiMappings
import ch.epfl.bluebrain.nexus.delta.sdk.syntax._
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.{Subject, User}
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Label
import ch.epfl.bluebrain.nexus.testkit.{IOFixedClock, IOValues, TestHelpers}
import monix.bio.IO
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

import java.util.UUID
import scala.annotation.nowarn

class ResolverScopeInitializationSpec
    extends AnyWordSpecLike
    with Matchers
    with IOValues
    with IOFixedClock
    with TestHelpers {

  private val defaultInProjectResolverId: IdSegment = nxv.defaultResolver

  private val uuid                        = UUID.randomUUID()
  private val saRealm: Label              = Label.unsafe("service-accounts")
  private val usersRealm: Label           = Label.unsafe("users")
  implicit private val sa: ServiceAccount = ServiceAccount(User("nexus-sa", saRealm))
  implicit private val bob: Subject       = User("bob", usersRealm)

  private val am       = ApiMappings("nxv" -> nxv.base, "Person" -> schema.Person)
  private val projBase = nxv.base
  private val project  =
    ProjectGen.project("org", "project", uuid = uuid, orgUuid = uuid, base = projBase, mappings = am)

  @nowarn("cat=unused")
  val resolvers: Resolvers = {
    implicit val api: JsonLdApi = JsonLdJavaApi.strict
    val resolution              = RemoteContextResolution.fixed(
      contexts.resolvers -> jsonContentOf("/contexts/resolvers.json").topContextValueOrEmpty
    )
    val rcr                     = new ResolverContextResolution(resolution, (_, _, _) => IO.raiseError(ResourceResolutionReport()))
    null
  }
  "A ResolverScopeInitialization" should {
    val init = new ResolverScopeInitialization(resolvers, sa)

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
