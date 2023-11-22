package ch.epfl.bluebrain.nexus.delta.plugins.blazegraph

import cats.effect.IO
import ch.epfl.bluebrain.nexus.delta.kernel.utils.ClasspathResourceLoader
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.model.BlazegraphViewValue
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.model.contexts.{blazegraph, blazegraphMetadata}
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.api.{JsonLdApi, JsonLdJavaApi}
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.{ContextValue, RemoteContextResolution}

trait Fixtures {

  implicit private val loader: ClasspathResourceLoader = ClasspathResourceLoader()

  implicit val api: JsonLdApi = JsonLdJavaApi.strict

  implicit val rcr: RemoteContextResolution = RemoteContextResolution.fixedIO(
    blazegraph                     -> ContextValue.fromFile("contexts/sparql.json"),
    blazegraphMetadata             -> ContextValue.fromFile("contexts/sparql-metadata.json"),
    Vocabulary.contexts.metadata   -> ContextValue.fromFile("contexts/metadata.json"),
    Vocabulary.contexts.error      -> ContextValue.fromFile("contexts/error.json"),
    Vocabulary.contexts.shacl      -> ContextValue.fromFile("contexts/shacl.json"),
    Vocabulary.contexts.statistics -> ContextValue.fromFile("contexts/statistics.json"),
    Vocabulary.contexts.offset     -> ContextValue.fromFile("contexts/offset.json"),
    Vocabulary.contexts.tags       -> ContextValue.fromFile("contexts/tags.json"),
    Vocabulary.contexts.search     -> ContextValue.fromFile("contexts/search.json")
  )

  val defaultProperties: IO[Map[String, String]] =
    loader.propertiesOf("blazegraph/index.properties")

  def alwaysValidate: ValidateBlazegraphView = (_: BlazegraphViewValue) => IO.unit
}

object Fixtures extends Fixtures
