package ch.epfl.bluebrain.nexus.kg.indexing

import java.util.regex.Pattern.quote

import ch.epfl.bluebrain.nexus.commons.test.Resources
import ch.epfl.bluebrain.nexus.kg.TestHelper
import ch.epfl.bluebrain.nexus.kg.config.KgConfig
import ch.epfl.bluebrain.nexus.kg.config.Vocabulary.nxv
import ch.epfl.bluebrain.nexus.kg.indexing.Statistics.{CompositeViewStatistics, ViewStatistics}
import ch.epfl.bluebrain.nexus.rdf.implicits._
import ch.epfl.bluebrain.nexus.commons.circe.syntax._
import io.circe.Printer
import io.circe.syntax._
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike
import org.scalatest.{Inspectors, OptionValues}

class StatisticsSpec
    extends AnyWordSpecLike
    with Matchers
    with OptionValues
    with TestHelper
    with Resources
    with Inspectors {

  "Statistics" should {
    val sourceId              = genIri
    val projectionId          = nxv.defaultElasticSearchIndex.value
    val single: Statistics    = ViewStatistics(10L, 1L, 2L, 12L, None, None, None)
    val singleJson            = jsonContentOf("/view/statistics.json").removeKeys("projectionId")
    val composite: Statistics =
      CompositeViewStatistics(IdentifiedProgress(sourceId, projectionId, single.asInstanceOf[ViewStatistics]))
    val compositeJson         = jsonContentOf("/view/composite_statistics.json", Map(quote("{sourceId}") -> sourceId.asString))
    val printer: Printer      = Printer.noSpaces.copy(dropNullValues = true)

    "be encoded" in {
      forAll(List(single -> singleJson, composite -> compositeJson)) {
        case (model, json) =>
          printer.print(model.asJson.sortKeys(KgConfig.orderedKeys)) shouldEqual printer.print(json)
      }
    }
  }

}
