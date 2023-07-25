package ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.query

import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.model.ElasticSearchViewValue.IndexingElasticSearchViewValue
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.model.{defaultViewId, permissions, ElasticSearchViewState}
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.{ElasticSearchViewGen, ElasticSearchViews}
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.nxv
import ch.epfl.bluebrain.nexus.delta.sdk.views.View.IndexingView
import ch.epfl.bluebrain.nexus.delta.sdk.views.ViewRef
import ch.epfl.bluebrain.nexus.delta.sourcing.Scope
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{Label, ProjectRef, Tag}
import ch.epfl.bluebrain.nexus.delta.sourcing.state.ScopedStateStoreFixture
import ch.epfl.bluebrain.nexus.testkit.bio.{BioSuite, ResourceFixture}
import munit.AnyFixture

import java.util.UUID

class DefaultViewsStoreSuite extends BioSuite {

  override def munitFixtures: Seq[AnyFixture[_]] = List(defaultViewsStore)

  private val org  = Label.unsafe("org")
  private val org2 = Label.unsafe("org2")

  private val project1         = ProjectRef(org, Label.unsafe("proj1"))
  private val defaultView1     = ViewRef(project1, defaultViewId)
  private val defaultView1Uuid = UUID.randomUUID()

  private val project2         = ProjectRef(org, Label.unsafe("proj2"))
  private val defaultView2     = ViewRef(project2, defaultViewId)
  private val defaultView2Uuid = UUID.randomUUID()

  private val project3         = ProjectRef(org2, Label.unsafe("proj3"))
  private val defaultView3     = ViewRef(project3, defaultViewId)
  private val defaultView3Uuid = UUID.randomUUID()

  private val project4              = ProjectRef(org, Label.unsafe("proj4"))
  private val deprecatedDefaultView = ViewRef(project4, defaultViewId)
  private val customView            = ViewRef(project4, nxv + "my-view")

  private val elasticsearchPrefix = "test"

  def createView(ref: ViewRef, uuid: UUID = UUID.randomUUID(), deprecated: Boolean = false): ElasticSearchViewState =
    ElasticSearchViewGen.stateFor(
      ref.viewId,
      ref.project,
      IndexingElasticSearchViewValue(None, None),
      uuid = uuid,
      deprecated = deprecated
    )

  val defaultViewsStore: ResourceFixture.TaskFixture[DefaultViewsStore] = {

    val tag = Tag.UserTag.unsafe("my-tag")

    val views = List(
      createView(defaultView1, defaultView1Uuid),
      createView(defaultView2, defaultView2Uuid),
      createView(defaultView3, defaultView3Uuid),
      createView(deprecatedDefaultView, deprecated = true),
      createView(customView)
    )

    ResourceFixture.suiteLocal(
      "viewsStore",
      ScopedStateStoreFixture
        .store(
          ElasticSearchViews.entityType,
          ElasticSearchViewState.serializer
        )(saveLatest = views, saveTagged = views.map(tag -> _))
        .map { case (xas, _) =>
          DefaultViewsStore(elasticsearchPrefix, xas)
        }
    )
  }

  private lazy val viewStore = defaultViewsStore()

  private def findDefaultRefs(predicate: Scope) =
    viewStore.find(predicate).map(_.map(_.ref))

  test("Construct indexing view correctly") {
    assertEquals(
      DefaultViewsStore.asIndexingView(
        elasticsearchPrefix,
        createView(defaultView1, defaultView1Uuid)
      ),
      IndexingView(defaultView1, s"test_${defaultView1Uuid}_1", permissions.read)
    )
  }

  test(s"Get non-deprecated default views in '$project1'") {
    findDefaultRefs(Scope.Project(project1)).assert(List(defaultView1))
  }

  test(s"Get non-deprecated default views in '$org'") {
    findDefaultRefs(Scope.Org(org)).assert(List(defaultView1, defaultView2))
  }

  test(s"Get non-deprecated in all orgs") {
    findDefaultRefs(Scope.Root).assert(List(defaultView1, defaultView2, defaultView3))
  }
}
