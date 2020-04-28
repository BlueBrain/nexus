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
import ch.epfl.bluebrain.nexus.rdf.syntax.all._

import scala.util.Try

@State(Scope.Thread)
class JsonLDBenchmark extends OptionValues with FromJenaConverters {

  implicit private val options: jsonld.JsonLdOptions =
    jsonld.JsonLdOptions(base = Some(uri"https://w3c.github.io/json-ld-api/tests/id"))

  private val test100Expand = List(
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

  private val test100ExpandNoVersion =
    test100Expand.map { case (name, json) => name -> json.removeNestedKeys(keyword.version) }

  private val test100ToGraph = List(
    "/jsonld/toRdf/0001-in.jsonld",
    "/jsonld/toRdf/0007-in.jsonld",
    "/jsonld/toRdf/0008-in.jsonld",
    "/jsonld/toRdf/0010-in.jsonld",
    "/jsonld/toRdf/0011-in.jsonld",
    "/jsonld/toRdf/0012-in.jsonld",
    "/jsonld/toRdf/0013-in.jsonld",
    "/jsonld/toRdf/0019-in.jsonld",
    "/jsonld/toRdf/0020-in.jsonld",
    "/jsonld/toRdf/0022-in.jsonld",
    "/jsonld/toRdf/0023-in.jsonld",
    "/jsonld/toRdf/0024-in.jsonld",
    "/jsonld/toRdf/0025-in.jsonld",
    "/jsonld/toRdf/0026-in.jsonld",
    "/jsonld/toRdf/0130-in.jsonld",
    "/jsonld/toRdf/0131-in.jsonld",
    "/jsonld/toRdf/0132-in.jsonld",
    "/jsonld/toRdf/c001-in.jsonld",
    "/jsonld/toRdf/c002-in.jsonld",
    "/jsonld/toRdf/c003-in.jsonld",
    "/jsonld/toRdf/c004-in.jsonld",
    "/jsonld/toRdf/c005-in.jsonld",
    "/jsonld/toRdf/c006-in.jsonld",
    "/jsonld/toRdf/c007-in.jsonld",
    "/jsonld/toRdf/c008-in.jsonld",
    "/jsonld/toRdf/c009-in.jsonld",
    "/jsonld/toRdf/c010-in.jsonld",
    "/jsonld/toRdf/c011-in.jsonld",
    "/jsonld/toRdf/c012-in.jsonld",
    "/jsonld/toRdf/c013-in.jsonld",
    "/jsonld/toRdf/c014-in.jsonld",
    "/jsonld/toRdf/c015-in.jsonld",
    "/jsonld/toRdf/c016-in.jsonld",
    "/jsonld/toRdf/c017-in.jsonld",
    "/jsonld/toRdf/c018-in.jsonld",
    "/jsonld/toRdf/c019-in.jsonld",
    "/jsonld/toRdf/c020-in.jsonld",
    "/jsonld/toRdf/c021-in.jsonld",
    "/jsonld/toRdf/c022-in.jsonld",
    "/jsonld/toRdf/c023-in.jsonld",
    "/jsonld/toRdf/c024-in.jsonld",
    "/jsonld/toRdf/c026-in.jsonld",
    "/jsonld/toRdf/c027-in.jsonld",
    "/jsonld/toRdf/c035-in.jsonld",
    "/jsonld/toRdf/di03-in.jsonld",
    "/jsonld/toRdf/di05-in.jsonld",
    "/jsonld/toRdf/e001-in.jsonld",
    "/jsonld/toRdf/e002-in.jsonld",
    "/jsonld/toRdf/e003-in.jsonld",
    "/jsonld/toRdf/e004-in.jsonld",
    "/jsonld/toRdf/e005-in.jsonld",
    "/jsonld/toRdf/e006-in.jsonld",
    "/jsonld/toRdf/e007-in.jsonld",
    "/jsonld/toRdf/e008-in.jsonld",
    "/jsonld/toRdf/e009-in.jsonld",
    "/jsonld/toRdf/e010-in.jsonld",
    "/jsonld/toRdf/e011-in.jsonld",
    "/jsonld/toRdf/e012-in.jsonld",
    "/jsonld/toRdf/e013-in.jsonld",
    "/jsonld/toRdf/e014-in.jsonld",
    "/jsonld/toRdf/e015-in.jsonld",
    "/jsonld/toRdf/e016-in.jsonld",
    "/jsonld/toRdf/e017-in.jsonld",
    "/jsonld/toRdf/e018-in.jsonld",
    "/jsonld/toRdf/e019-in.jsonld",
    "/jsonld/toRdf/e022-in.jsonld",
    "/jsonld/toRdf/e023-in.jsonld",
    "/jsonld/toRdf/e024-in.jsonld",
    "/jsonld/toRdf/e025-in.jsonld",
    "/jsonld/toRdf/e026-in.jsonld",
    "/jsonld/toRdf/e027-in.jsonld",
    "/jsonld/toRdf/e028-in.jsonld",
    "/jsonld/toRdf/e029-in.jsonld",
    "/jsonld/toRdf/e030-in.jsonld",
    "/jsonld/toRdf/e031-in.jsonld",
    "/jsonld/toRdf/e032-in.jsonld",
    "/jsonld/toRdf/e033-in.jsonld",
    "/jsonld/toRdf/e034-in.jsonld",
    "/jsonld/toRdf/e035-in.jsonld",
    "/jsonld/toRdf/e036-in.jsonld",
    "/jsonld/toRdf/e037-in.jsonld",
    "/jsonld/toRdf/e039-in.jsonld",
    "/jsonld/toRdf/e040-in.jsonld",
    "/jsonld/toRdf/e041-in.jsonld",
    "/jsonld/toRdf/e042-in.jsonld",
    "/jsonld/toRdf/e065-in.jsonld",
    "/jsonld/toRdf/e066-in.jsonld",
    "/jsonld/toRdf/e067-in.jsonld",
    "/jsonld/toRdf/e069-in.jsonld",
    "/jsonld/toRdf/e070-in.jsonld",
    "/jsonld/toRdf/e071-in.jsonld",
    "/jsonld/toRdf/e072-in.jsonld",
    "/jsonld/toRdf/e073-in.jsonld",
    "/jsonld/toRdf/e074-in.jsonld",
    "/jsonld/toRdf/li06-in.jsonld",
    "/jsonld/toRdf/li08-in.jsonld",
    "/jsonld/toRdf/p001-in.jsonld",
    "/jsonld/toRdf/p002-in.jsonld",
    "/jsonld/toRdf/p003-in.jsonld",
    "/jsonld/toRdf/p004-in.jsonld"
  ).map(name => name -> jsonContentOf(name))

  private val test100ToGrphNoVersion =
    test100ToGraph.map {
      case (name, json) => (name, JsonLd(json).toOption.value.node.subject, json.removeNestedKeys(keyword.version))
    }

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

  @Benchmark
  def toExpanded100(): Unit =
    test100Expand.foreach {
      case (_, json) =>
        val jsonLd = JsonLd(json).toOption.value
        jsonLd.expanded
    }

  @Benchmark
  def toExpandedJena100(): Unit =
    test100ExpandNoVersion.foreach {
      case (_, json) =>
        val model = parseModel(json).value
        writeJsonLD(model).value
    }

  @Benchmark
  def toGraph100(): Unit =
    test100ToGraph.foreach {
      case (_, json) =>
        val jsonLd = JsonLd(json).toOption.value
        jsonLd.toGraph
    }

  @Benchmark
  def toGraphJena100(): Unit =
    test100ToGrphNoVersion.foreach {
      case (_, n, json) =>
        val model = parseModel(json).value
        asRdfGraph(n, model).toOption.value
    }

}
