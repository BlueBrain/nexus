package ch.epfl.bluebrain.nexus.delta.rdf.shacl

import cats.effect.IO
import ch.epfl.bluebrain.nexus.delta.kernel.utils.ClasspathResourceLoader
import org.apache.jena.graph.GraphMemFactory.createDefaultGraph
import org.apache.jena.rdf.model.{Model, ModelFactory}
import org.apache.jena.util.FileUtils
import org.topbraid.shacl.vocabulary.SH

object ShaclFileLoader {

  private val loader = ClasspathResourceLoader()

  private def readFromTurtleFile(base: String, resourcePath: String) =
    loader
      .streamOf(resourcePath)
      .use { is =>
        IO.blocking {
          ModelFactory
            .createModelForGraph(createDefaultGraph())
            .read(is, base, FileUtils.langTurtle)
        }
      }

  def readShaclShapes: IO[Model] = readFromTurtleFile("http://www.w3.org/ns/shacl-shacl#", "shacl-shacl.ttl")

  def readShaclVocabulary: IO[Model] = readFromTurtleFile(SH.BASE_URI, "rdf/shacl.ttl")
}
