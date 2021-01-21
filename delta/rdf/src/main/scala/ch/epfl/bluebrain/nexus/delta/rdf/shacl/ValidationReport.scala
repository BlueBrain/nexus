package ch.epfl.bluebrain.nexus.delta.rdf.shacl

import cats.syntax.all._
import ch.epfl.bluebrain.nexus.delta.kernel.utils.ClasspathResourceUtils
import ch.epfl.bluebrain.nexus.delta.rdf.Triple.predicate
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.{contexts, sh}
import ch.epfl.bluebrain.nexus.delta.rdf.graph.Graph
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.{ContextValue, RemoteContextResolution}
import io.circe.{Encoder, Json}
import monix.bio.IO
import org.apache.jena.rdf.model.Resource

/**
  * Data type that represents the outcome of validating data against a shacl schema.
  *
  * @param conforms      true if the validation was successful and false otherwise
  * @param targetedNodes the number of target nodes that were touched per shape
  * @param json          the detailed message of the validator
  */
final case class ValidationReport private (conforms: Boolean, targetedNodes: Int, json: Json) {

  /**
    * @param ignoreTargetedNodes flag to decide whether or not ''targetedNodes''
    *                         should be ignored from the validation logic
    * @return true if the validation report has been successful or false otherwise
    */
  def isValid(ignoreTargetedNodes: Boolean = false): Boolean =
    (ignoreTargetedNodes && conforms) || (!ignoreTargetedNodes && targetedNodes > 0 && conforms)
}

object ValidationReport {

  implicit private val classLoader: ClassLoader = getClass.getClassLoader
  private val shaclCtx: ContextValue            = ContextValue(contexts.shacl)

  implicit private val rcr: RemoteContextResolution =
    RemoteContextResolution.fixedIOResource(
      contexts.shacl -> ClasspathResourceUtils.ioJsonContentOf("/contexts/shacl.json").memoizeOnSuccess
    )

  final def apply(report: Resource): IO[String, ValidationReport] = {
    val tmpGraph = Graph.unsafe(report.getModel)
    for {
      subject       <- IO.fromEither(
                         tmpGraph
                           .find { case (_, p, _) => p == predicate(sh.conforms) }
                           .map { case (s, _, _) => s }
                           .toRight("Unable to find predicate sh:conforms in the validation report graph")
                       )
      graph          = tmpGraph.replaceRootNode(subject)
      compacted     <- graph.toCompactedJsonLd(shaclCtx).leftMap(_.getMessage)
      json           = compacted.json
      conforms      <- IO.fromEither(json.hcursor.get[Boolean]("conforms").leftMap(_.message))
      targetedNodes <- IO.fromEither(json.hcursor.get[Int]("targetedNodes").leftMap(_.message))
    } yield ValidationReport(conforms, targetedNodes, json)
  }

  implicit val reportEncoder: Encoder[ValidationReport] = Encoder.instance(_.json)
}
