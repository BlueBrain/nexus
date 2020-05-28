package ch.epfl.bluebrain.nexus.rdf.shacl

import cats.implicits._
import ch.epfl.bluebrain.nexus.rdf.Iri.AbsoluteIri
import ch.epfl.bluebrain.nexus.rdf.Node.{BNode, IriNode}
import ch.epfl.bluebrain.nexus.rdf.jena.syntax.all._
import ch.epfl.bluebrain.nexus.rdf.jsonld.syntax._
import ch.epfl.bluebrain.nexus.rdf.shacl.Vocabulary._
import ch.epfl.bluebrain.nexus.rdf.syntax.all._
import io.circe.parser.parse
import io.circe.{Encoder, Json}
import org.apache.jena.rdf.model.Resource

import scala.io.Source

/**
  * Data type that represents the outcome of validating data against a shacl schema.
  *
  * @param conforms      true if the validation was successful and false otherwise
  * @param targetedNodes the number of target nodes that were touched per shape
  * @param json          the detailed message of the validator
  */
final case class ValidationReport(conforms: Boolean, targetedNodes: Int, json: Json) {

  /**
    * @param ignoreTargetedNodes flag to decide whether or not ''targetedNodes''
    *                         should be ignored from the validation logic
    * @return true if the validation report has been successful or false otherwise
    */
  def isValid(ignoreTargetedNodes: Boolean = false): Boolean =
    (ignoreTargetedNodes && conforms) || (!ignoreTargetedNodes && targetedNodes > 0 && conforms)
}

object ValidationReport {

  final def apply(report: Resource): Either[String, ValidationReport] =
    // format: off
    for {
      tmp     <- report.getModel.asRdfGraph(BNode())
      subject <- tmp.triples.find { case (_, p ,_ ) => p == IriNode(sh.conforms) }.map(_._1).toRight("Unable to find predicate sh:conforms in the validation report graph")
      graph    = tmp.withRoot(subject)
      cursor   = graph.cursor
      conforms <- cursor.down(sh.conforms).as[Boolean].leftMap(_.message)
      targeted <- cursor.down(nxsh.targetedNodes).as[Int].leftMap(_.message)
      json     <- graph.toJson(shaclCtx)
    } yield ValidationReport(conforms, targeted, json.removeKeys("@context", "@id").addContext(shaclCtxUri))
  // format: on

  private val shaclCtxUri: AbsoluteIri = url"https://bluebrain.github.io/nexus/contexts/shacl-20170720.json"
  private val shaclCtx: Json           = jsonContentOf("/shacl-context-resp.json")

  implicit val reportEncoder: Encoder[ValidationReport] = Encoder.instance(_.json)

  private def jsonContentOf(resourcePath: String): Json =
    parse(Source.fromInputStream(getClass.getResourceAsStream(resourcePath)).mkString)
      .getOrElse(throw new IllegalArgumentException)
}
