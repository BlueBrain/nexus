package ch.epfl.bluebrain.nexus.rdf.jsonld

import ch.epfl.bluebrain.nexus.rdf.RdfSpec._
import ch.epfl.bluebrain.nexus.rdf.jsonld
import com.github.jsonldjava.core.JsonLdOptions
import io.circe.Json
import io.circe.parser.parse
import org.apache.jena.query.DatasetFactory
import org.apache.jena.rdf.model.{Model, ModelFactory}
import org.apache.jena.riot._
import org.apache.jena.riot.system.StreamRDFLib
import org.openjdk.jmh.annotations._
import org.scalatest.OptionValues

import scala.util.Try

@State(Scope.Thread)
class JsonLDBenchmark extends OptionValues {

  private val tests = List(
    "/jsonld/expand/0001-in.jsonld",
    "/jsonld/expand/0002-in.jsonld",
    "/jsonld/expand/0003-in.jsonld",
    "/jsonld/expand/0004-in.jsonld",
    "/jsonld/expand/0006-in.jsonld",
    "/jsonld/expand/0007-in.jsonld",
    "/jsonld/expand/0008-in.jsonld",
    "/jsonld/expand/0009-in.jsonld",
    "/jsonld/expand/0010-in.jsonld",
    "/jsonld/expand/0011-in.jsonld",
    "/jsonld/expand/0012-in.jsonld",
    "/jsonld/expand/0014-in.jsonld",
    "/jsonld/expand/0015-in.jsonld",
    "/jsonld/expand/0016-in.jsonld",
    "/jsonld/expand/0017-in.jsonld",
    "/jsonld/expand/0018-in.jsonld",
    "/jsonld/expand/0019-in.jsonld",
    "/jsonld/expand/0020-in.jsonld",
    "/jsonld/expand/0021-in.jsonld",
    "/jsonld/expand/0022-in.jsonld",
    "/jsonld/expand/0023-in.jsonld",
    "/jsonld/expand/0024-in.jsonld",
    "/jsonld/expand/0025-in.jsonld",
    "/jsonld/expand/0026-in.jsonld",
    "/jsonld/expand/0027-in.jsonld",
    "/jsonld/expand/0030-in.jsonld",
    "/jsonld/expand/0031-in.jsonld",
    "/jsonld/expand/0033-in.jsonld",
    "/jsonld/expand/0034-in.jsonld",
    "/jsonld/expand/0035-in.jsonld",
    "/jsonld/expand/0036-in.jsonld",
    "/jsonld/expand/0037-in.jsonld",
    "/jsonld/expand/0039-in.jsonld",
    "/jsonld/expand/0041-in.jsonld",
    "/jsonld/expand/0042-in.jsonld",
    "/jsonld/expand/0043-in.jsonld",
    "/jsonld/expand/0044-in.jsonld",
    "/jsonld/expand/0045-in.jsonld",
    "/jsonld/expand/0046-in.jsonld",
    "/jsonld/expand/0047-in.jsonld",
    "/jsonld/expand/0049-in.jsonld",
    "/jsonld/expand/0052-in.jsonld",
    "/jsonld/expand/0053-in.jsonld",
    "/jsonld/expand/0054-in.jsonld",
    "/jsonld/expand/0055-in.jsonld",
    "/jsonld/expand/0058-in.jsonld",
    "/jsonld/expand/0062-in.jsonld",
    "/jsonld/expand/0063-in.jsonld",
    "/jsonld/expand/0064-in.jsonld",
    "/jsonld/expand/0065-in.jsonld",
    "/jsonld/expand/0067-in.jsonld",
    "/jsonld/expand/0069-in.jsonld",
    "/jsonld/expand/0070-in.jsonld",
    "/jsonld/expand/0071-in.jsonld",
    "/jsonld/expand/0072-in.jsonld",
    "/jsonld/expand/0073-in.jsonld",
    "/jsonld/expand/0074-in.jsonld",
    "/jsonld/expand/0088-in.jsonld",
    "/jsonld/expand/0109-in.jsonld",
    "/jsonld/expand/0113-in.jsonld",
    "/jsonld/expand/0118-in.jsonld",
    "/jsonld/expand/0119-in.jsonld",
    "/jsonld/expand/0120-in.jsonld",
    "/jsonld/expand/0121-in.jsonld",
    "/jsonld/expand/0124-in.jsonld",
    "/jsonld/expand/c001-in.jsonld",
    "/jsonld/expand/c002-in.jsonld",
    "/jsonld/expand/c003-in.jsonld",
    "/jsonld/expand/c004-in.jsonld",
    "/jsonld/expand/c006-in.jsonld",
    "/jsonld/expand/c007-in.jsonld",
    "/jsonld/expand/c008-in.jsonld",
    "/jsonld/expand/c009-in.jsonld",
    "/jsonld/expand/c010-in.jsonld",
    "/jsonld/expand/c012-in.jsonld",
    "/jsonld/expand/c014-in.jsonld",
    "/jsonld/expand/c015-in.jsonld",
    "/jsonld/expand/c016-in.jsonld",
    "/jsonld/expand/c017-in.jsonld",
    "/jsonld/expand/c018-in.jsonld",
    "/jsonld/expand/c019-in.jsonld",
    "/jsonld/expand/c020-in.jsonld",
    "/jsonld/expand/c021-in.jsonld",
    "/jsonld/expand/c022-in.jsonld",
    "/jsonld/expand/c023-in.jsonld",
    "/jsonld/expand/c024-in.jsonld",
    "/jsonld/expand/c025-in.jsonld",
    "/jsonld/expand/c026-in.jsonld",
    "/jsonld/expand/c027-in.jsonld",
    "/jsonld/expand/di03-in.jsonld",
    "/jsonld/expand/di05-in.jsonld",
    "/jsonld/expand/in01-in.jsonld",
    "/jsonld/expand/in02-in.jsonld",
    "/jsonld/expand/in03-in.jsonld",
    "/jsonld/expand/in04-in.jsonld",
    "/jsonld/expand/in05-in.jsonld",
    "/jsonld/expand/li06-in.jsonld",
    "/jsonld/expand/li08-in.jsonld",
    "/jsonld/expand/m009-in.jsonld",
    "/jsonld/expand/p001-in.jsonld",
    "/jsonld/expand/p002-in.jsonld",
    "/jsonld/expand/p003-in.jsonld",
    "/jsonld/expand/p004-in.jsonld"
  ).map(name => name -> jsonContentOf(name))

