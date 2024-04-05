package ch.epfl.bluebrain.nexus.delta.rdf.shacl

import cats.effect.IO
import ch.epfl.bluebrain.nexus.delta.rdf.graph.Graph
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.RemoteContextResolution
import org.apache.jena.query.DatasetFactory
import org.apache.jena.rdf.model.Model
import org.topbraid.jenax.util.SystemTriples
import org.topbraid.shacl.arq.SHACLFunctions
import org.topbraid.shacl.engine.ShapesGraph
import org.topbraid.shacl.util.SHACLUtil
import org.topbraid.shacl.validation.ValidationEngineConfiguration
import ch.epfl.bluebrain.nexus.delta.rdf.shacl.ValidateShacl.ShaclShapesGraph

import java.net.URI

final class ValidateShacl(shaclShapes: Model, shaclVocabulary: Model)(implicit rcr: RemoteContextResolution) {

  private val shaclShapesGraph = prepareShapesGraph(shaclShapes)

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
  def apply(shapesGraph: Graph, reportDetails: Boolean): IO[ValidationReport] =
    validate(
      shapesGraph,
      shaclShapesGraph,
      validateShapes = true,
      reportDetails = reportDetails
    )

  /**
    * Validates a given data Graph against all shapes from a given shapes graph.
    *
    * @param graph
    *   the data Graph
    * @param shapesGraph
    *   the shapes graph
    * @param reportDetails
    *   true to also include the sh:detail (more verbose) and false to omit them
    * @return
    *   an option of [[ValidationReport]] with the validation results
    */
  def apply(graph: Graph, shapesGraph: Graph, reportDetails: Boolean): IO[ValidationReport] = {
    val s = prepareShapesGraph(DatasetFactory.wrap(shapesGraph.value).getDefaultModel)
    validate(graph, s, validateShapes = false, reportDetails = reportDetails)
  }

  private def validate(
      graph: Graph,
      shapesGraph: ShaclShapesGraph,
      validateShapes: Boolean,
      reportDetails: Boolean
  ): IO[ValidationReport] = {
    val dataset = DatasetFactory.wrap(graph.value)
    for {
      engine   <- IO.delay {
                    // Create Dataset that contains both the data model and the shapes model
                    // (here, using a temporary URI for the shapes graph)
                    dataset.addNamedModel(shapesGraph.uri.toString, shapesGraph.model)
                    val engine = new ShaclEngine(dataset, shapesGraph.uri, shapesGraph.value)
                    engine.setConfiguration(
                      new ValidationEngineConfiguration()
                        .setReportDetails(reportDetails)
                        .setValidateShapes(validateShapes)
                    )
                    engine.applyEntailments()
                    engine
                  }
      resource <- IO.delay(engine.validateAll())
      report   <- ValidationReport(resource)
    } yield report
  }

  private def prepareShapesGraph(model: Model) = {
    val finalShapesModel = model.add(SystemTriples.getVocabularyModel)
    model.add(shaclVocabulary)

    // Make sure all sh:Functions are registered
    SHACLFunctions.registerFunctions(finalShapesModel)
    ShaclShapesGraph(SHACLUtil.createRandomShapesGraphURI(), new ShapesGraph(finalShapesModel))
  }
}

object ValidateShacl {

  private case class ShaclShapesGraph(uri: URI, value: ShapesGraph) {
    val model: Model = value.getShapesModel
  }

  def apply(rcr: RemoteContextResolution): IO[ValidateShacl] =
    for {
      shaclShaclShapes <- ShaclFileLoader.readShaclShapes
      shaclVocabulary  <- ShaclFileLoader.readShaclVocabulary
    } yield new ValidateShacl(shaclShaclShapes, shaclVocabulary)(rcr)

}
