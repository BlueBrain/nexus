package ch.epfl.bluebrain.nexus.delta.rdf.shacl

import org.apache.jena.rdf.model.Model
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

  /**
    * Creates a [[ShaclShapesGraph]] initializing  and registering the required validation components from the passed model.
    */
  def apply(model: Model): ShaclShapesGraph = {
    val finalShapesModel = ValidationUtil.ensureToshTriplesExist(model)
    // Make sure all sh:Functions are registered
    SHACLFunctions.registerFunctions(finalShapesModel)
    ShaclShapesGraph(SHACLUtil.createRandomShapesGraphURI(), new ShapesGraph(finalShapesModel))
  }
}
