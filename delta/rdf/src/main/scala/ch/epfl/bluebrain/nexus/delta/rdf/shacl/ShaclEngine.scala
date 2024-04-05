package ch.epfl.bluebrain.nexus.delta.rdf.shacl

import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.nxsh
import org.apache.jena.query.Dataset
import org.apache.jena.rdf.model._
import org.topbraid.jenax.util.JenaDatatypes
import org.topbraid.shacl.engine.{Constraint, ShapesGraph}
import org.topbraid.shacl.validation.ValidationEngine

import java.net.URI
import java.util

/**
  * Extend the [[ValidationEngine]] form TopQuadrant in order to add triples to the report with the number of
  * targetedNodes
  *
  * @param dataset
  *   the Dataset to operate on
  * @param shapesGraphURI
  *   the URI of the shapes graph (must be in the dataset)
  * @param shapesGraph
  *   the ShapesGraph with the shapes to validate against
  */
@SuppressWarnings(Array("NullParameter"))
final class ShaclEngine(dataset: Dataset, shapesGraphURI: URI, shapesGraph: ShapesGraph)
    extends ValidationEngine(dataset, shapesGraphURI, shapesGraph, null) {
  private var targetedNodes = 0

  override def validateNodesAgainstConstraint(focusNodes: util.Collection[RDFNode], constraint: Constraint): Unit = {
    super.validateNodesAgainstConstraint(focusNodes, constraint)
    targetedNodes += focusNodes.size()
  }

  override def validateAll(): Resource = {
    val r = super.validateAll()
    Option(r).fold(r)(_.addLiteral(toProperty(nxsh.targetedNodes), JenaDatatypes.createInteger(targetedNodes)))
  }

  private def toProperty(iri: Iri): Property =
    ResourceFactory.createProperty(iri.toString)
}
