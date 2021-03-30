package ch.epfl.bluebrain.nexus.delta.plugins.compositeviews

import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.model.CompositeView.Interval
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.model.CompositeViewValue
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.nxv
import ch.epfl.bluebrain.nexus.delta.rdf.syntax.iriStringContextSyntax
import ch.epfl.bluebrain.nexus.delta.sdk.generators.ProjectGen
import ch.epfl.bluebrain.nexus.delta.sdk.model.NonEmptySet
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.ApiMappings
import ch.epfl.bluebrain.nexus.testkit.{IOFixedClock, IOValues, TestHelpers}
import org.scalatest.Inspectors
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

import java.util.UUID
import scala.concurrent.duration.DurationInt

class CompositeViewValueSpec
    extends AnyWordSpecLike
    with Matchers
    with Inspectors
    with IOFixedClock
    with IOValues
    with TestHelpers
    with CompositeViewsFixture {

  "Composite views value" should {
    val apiMappings  = ApiMappings("nxv" -> nxv.base)
    val base         = nxv.base
    val project      = ProjectGen.project("org", "proj", base = base, mappings = apiMappings)
    val previousUuid = UUID.randomUUID()

    "keep existing uuid and id when it's defined" in {
      val expected = CompositeViewValue(
        NonEmptySet.of(
          projectSource.copy(uuid = previousUuid),
          crossProjectSource.copy(uuid = previousUuid),
          remoteProjectSource.copy(uuid = previousUuid)
        ),
        NonEmptySet.of(esProjection.copy(uuid = previousUuid), blazegraphProjection.copy(uuid = previousUuid)),
        Interval(1.minute)
      )

      CompositeViewValue(
        viewFields,
        Map(
          iri"http://example.com/project-source"        -> previousUuid,
          iri"http://example.com/cross-project-source"  -> previousUuid,
          iri"http://example.com/remote-project-source" -> previousUuid
        ),
        Map(
          iri"http://example.com/es-projection"         -> previousUuid,
          iri"http://example.com/blazegraph-projection" -> previousUuid
        ),
        project.base
      ).accepted shouldEqual expected

    }

    "generate new uuid when it's not defined" in {
      CompositeViewValue(viewFields, Map.empty, Map.empty, project.base).accepted shouldEqual viewValue
    }

  }

}
