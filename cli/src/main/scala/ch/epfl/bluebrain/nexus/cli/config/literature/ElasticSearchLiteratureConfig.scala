package ch.epfl.bluebrain.nexus.cli.config.literature

import ch.epfl.bluebrain.nexus.cli.config.ElasticSearchConfig
import ch.epfl.bluebrain.nexus.cli.config.literature.ElasticSearchLiteratureConfig.ModelIndex
import ch.epfl.bluebrain.nexus.cli.utils.Codecs
import org.http4s.Uri
import pureconfig.ConfigConvert
import pureconfig.generic.semiauto.deriveConvert

/**
  * ElasticSearch literature indices information.
  *
  * @param endpoint     the ElasticSearch 7.x API endpoint
  * @param modelIndices the sequence of model names and ES index name
  */
final case class ElasticSearchLiteratureConfig(
    endpoint: Uri,
    modelIndices: Seq[ModelIndex]
) {
  val elasticSearchConfig: ElasticSearchConfig = ElasticSearchConfig(endpoint)
  val modelIndicesMap: Map[String, String]     = modelIndices.map { m => m.name -> m.index }.toMap
}

object ElasticSearchLiteratureConfig extends Codecs {

  final case class ModelIndex(name: String, index: String)
  object ModelIndex {
    implicit final val modelIndexConfigConvert: ConfigConvert[ModelIndex] =
      deriveConvert[ModelIndex]
  }
  implicit final val elasticSearchLiteratureConfigConvert: ConfigConvert[ElasticSearchLiteratureConfig] =
    deriveConvert[ElasticSearchLiteratureConfig]
}
