package ch.epfl.bluebrain.nexus.rdf.jena

import cats.implicits._
import org.apache.jena.rdf.model.{Model, ModelFactory}
import org.apache.jena.riot.system.StreamRDFLib
import org.apache.jena.riot.{Lang, RDFParser}

import scala.util.Try

object Jena {

  /**
    * Attempts to parse the argument string as a JSON-LD,
    * @param json the jsonld string representation
    */
  def parse(json: String): Either[String, Model] = {
    Try {
      val model  = ModelFactory.createDefaultModel()
      val stream = StreamRDFLib.graph(model.getGraph)
      RDFParser.create.fromString(json).lang(Lang.JSONLD).parse(stream)
      model
    }.toEither.leftMap(_ => s"Failed to parse string '${json.take(300)}...' as an JSON-LD document.")
  }
}
