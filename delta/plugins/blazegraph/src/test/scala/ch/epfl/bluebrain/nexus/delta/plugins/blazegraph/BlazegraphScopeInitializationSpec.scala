package ch.epfl.bluebrain.nexus.delta.plugins.blazegraph

import cats.effect.IO
import ch.epfl.bluebrain.nexus.delta.kernel.utils.UUIDF
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.model.BlazegraphView.{AggregateBlazegraphView, IndexingBlazegraphView}
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.model.BlazegraphViewRejection.ViewNotFound
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.model.*
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.{nxv, schema as schemaorg}
import ch.epfl.bluebrain.nexus.delta.sdk.generators.ProjectGen
import ch.epfl.bluebrain.nexus.delta.sdk.identities.model.ServiceAccount
import ch.epfl.bluebrain.nexus.delta.sdk.projects.FetchContextDummy
import ch.epfl.bluebrain.nexus.delta.sdk.projects.model.ApiMappings
import ch.epfl.bluebrain.nexus.delta.sdk.resolvers.ResolverContextResolution
import ch.epfl.bluebrain.nexus.delta.sdk.{ConfigFixtures, Defaults}
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.{Subject, User}
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{IriFilter, Label}
import ch.epfl.bluebrain.nexus.delta.sourcing.postgres.DoobieScalaTestFixture
import ch.epfl.bluebrain.nexus.testkit.scalatest.ce.CatsEffectSpec

import java.util.UUID

class BlazegraphScopeInitializationSpec
    extends CatsEffectSpec
    with DoobieScalaTestFixture
    with ConfigFixtures
    with Fixtures {

  private val uuid                  = UUID.randomUUID()
  implicit private val uuidF: UUIDF = UUIDF.fixed(uuid)

  private val prefix = "prefix"

  private val saRealm: Label              = Label.unsafe("service-accounts")
  private val usersRealm: Label           = Label.unsafe("users")
  implicit private val sa: ServiceAccount = ServiceAccount(User("nexus-sa", saRealm))
  implicit private val bob: Subject       = User("bob", usersRealm)

  private val am       = ApiMappings("nxv" -> nxv.base, "Person" -> schemaorg.Person)
  private val projBase = nxv.base
  private val project  =
    ProjectGen.project("org", "project", uuid = uuid, orgUuid = uuid, base = projBase, mappings = am)

  private val fetchContext = FetchContextDummy(List(project))

  private lazy val views: BlazegraphViews = BlazegraphViews(
    fetchContext,
    ResolverContextResolution(rcr),
    alwaysValidate,
    _ => IO.unit,
    eventLogConfig,
    prefix,
    xas,
    clock
  ).accepted

  private val defaultViewName        = "defaultName"
  private val defaultViewDescription = "defaultDescription"
  private val defaults               = Defaults(defaultViewName, defaultViewDescription)

  "A BlazegraphScopeInitialization" should {
    lazy val init = new BlazegraphScopeInitialization(views, sa, defaults)

    "create a default SparqlView on newly created project" in {
      views.fetch(defaultViewId, project.ref).rejectedWith[ViewNotFound]
      init.onProjectCreation(project.ref, bob).accepted
      val resource = views.fetch(defaultViewId, project.ref).accepted
      resource.value match {
        case v: IndexingBlazegraphView  =>
          v.resourceSchemas shouldBe IriFilter.None
          v.resourceTypes shouldBe IriFilter.None
          v.resourceTag shouldEqual None
          v.includeDeprecated shouldEqual true
          v.includeMetadata shouldEqual true
          v.permission shouldEqual permissions.query
          v.name should contain(defaultViewName)
          v.description should contain(defaultViewDescription)
        case _: AggregateBlazegraphView => fail("Expected an IndexingBlazegraphView to be created")
      }
      resource.rev shouldEqual 1L
      resource.createdBy shouldEqual sa.caller.subject
    }

    "not create a default SparqlView if one already exists" in {
      views.fetch(defaultViewId, project.ref).accepted.rev shouldEqual 1L
      init.onProjectCreation(project.ref, bob).accepted
      views.fetch(defaultViewId, project.ref).accepted.rev shouldEqual 1L
    }
  }
}
