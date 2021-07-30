package ch.epfl.bluebrain.nexus.delta.plugins.search.model

import ch.epfl.bluebrain.nexus.delta.plugins.search.model.SearchConfigError.{InvalidJsonError, InvalidSparqlConstructQuery, LoadingFileError}
import ch.epfl.bluebrain.nexus.testkit.IOValues
import com.typesafe.config.ConfigFactory
import org.scalatest.Inspectors
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

class SearchConfigSpec extends AnyWordSpecLike with Matchers with Inspectors with IOValues {

  private def getAbsolutePath(path: String) = getClass.getResource(path).getPath

  private val validJson     = getAbsolutePath("/empty-object.json")
  private val emptyFile     = getAbsolutePath("/empty.txt")
  private val validQuery    = getAbsolutePath("/construct-query.sparql")
  private val resourceTypes = getAbsolutePath("/resource-types.json")
  private val missingFile   = "/path/to/nowhere"

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
        |  }
        |}
        |""".stripMargin
    )

  "Search config" should {
    "load correctly if all is well defined" in {
      SearchConfig
        .load(
          config(
            validJson,
            validJson,
            Some(validJson),
            validQuery,
            Some(validJson)
          )
        )
        .accepted
    }

    "fail if the file can't be found" in {
      forAll(
        List(
          config(
            missingFile,
            validJson,
            Some(validJson),
            validQuery,
            Some(validJson)
          ),
          config(
            validJson,
            missingFile,
            Some(validJson),
            validQuery,
            Some(validJson)
          ),
          config(
            validJson,
            validJson,
            Some(missingFile),
            validQuery,
            Some(validJson)
          ),
          config(
            validJson,
            validJson,
            Some(validJson),
            missingFile,
            Some(validJson)
          ),
          config(
            validJson,
            validJson,
            Some(validJson),
            validQuery,
            Some(missingFile)
          )
        )
      ) { c =>
        SearchConfig.load(c).rejectedWith[LoadingFileError]
      }
    }

    "fail if fields is an invalid json object is passed" in {
      forAll(
        List(
          config(
            emptyFile,
            validJson,
            Some(validJson),
            validQuery,
            Some(validJson)
          ),
          config(
            validJson,
            emptyFile,
            Some(validJson),
            validQuery,
            Some(validJson)
          ),
          config(
            validJson,
            validJson,
            Some(emptyFile),
            validQuery,
            Some(validJson)
          ),
          config(
            validJson,
            validJson,
            Some(validJson),
            validQuery,
            Some(emptyFile)
          )
        )
      ) { c =>
        SearchConfig.load(c).rejectedWith[InvalidJsonError]
      }
    }

    "fail if the construct query is invalid" in {
      SearchConfig
        .load(
          config(
            validJson,
            validJson,
            Some(validJson),
            emptyFile,
            Some(validJson)
          )
        )
        .rejectedWith[InvalidSparqlConstructQuery]
    }
  }

}
