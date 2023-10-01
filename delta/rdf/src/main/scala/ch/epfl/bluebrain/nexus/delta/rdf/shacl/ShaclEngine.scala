package ch.epfl.bluebrain.nexus.delta.rdf.shacl

import cats.effect.IO
import ch.epfl.bluebrain.nexus.delta.kernel.utils.ClasspathResourceUtils.ioStreamOf
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.nxsh
import ch.epfl.bluebrain.nexus.delta.rdf.graph.Graph
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.api.JsonLdApi
import org.apache.jena.graph.Factory.createDefaultGraph
import org.apache.jena.query.{Dataset, DatasetFactory}
import org.apache.jena.rdf.model._
import org.apache.jena.util.FileUtils
import org.topbraid.jenax.util.JenaDatatypes
import org.topbraid.shacl.arq.SHACLFunctions
import org.topbraid.shacl.engine.{Constraint, ShapesGraph}
import org.topbraid.shacl.validation.{ValidationEngine, ValidationEngineConfiguration, ValidationUtil}

import java.io.InputStream
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
  implicit private val classLoader: ClassLoader = getClass.getClassLoader

  // TODO memoization requires either an impure hack or refactor to do properly (using Deferred)
  private val shaclGraphIO: IO[Graph] =
    ioStreamOf("shacl-shacl.ttl").flatMap(createSchaclModelAndGraph)

  private def createSchaclModelAndGraph(is: InputStream): IO[Graph] =
    IO {
      val model            = ModelFactory
        .createModelForGraph(createDefaultGraph())
        .read(is, "http://www.w3.org/ns/shacl-shacl#", FileUtils.langTurtle)
      val finalShapesModel = ValidationUtil.ensureToshTriplesExist(model)
      // Make sure all sh:Functions are registered
      SHACLFunctions.registerFunctions(finalShapesModel)
      Graph.unsafe(DatasetFactory.create(finalShapesModel).asDatasetGraph())
    }

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
  )(implicit api: JsonLdApi): IO[ValidationReport] =
    for {
      shaclGraph <- shaclGraphIO
      shapes      = ShaclShapesGraph(shaclGraph)
      report     <- apply(shapesGraph, shapes, validateShapes = true, reportDetails = reportDetails)
    } yield report

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
  )(implicit api: JsonLdApi): IO[ValidationReport] =
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
  )(implicit api: JsonLdApi): IO[ValidationReport] =
    apply(DatasetFactory.wrap(graph.value), shapesGraph, validateShapes, reportDetails)

  private def apply(
      dataset: Dataset,
      shapesGraph: ShaclShapesGraph,
      validateShapes: Boolean,
      reportDetails: Boolean
  )(implicit api: JsonLdApi): IO[ValidationReport] =
    // Create Dataset that contains both the data model and the shapes model
    // (here, using a temporary URI for the shapes graph)
    for {
      _        <- IO(dataset.addNamedModel(shapesGraph.uri.toString, shapesGraph.model))
      resource <- mkRdfResource(dataset, shapesGraph, validateShapes, reportDetails)
      report   <- ValidationReport(resource)
    } yield report

  private def mkRdfResource(
      dataset: Dataset,
      shapesGraph: ShaclShapesGraph,
      validateShapes: Boolean,
      reportDetails: Boolean
  ): IO[Resource] = IO {
    val engine = new ShaclEngine(dataset, shapesGraph.uri, shapesGraph.value)
    val config = new ValidationEngineConfiguration().setReportDetails(reportDetails).setValidateShapes(validateShapes)
    engine.setConfiguration(config)
    engine.applyEntailments()
    engine.validateAll()
  }
}
