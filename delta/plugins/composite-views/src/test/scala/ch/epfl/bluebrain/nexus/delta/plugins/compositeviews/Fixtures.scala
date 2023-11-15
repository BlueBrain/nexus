package ch.epfl.bluebrain.nexus.delta.plugins.compositeviews

import ch.epfl.bluebrain.nexus.delta.kernel.utils.ClasspathResourceLoader
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.model.contexts
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.api.{JsonLdApi, JsonLdJavaApi}
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.{ContextValue, RemoteContextResolution}
import ch.epfl.bluebrain.nexus.delta.sdk.syntax._

trait Fixtures {

  import Fixtures._

  implicit val api: JsonLdApi = JsonLdJavaApi.strict

  implicit val rcr: RemoteContextResolution = RemoteContextResolution.fixedIO(
    iri"http://music.com/context"   -> ContextValue.fromFile("indexing/music-context.json"),
    contexts.compositeViews         -> ContextValue.fromFile("contexts/composite-views.json"),
    contexts.compositeViewsMetadata -> ContextValue.fromFile("contexts/composite-views-metadata.json"),
    Vocabulary.contexts.metadata    -> ContextValue.fromFile("contexts/metadata.json"),
    Vocabulary.contexts.error       -> ContextValue.fromFile("contexts/error.json"),
    Vocabulary.contexts.shacl       -> ContextValue.fromFile("contexts/shacl.json"),
    Vocabulary.contexts.statistics  -> ContextValue.fromFile("/contexts/statistics.json"),
    Vocabulary.contexts.offset      -> ContextValue.fromFile("/contexts/offset.json"),
    Vocabulary.contexts.tags        -> ContextValue.fromFile("contexts/tags.json"),
    Vocabulary.contexts.search      -> ContextValue.fromFile("contexts/search.json")
  )
}

object Fixtures {
  implicit private val loader: ClasspathResourceLoader = ClasspathResourceLoader()
}
