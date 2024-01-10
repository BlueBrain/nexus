package ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch

import ch.epfl.bluebrain.nexus.delta.kernel.utils.UUIDF
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.model.ElasticSearchView.{AggregateElasticSearchView, IndexingElasticSearchView}
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.model.ElasticSearchViewRejection.{ProjectContextRejection, ViewNotFound}
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.model.permissions.{query => queryPermissions}
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.model.{defaultViewId, ElasticSearchViewRejection}
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.{nxv, schema => schemaorg}
import ch.epfl.bluebrain.nexus.delta.sdk.generators.ProjectGen
import ch.epfl.bluebrain.nexus.delta.sdk.identities.model.ServiceAccount
import ch.epfl.bluebrain.nexus.delta.sdk.projects.FetchContextDummy
import ch.epfl.bluebrain.nexus.delta.sdk.projects.model.ApiMappings
import ch.epfl.bluebrain.nexus.delta.sdk.resolvers.ResolverContextResolution
import ch.epfl.bluebrain.nexus.delta.sdk.views.PipeStep
import ch.epfl.bluebrain.nexus.delta.sdk.{ConfigFixtures, Defaults}
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.{Subject, User}
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Label
import ch.epfl.bluebrain.nexus.delta.sourcing.postgres.DoobieScalaTestFixture
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.pipes.{DefaultLabelPredicates, SourceAsText}
import ch.epfl.bluebrain.nexus.testkit.scalatest.ce.CatsEffectSpec

import java.util.UUID

class ElasticSearchScopeInitializationSpec
    extends CatsEffectSpec
    with DoobieScalaTestFixture
    with ConfigFixtures
    with Fixtures {

  private val uuid                  = UUID.randomUUID()
  implicit private val uuidF: UUIDF = UUIDF.fixed(uuid)

  private val saRealm: Label              = Label.unsafe("service-accounts")
  private val usersRealm: Label           = Label.unsafe("users")
  implicit private val sa: ServiceAccount = ServiceAccount(User("nexus-sa", saRealm))
  implicit private val bob: Subject       = User("bob", usersRealm)

  private val am       = ApiMappings("nxv" -> nxv.base, "Person" -> schemaorg.Person)
  private val projBase = nxv.base
  private val project  =
    ProjectGen.project("org", "project", uuid = uuid, orgUuid = uuid, base = projBase, mappings = am)

  private val fetchContext = FetchContextDummy[ElasticSearchViewRejection](
    List(project),
    ProjectContextRejection
  )

  private lazy val views: ElasticSearchViews = ElasticSearchViews(
    fetchContext,
    ResolverContextResolution(rcr),
    ValidateElasticSearchView.always,
    eventLogConfig,
    "prefix",
    xas,
    defaultMapping,
    defaultSettings,
    clock
  ).accepted

  private val defaultName        = "defaultName"
  private val defaultDescription = "defaultDescription"

  "An ElasticSearchScopeInitialization" should {
    lazy val init = new ElasticSearchScopeInitialization(views, sa, Defaults(defaultName, defaultDescription))

    "create a default ElasticSearchView on a newly created project" in {
      views.fetch(defaultViewId, project.ref).rejectedWith[ViewNotFound]
      init.onProjectCreation(project.ref, bob).accepted
      val resource = views.fetch(defaultViewId, project.ref).accepted
      resource.value match {
        case v: IndexingElasticSearchView  =>
          v.resourceTag shouldEqual None
          v.pipeline shouldEqual List(
            PipeStep.noConfig(DefaultLabelPredicates.ref),
            PipeStep.noConfig(SourceAsText.ref)
          )
          v.mapping shouldEqual defaultMapping.value
          v.settings shouldEqual defaultSettings.value
          v.permission shouldEqual queryPermissions
          v.name shouldEqual Some(defaultName)
          v.description shouldEqual Some(defaultDescription)
        case _: AggregateElasticSearchView => fail("Expected an IndexingElasticSearchView to be created")
      }
      resource.rev shouldEqual 1L
      resource.createdBy shouldEqual sa.caller.subject
    }

    "not create a default ElasticSearchView if one already exists" in {
      views.fetch(defaultViewId, project.ref).accepted.rev shouldEqual 1L
      init.onProjectCreation(project.ref, bob).accepted
      views.fetch(defaultViewId, project.ref).accepted.rev shouldEqual 1L
    }

  }
}
