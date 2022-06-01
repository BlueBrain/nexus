package ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch

import ch.epfl.bluebrain.nexus.delta.kernel.utils.UUIDF
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.model.ElasticSearchView.{AggregateElasticSearchView, IndexingElasticSearchView}
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.model.ElasticSearchViewRejection.ViewNotFound
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.model.permissions.{query => queryPermissions}
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.model.{defaultElasticsearchMapping, defaultElasticsearchSettings, defaultViewId}
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.{nxv, schema => schemaorg}
import ch.epfl.bluebrain.nexus.delta.sdk.generators.ProjectGen
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Identity.{Subject, User}
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.ServiceAccount
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.ApiMappings
import ch.epfl.bluebrain.nexus.delta.sdk.model.BaseUri
import ch.epfl.bluebrain.nexus.delta.sdk.testkit.{AbstractDBSpec, ConfigFixtures}
import ch.epfl.bluebrain.nexus.delta.sdk.views.pipe.{DefaultLabelPredicates, SourceAsText}
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Label
import ch.epfl.bluebrain.nexus.testkit.{IOValues, TestHelpers}
import monix.execution.Scheduler
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike
import org.scalatest.{Inspectors, OptionValues}

import java.util.UUID

class ElasticSearchScopeInitializationSpec
    extends AbstractDBSpec
    with AnyWordSpecLike
    with Matchers
    with Inspectors
    with IOValues
    with OptionValues
    with TestHelpers
    with ConfigFixtures
    with Fixtures {

  private val uuid                   = UUID.randomUUID()
  implicit private val uuidF: UUIDF  = UUIDF.fixed(uuid)
  implicit private val sc: Scheduler = Scheduler.global

  private val saRealm: Label              = Label.unsafe("service-accounts")
  private val usersRealm: Label           = Label.unsafe("users")
  implicit private val sa: ServiceAccount = ServiceAccount(User("nexus-sa", saRealm))
  implicit private val bob: Subject       = User("bob", usersRealm)

  private val org      = Label.unsafe("org")
  private val am       = ApiMappings("nxv" -> nxv.base, "Person" -> schemaorg.Person)
  private val projBase = nxv.base
  private val project  =
    ProjectGen.project("org", "project", uuid = uuid, orgUuid = uuid, base = projBase, mappings = am)

  private val mapping           = defaultElasticsearchMapping.accepted
  private val settings          = defaultElasticsearchSettings.accepted
  implicit val baseUri: BaseUri = BaseUri.withoutPrefix("http://localhost")

  private val views: ElasticSearchViews =
    ElasticSearchViewsSetup.init(org, project, queryPermissions)

  "An ElasticSearchScopeInitialization" should {
    val init = new ElasticSearchScopeInitialization(views, sa)

    "create a default ElasticSearchView on a newly created project" in {
      views.fetch(defaultViewId, project.ref).rejectedWith[ViewNotFound]
      init.onProjectCreation(project, bob).accepted
      val resource = views.fetch(defaultViewId, project.ref).accepted
      resource.value match {
        case v: IndexingElasticSearchView  =>
          v.resourceTag shouldEqual None
          v.pipeline shouldEqual List(DefaultLabelPredicates(), SourceAsText())
          v.mapping shouldEqual mapping
          v.settings shouldEqual settings
          v.permission shouldEqual queryPermissions
        case _: AggregateElasticSearchView => fail("Expected an IndexingElasticSearchView to be created")
      }
      resource.rev shouldEqual 1L
      resource.createdBy shouldEqual sa.caller.subject
    }

    "not create a default ElasticSearchView if one already exists" in {
      views.fetch(defaultViewId, project.ref).accepted.rev shouldEqual 1L
      init.onProjectCreation(project, bob).accepted
      views.fetch(defaultViewId, project.ref).accepted.rev shouldEqual 1L
    }

  }
}
