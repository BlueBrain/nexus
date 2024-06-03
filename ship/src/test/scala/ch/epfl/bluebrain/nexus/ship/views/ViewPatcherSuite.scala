package ch.epfl.bluebrain.nexus.ship.views

import cats.data.NonEmptySet
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.model.ElasticSearchViewValue
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.model.ElasticSearchViewValue.AggregateElasticSearchViewValue
import ch.epfl.bluebrain.nexus.delta.rdf.syntax.iriStringContextSyntax
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.model.ElasticSearchViewValue.Database._
import ch.epfl.bluebrain.nexus.delta.sdk.views.ViewRef
import ch.epfl.bluebrain.nexus.delta.sourcing.model.ProjectRef
import ch.epfl.bluebrain.nexus.ship.{IriPatcher, ProjectMapper}
import ch.epfl.bluebrain.nexus.testkit.mu.NexusSuite
import io.circe.syntax.EncoderOps

class ViewPatcherSuite extends NexusSuite {

  private val project1 = ProjectRef.unsafe("org", "project")
  private val viewId1  = iri"https://bbp.epfl.ch/view1"
  private val view1    = ViewRef(project1, viewId1)

  private val project2 = ProjectRef.unsafe("org", "project2")
  private val viewId2  = iri"https://another-view-prefix/view2"
  private val view2    = ViewRef(project2, viewId2)

  private val aggregateView: ElasticSearchViewValue =
    AggregateElasticSearchViewValue(None, None, NonEmptySet.of(view1, view2))

  private val originalPrefix = iri"https://bbp.epfl.ch/"
  private val targetPrefix   = iri"https://openbrainplatform.com/"

  private val targetProject = ProjectRef.unsafe("new-org", "new-project2")

  private val iriPatcher    = IriPatcher(originalPrefix, targetPrefix, Map.empty)
  private val projectMapper = ProjectMapper(Map(project2 -> targetProject))
  private val viewPatcher   = new ViewPatcher(projectMapper, iriPatcher)

  test("Patch the aggregate view") {
    val viewAsJson         = aggregateView.asJson
    val expectedView1      = ViewRef(project1, iri"https://openbrainplatform.com/view1")
    val expectedView2      = ViewRef(targetProject, viewId2)
    val expectedAggregated = AggregateElasticSearchViewValue(None, None, NonEmptySet.of(expectedView1, expectedView2))
    val result             = viewPatcher.patchAggregateViewSource(viewAsJson).as[ElasticSearchViewValue]
    assertEquals(result, Right(expectedAggregated))
  }

}
