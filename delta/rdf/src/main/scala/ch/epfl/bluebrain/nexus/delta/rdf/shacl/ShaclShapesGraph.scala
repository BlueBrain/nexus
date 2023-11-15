package ch.epfl.bluebrain.nexus.delta.rdf.shacl

import cats.effect.IO
import ch.epfl.bluebrain.nexus.delta.kernel.utils.ClasspathResourceLoader
import ch.epfl.bluebrain.nexus.delta.rdf.graph.Graph
import org.apache.jena.graph.Factory.createDefaultGraph
import org.apache.jena.query.DatasetFactory
import org.apache.jena.rdf.model.{Model, ModelFactory}
import org.apache.jena.util.FileUtils
import org.topbraid.shacl.arq.SHACLFunctions
import org.topbraid.shacl.engine.ShapesGraph
import org.topbraid.shacl.util.SHACLUtil
import org.topbraid.shacl.validation.ValidationUtil

import java.net.URI

/**
  * Holds information about the ''ShapesGraph''
  */
final case class ShaclShapesGraph(uri: URI, value: ShapesGraph) {
  val model: Model = value.getShapesModel
}

object ShaclShapesGraph {

  private val loader = ClasspathResourceLoader()

  /**
    * Loads the SHACL shapes graph to validate SHACL shapes graphs
    */
  def shaclShaclShapes: IO[ShaclShapesGraph] =
    loader
      .streamOf("shacl-shacl.ttl")
      .map { is =>
        val model = ModelFactory
          .createModelForGraph(createDefaultGraph())
          .read(is, "http://www.w3.org/ns/shacl-shacl#", FileUtils.langTurtle)
        validateAndRegister(model)
      }

  /**
    * Creates a [[ShaclShapesGraph]] initializing and registering the required validation components from the passed
    * model.
    */
  def apply(graph: Graph): ShaclShapesGraph =
    validateAndRegister(DatasetFactory.wrap(graph.value).getDefaultModel)

  private def validateAndRegister(model: Model) = {
    val finalShapesModel = ValidationUtil.ensureToshTriplesExist(model)
    // Make sure all sh:Functions are registered
    SHACLFunctions.registerFunctions(finalShapesModel)
    ShaclShapesGraph(SHACLUtil.createRandomShapesGraphURI(), new ShapesGraph(finalShapesModel))
  }
}
