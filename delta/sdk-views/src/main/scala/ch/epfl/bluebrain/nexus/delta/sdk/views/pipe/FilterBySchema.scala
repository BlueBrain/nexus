package ch.epfl.bluebrain.nexus.delta.sdk.views.pipe

import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.nxv
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.ExpandedJsonLd
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.decoder.JsonLdDecoder
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.decoder.semiauto.deriveJsonLdDecoder
import ch.epfl.bluebrain.nexus.delta.sdk.views.model.IndexingData
import monix.bio.Task

/**
  * Filters a resource according to its schema
  */
object FilterBySchema {

  final private case class Config(types: Set[Iri])

  val name = "filterBySchema"

  val pipe: Pipe = {
    implicit val configDecoder: JsonLdDecoder[Config] = deriveJsonLdDecoder[Config]
    Pipe.withConfig(
      name,
      (config: Config, data: IndexingData) =>
        Task.pure(
          Option.when(config.types.isEmpty || config.types.contains(data.schema.iri))(data)
        )
    )
  }

  private val typesKey = nxv + "types"

  def definition(set: Set[Iri]): PipeDef = {
    PipeDef.withConfig(
      name,
      set.foldLeft(ExpandedJsonLd.empty.copy(rootId = nxv + name)) { case (expanded, tpe) =>
        expanded.add(typesKey, tpe)
      }
    )
  }

  def extractTypes(pipeDef: PipeDef): Either[PipeError.InvalidConfig, Set[Iri]] =
    pipe.parse(pipeDef.config).map { _.asInstanceOf[Config].types }

}
