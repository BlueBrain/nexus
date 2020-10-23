package ch.epfl.bluebrain.nexus.delta.rdf.jena.writer

import java.io.{OutputStream, OutputStreamWriter, Writer}

import cats.implicits._
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.rdf
import ch.epfl.bluebrain.nexus.delta.rdf.implicits._
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.JsonLdContext
import org.apache.jena.graph.{Graph, Node}
import org.apache.jena.rdf.model.Resource
import org.apache.jena.riot._
import org.apache.jena.riot.system.PrefixMap
import org.apache.jena.riot.writer.WriterGraphRIOTBase
import org.apache.jena.sparql.util.{Context, Symbol}

private object DotWriterImpl extends WriterGraphRIOTBase {

  private def escape(str: String): String =
    str.replaceAll(""""""", "\\\\\"")

  private def quote(str: String): String =
    s""""$str""""

  private val primitiveTypes: Set[Class[_]] =
    Set(classOf[java.lang.Integer], classOf[java.lang.Boolean], classOf[java.lang.Double], classOf[java.lang.Float])

  override def write(out: OutputStream, graph: Graph, prefixMap: PrefixMap, baseURI: String, context: Context): Unit =
    write(new OutputStreamWriter(out, "UTF-8"), graph, prefixMap, baseURI, context)

  @SuppressWarnings(Array("OptionGet"))
  override def write(out: Writer, graph: Graph, prefixMap: PrefixMap, baseURI: String, context: Context): Unit = {
    val root = context.get[Resource](DotWriter.ROOT_ID)
    val ctx  = context.get[JsonLdContext](DotWriter.JSONLD_CONTEXT).addAlias("type", rdf.tpe)

    def formatIri(node: Node, useVocab: Boolean = false): Option[String] =
      Option.when(node.isURI)(quote(ctx.compact(iri"${node.getURI}", useVocab)))

    def formatLiteral(node: Node): Option[String] =
      Option.when(node.isLiteral)(node.getLiteralValue match {
        case v if primitiveTypes.contains(v.getClass) => v.toString
        case _                                        => s""""${escape(node.getLiteralLexicalForm)}""""
      })

    def formatBNode(node: Node): Option[String] =
      Option.when(node.isBlank)(quote(s"_:B${node.getBlankNodeLabel}"))

    def formatSubject(node: Node): Option[String] =
      formatIri(node) orElse formatBNode(node)

    def formatPredicate(node: Node): Option[String] =
      formatIri(node, useVocab = true)

    def formatObject(node: Node, useVocab: Boolean): Option[String] =
      formatLiteral(node) orElse formatIri(node, useVocab) orElse formatBNode(node)

    val iter = graph.find()
    out.write(s"""digraph ${formatSubject(root.asNode).get} {\n""")
    while (iter.hasNext) {
      val triple    = iter.next()
      val (s, p, o) = (triple.getSubject, triple.getPredicate, triple.getObject)
      (formatSubject(s), formatPredicate(p), formatObject(o, p.toString == rdf.tpe.toString)).mapN {
        case (ss, pp, oo) => out.write(s"""  $ss -> $oo [label = $pp]\n""")
      }
    }
    out.write("}")
  }

  override def getLang: Lang = DotWriter.DOT
}

object DotWriter {
  final val DOT: Lang = LangBuilder.create("DOT", "application/vnd.graphviz").addFileExtensions("dot").build

  private val dotNamespace                         = "http://jena.apache.org/riot/dot#"
  final private[writer] val JSONLD_CONTEXT: Symbol = Symbol.create(s"${dotNamespace}JSONLD_CONTEXT")
  final private[writer] val ROOT_ID: Symbol        = Symbol.create(s"${dotNamespace}ROOT_ID")
  private val format: RDFFormat                    = new RDFFormat(DOT)

  RDFWriterRegistry.register(DotWriter.DOT, format)
  RDFWriterRegistry.register(format, (_: RDFFormat) => DotWriterImpl)

  /**
    * The Jena [[Context]] to be passed to the [[WriterGraphRIOT]]
    *
    * @param rootResource the top graph node
    * @param context      the resolved context
    * @return the Jena context
    */
  def dotContext(rootResource: Resource, context: JsonLdContext): Context = {
    val ctx = new Context()
    ctx.set(ROOT_ID, rootResource)
    ctx.set(JSONLD_CONTEXT, context)
    ctx
  }

}