  private val testNoVersion = tests.map { case (name, json) => name -> json.removeNestedKeys(keyword.version) }

  private def parseModel(json: Json): Option[Model] = {
    Try {
      val model  = ModelFactory.createDefaultModel()
      val stream = StreamRDFLib.graph(model.getGraph)
      RDFParser.create.fromString(json.noSpaces).lang(Lang.JSONLD).parse(stream)
      model
    }.toOption
  }

  private def writeJsonLD(model: Model): Option[Json] = {
    val opts = new JsonLdOptions()
    opts.setEmbed(true)
    opts.setProcessingMode(JsonLdOptions.JSON_LD_1_1)
    opts.setCompactArrays(true)
    opts.setPruneBlankNodeIdentifiers(true)
    val ctx = new JsonLDWriteContext
    ctx.setOptions(opts)
    Try {
      val g = DatasetFactory.wrap(model).asDatasetGraph
      RDFWriter.create().format(RDFFormat.JSONLD_EXPAND_FLAT).source(g).context(ctx).build().asString()
    }.toOption.flatMap(str => parse(str).toOption)
  }

  implicit private val options: jsonld.JsonLdOptions = jsonld.JsonLdOptions.empty

  @Benchmark
  def toExpandedJena(): Unit =
    tests.foreach {
      case (_, json) =>
        val expanded = JsonLd.expand(json).toOption.value
        expanded.toJson()
    }

  @Benchmark
  def toExpanded(): Unit =
    testNoVersion.foreach {
      case (_, json) =>
        val model = parseModel(json).value
        writeJsonLD(model).value

    }

}
