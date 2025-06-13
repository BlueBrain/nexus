package ai.senscience.nexus.delta.plugins.search.model

import ai.senscience.nexus.delta.plugins.search.model.SearchConfigError.InvalidSparqlConstructQuery
import ch.epfl.bluebrain.nexus.delta.kernel.error.LoadFileError.{InvalidJson, UnaccessibleFile}
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.model.CompositeView.Interval
import ch.epfl.bluebrain.nexus.delta.sdk.Defaults
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{Label, ProjectRef}
import ch.epfl.bluebrain.nexus.testkit.scalatest.ce.CatsEffectSpec
import com.typesafe.config.ConfigFactory

import scala.concurrent.duration.DurationInt

class SearchConfigSpec extends CatsEffectSpec {

  private def getAbsolutePath(path: String) = getClass.getResource(path).getPath

  private val validJson     = getAbsolutePath("/empty-object.json")
  private val emptyFile     = getAbsolutePath("/empty.txt")
  private val validQuery    = getAbsolutePath("/construct-query.sparql")
  private val resourceTypes = getAbsolutePath("/resource-types.json")
  private val missingFile   = "/path/to/nowhere"
  private val defaults      = Defaults("name", "description")

  private def config(
      fields: String,
      mappings: String,
      settings: Option[String],
      query: String,
      context: Option[String]
  ) =
    ConfigFactory.parseString(
      s"""
        |plugins.search {
        |  fields = $fields
        |
        |  indexing {
        |    mapping = $mappings
        |    settings = ${settings.orNull}
        |    query = $query
        |    context = ${context.orNull}
        |    resource-types = $resourceTypes
        |    rebuild-strategy = 2 minutes
        |    min-interval-rebuild = 1 minute
        |  }
        |
        |  defaults {
        |    name = ${defaults.name}
        |    description = ${defaults.description}
        |  }
        |
        |  suites {
        |    my-suite   = [ "myorg/myproject", "myorg/myproject2" ]
        |    my-suite-2 = [ "myorg2/myproject" ]
        |  }
        |
        |}
        |""".stripMargin
    )

  "Search config" should {
    "load correctly if all is well defined" in {
      val hocon        = config(validJson, validJson, Some(validJson), validQuery, Some(validJson))
      val searchConfig = SearchConfig.load(hocon).accepted

      val expectedSuites = Map(
        Label
          .unsafe("my-suite")      -> Set(ProjectRef.unsafe("myorg", "myproject"), ProjectRef.unsafe("myorg", "myproject2")),
        Label.unsafe("my-suite-2") -> Set(ProjectRef.unsafe("myorg2", "myproject"))
      )

      searchConfig.indexing.rebuildStrategy shouldEqual Some(Interval(2.minutes))
      searchConfig.suites shouldEqual expectedSuites
    }

    "fail if the file can't be found" in {
      val missingFieldFile   = config(missingFile, validJson, Some(validJson), validQuery, Some(validJson))
      val missingEsMapping   = config(validJson, missingFile, Some(validJson), validQuery, Some(validJson))
      val missingEsSettings  = config(validJson, validJson, Some(missingFile), validQuery, Some(validJson))
      val missingSparqlFile  = config(validJson, validJson, Some(validJson), missingFile, Some(validJson))
      val missingContextFile = config(validJson, validJson, Some(validJson), validQuery, Some(missingFile))
      val all                = List(missingFieldFile, missingEsMapping, missingEsSettings, missingSparqlFile, missingContextFile)
      forAll(all) { c =>
        SearchConfig.load(c).rejectedWith[UnaccessibleFile]
      }
    }

    "fail if fields is an invalid json object is passed" in {
      val invalidFields     = config(emptyFile, validJson, Some(validJson), validQuery, Some(validJson))
      val invalidEsMapping  = config(validJson, emptyFile, Some(validJson), validQuery, Some(validJson))
      val invalidEsSettings = config(validJson, validJson, Some(emptyFile), validQuery, Some(validJson))
      val invalidContext    = config(validJson, validJson, Some(validJson), validQuery, Some(emptyFile))
      val all               = List(invalidFields, invalidEsMapping, invalidEsSettings, invalidContext)
      forAll(all) { c =>
        SearchConfig.load(c).rejectedWith[InvalidJson]
      }
    }

    "fail if the construct query is invalid" in {
      val invalidQuery = config(validJson, validJson, Some(validJson), emptyFile, Some(validJson))
      SearchConfig.load(invalidQuery).rejectedWith[InvalidSparqlConstructQuery]
    }
  }

}
