package ch.epfl.bluebrain.nexus.delta.service.utils

import ch.epfl.bluebrain.nexus.delta.kernel.utils.UUIDF
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.{contexts, nxv, schema}
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.RemoteContextResolution
import ch.epfl.bluebrain.nexus.delta.sdk.{QuotasDummy, Resolvers}
import ch.epfl.bluebrain.nexus.delta.sdk.generators.ProjectGen
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Identity.{Subject, User}
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.ServiceAccount
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.ApiMappings
import ch.epfl.bluebrain.nexus.delta.sdk.model.resolvers.ResolverRejection.ResolverNotFound
import ch.epfl.bluebrain.nexus.delta.sdk.model.resolvers.ResolverValue.InProjectValue
import ch.epfl.bluebrain.nexus.delta.sdk.model.resolvers.{Priority, ResolverContextResolution, ResourceResolutionReport}
import ch.epfl.bluebrain.nexus.delta.sdk.model.{BaseUri, IdSegment, Label}
import ch.epfl.bluebrain.nexus.delta.sdk.syntax._
import ch.epfl.bluebrain.nexus.delta.sdk.testkit._
import ch.epfl.bluebrain.nexus.testkit.{IOFixedClock, IOValues, TestHelpers}
import monix.bio.IO
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

import java.util.UUID

class ResolverScopeInitializationSpec
    extends AnyWordSpecLike
    with Matchers
    with IOValues
    with IOFixedClock
    with TestHelpers {

  private val defaultInProjectResolverId: IdSegment = nxv.defaultResolver

  private val uuid                  = UUID.randomUUID()
  implicit private val uuidF: UUIDF = UUIDF.fixed(uuid)

  private val saRealm: Label              = Label.unsafe("service-accounts")
  private val usersRealm: Label           = Label.unsafe("users")
  implicit private val sa: ServiceAccount = ServiceAccount(User("nexus-sa", saRealm))
  implicit private val bob: Subject       = User("bob", usersRealm)

  private val org      = Label.unsafe("org")
  private val am       = ApiMappings("nxv" -> nxv.base, "Person" -> schema.Person)
  private val projBase = nxv.base
  private val project  =
    ProjectGen.project("org", "project", uuid = uuid, orgUuid = uuid, base = projBase, mappings = am)

  val resolvers: Resolvers = {
    implicit val baseUri: BaseUri = BaseUri.withoutPrefix("http://localhost")

    val resolution = RemoteContextResolution.fixed(
      contexts.resolvers -> jsonContentOf("/contexts/resolvers.json").topContextValueOrEmpty
    )
    val rcr        = new ResolverContextResolution(resolution, (_, _, _) => IO.raiseError(ResourceResolutionReport()))
    val (o, p)     = ProjectSetup.init(List(org), List(project)).accepted
    ResolversDummy(o, p, rcr, (_, _) => IO.unit, QuotasDummy.neverReached).accepted
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
