package ch.epfl.bluebrain.nexus.delta.rdf.shacl

import cats.effect.IO
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.nxsh
import ch.epfl.bluebrain.nexus.delta.rdf.graph.Graph
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.api.JsonLdApi
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.RemoteContextResolution
import org.apache.jena.query.{Dataset, DatasetFactory}
import org.apache.jena.rdf.model._
import org.topbraid.jenax.util.JenaDatatypes
import org.topbraid.shacl.engine.{Constraint, ShapesGraph}
import org.topbraid.shacl.validation.{ValidationEngine, ValidationEngineConfiguration}

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
final class ShaclEngine private (dataset: Dataset, shapesGraphURI: URI, shapesGraph: ShapesGraph)
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

object ShaclEngine {

  /**
    * Validates a given graph against the SHACL shapes spec.
    *
    * @param shapesGraph
    *   the shapes Graph to test against the SHACL shapes spec
    * @param reportDetails
    *   true to also include the sh:detail (more verbose) and false to omit them
    * @return
    *   an option of [[ValidationReport]] with the validation results
    */
  def apply(
      shapesGraph: Graph,
      reportDetails: Boolean
  )(implicit api: JsonLdApi, shaclShapesGraph: ShaclShapesGraph, rcr: RemoteContextResolution): IO[ValidationReport] =
    apply(shapesGraph, shaclShapesGraph, validateShapes = true, reportDetails = reportDetails)

  /**
    * Validates a given data Graph against all shapes from a given shapes Model.
    *
    * @param dataGraph
    *   the data Graph
    * @param shapesGraph
    *   the shapes Graph
    * @param reportDetails
    *   true to also include the sh:detail (more verbose) and false to omit them
    * @return
    *   an option of [[ValidationReport]] with the validation results
    */
  def apply(
      dataGraph: Graph,
      shapesGraph: Graph,
      reportDetails: Boolean
  )(implicit api: JsonLdApi, rcr: RemoteContextResolution): IO[ValidationReport] =
    apply(dataGraph, ShaclShapesGraph(shapesGraph), validateShapes = false, reportDetails)

  /**
    * Validates a given data Graph against all shapes from a given shapes graph.
    *
    * @param graph
    *   the data Graph
    * @param shapesGraph
    *   the shapes graph
    * @param validateShapes
    *   true to also validate the shapes graph
    * @param reportDetails
    *   true to also include the sh:detail (more verbose) and false to omit them
    * @return
    *   an option of [[ValidationReport]] with the validation results
    */
  def apply(
      graph: Graph,
      shapesGraph: ShaclShapesGraph,
      validateShapes: Boolean,
      reportDetails: Boolean
  )(implicit api: JsonLdApi, rcr: RemoteContextResolution): IO[ValidationReport] =
    apply(DatasetFactory.wrap(graph.value), shapesGraph, validateShapes, reportDetails)

  private def apply(
      dataset: Dataset,
      shapesGraph: ShaclShapesGraph,
      validateShapes: Boolean,
      reportDetails: Boolean
  )(implicit api: JsonLdApi, rcr: RemoteContextResolution): IO[ValidationReport] = {
    // Create Dataset that contains both the data model and the shapes model
    // (here, using a temporary URI for the shapes graph)
    dataset.addNamedModel(shapesGraph.uri.toString, shapesGraph.model)
    val engine = new ShaclEngine(dataset, shapesGraph.uri, shapesGraph.value)
    engine.setConfiguration(
      new ValidationEngineConfiguration().setReportDetails(reportDetails).setValidateShapes(validateShapes)
    )
    IO.delay {
      engine.applyEntailments()
      engine.validateAll()
    }.flatMap(ValidationReport(_))
  }
}
